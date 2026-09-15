package com.aistudio.voicenote.cvtr.editor.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aistudio.voicenote.cvtr.audio.AudioConversionException
import com.aistudio.voicenote.cvtr.audio.NoAudioTrackException
import com.aistudio.voicenote.cvtr.audio.PcmDecoder
import com.aistudio.voicenote.cvtr.audio.UnsupportedAudioFormatException
import com.aistudio.voicenote.cvtr.audio.StreamingPcmProcessor
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import kotlin.math.max
import kotlin.math.min

internal const val EDITOR_SAMPLE_RATE = 48_000
internal const val EDITOR_CHANNEL_COUNT = 1

/** Keep decoder work bounded so callers can render in small rolling chunks. */
internal const val MAX_PCM_READ_FRAMES = 1_920

/** A seekable source of normalized mono PCM16 frames. Source frame zero is at 48 kHz. */
internal interface PcmSourceReader : AutoCloseable {
    val sampleRate: Int
    fun read(sourceFrame: Long, frameCount: Int): ShortArray
}

internal fun interface PcmSourceReaderFactory {
    fun open(source: AudioSourceRef): PcmSourceReader
}

/** Creates MediaCodec-backed editor readers for content, file, and provider URIs. */
internal class MediaCodecPcmSourceReaderFactory(
    context: Context,
) : PcmSourceReaderFactory {
    private val appContext = context.applicationContext

    override fun open(source: AudioSourceRef): PcmSourceReader =
        MediaCodecPcmSourceReader(appContext, source)
}

/**
 * A small random-access adapter around the decoder used by the conversion pipeline.
 * Each read seeks to a decoder sync point, discards earlier decoded frames, and returns
 * at most [MAX_PCM_READ_FRAMES] exact normalized frames.
 */
internal class MediaCodecPcmSourceReader(
    private val context: Context,
    private val source: AudioSourceRef,
) : PcmSourceReader {
    override val sampleRate: Int = EDITOR_SAMPLE_RATE

    private val lock = Any()
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var decoderStarted = false
    private var closed = false
    private var trackFormat: MediaFormat
    private var trackSampleRate: Int
    private var trackChannels: Int
    private var trackPcmEncoding: Int

    init {
        try {
            setDataSource()
            val track = findAudioTrack()
            extractor.selectTrack(track.first)
            trackFormat = track.second
            trackSampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            trackChannels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            trackPcmEncoding = pcmEncoding(trackFormat)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw UnsupportedAudioFormatException("Track audio tidak memiliki MIME type.")
            decoder = MediaCodec.createDecoderByType(mime).also { codec ->
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                decoderStarted = true
            }
        } catch (error: AudioConversionException) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            throw UnsupportedAudioFormatException(
                "Gagal menyiapkan decoder audio: ${error.localizedMessage}"
            )
        }
    }

    override fun read(sourceFrame: Long, frameCount: Int): ShortArray = synchronized(lock) {
        check(!closed) { "PCM source reader is closed" }
        require(sourceFrame >= 0L) { "sourceFrame must be non-negative" }
        require(frameCount >= 0) { "frameCount must be non-negative" }
        require(frameCount <= MAX_PCM_READ_FRAMES) {
            "PCM reads are limited to $MAX_PCM_READ_FRAMES frames"
        }
        if (frameCount == 0) return@synchronized ShortArray(0)

        val durationFrames = normalizedDurationFrames(source.durationMs)
        if (durationFrames != null && sourceFrame >= durationFrames) return@synchronized ShortArray(0)
        val requestedCount = durationFrames?.let {
            min(frameCount.toLong(), it - sourceFrame).toInt()
        } ?: frameCount
        if (requestedCount <= 0) return@synchronized ShortArray(0)

        val codec = checkNotNull(decoder) { "PCM decoder is unavailable" }
        val seekUs = sourceFrame * 1_000_000L / EDITOR_SAMPLE_RATE
        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()

        val output = ShortArray(requestedCount)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var pcmProcessor = StreamingPcmProcessor(trackChannels, trackSampleRate)
        var nextDecodedFrame = 0L
        var writtenUntil = sourceFrame

        while (!outputEnded && writtenUntil < sourceFrame + requestedCount) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Decoder input buffer is unavailable")
                    inputBuffer.clear()
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                try {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val decoded = PcmDecoder.decode(
                            outputBuffer = outputBuffer,
                            offset = bufferInfo.offset,
                            size = bufferInfo.size,
                            encoding = trackPcmEncoding,
                            channels = trackChannels,
                        )
                        val normalized = pcmProcessor.process(decoded)
                        val timestampFrame = presentationFrame(bufferInfo.presentationTimeUs)
                        val chunkStart = max(timestampFrame, nextDecodedFrame)
                        nextDecodedFrame = chunkStart + normalized.size
                        if (normalized.isNotEmpty()) {
                            val copyStart = max(sourceFrame, chunkStart)
                            val copyEnd = min(sourceFrame + requestedCount, chunkStart + normalized.size)
                            if (copyStart < copyEnd) {
                                val sourceOffset = (copyStart - chunkStart).toInt()
                                val destinationOffset = (copyStart - sourceFrame).toInt()
                                val copyCount = (copyEnd - copyStart).toInt()
                                normalized.copyInto(
                                    output,
                                    destinationOffset,
                                    sourceOffset,
                                    sourceOffset + copyCount,
                                )
                                writtenUntil = max(writtenUntil, copyEnd)
                            }
                        }
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outputFormat = codec.outputFormat
                trackSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                trackChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                trackPcmEncoding = pcmEncoding(outputFormat)
                pcmProcessor = StreamingPcmProcessor(trackChannels, trackSampleRate)
            }
        }

        val count = (writtenUntil - sourceFrame).coerceIn(0L, requestedCount.toLong()).toInt()
        return@synchronized if (count == output.size) output else output.copyOf(count)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            if (decoderStarted) runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null
            decoderStarted = false
            runCatching { extractor.release() }
        }
    }

    private fun setDataSource() {
        val uri = Uri.parse(source.uri)
        if (uri.scheme.isNullOrBlank()) {
            extractor.setDataSource(source.uri)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    private fun findAudioTrack(): Pair<Int, MediaFormat> {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return index to format
            }
        }
        throw NoAudioTrackException()
    }

    private fun normalizedDurationFrames(durationMs: Long): Long? = when {
        durationMs == Long.MAX_VALUE -> null
        durationMs <= 0L -> 0L
        durationMs > Long.MAX_VALUE / EDITOR_SAMPLE_RATE -> Long.MAX_VALUE
        else -> durationMs * EDITOR_SAMPLE_RATE / 1_000L
    }

    private fun presentationFrame(presentationTimeUs: Long): Long =
        if (presentationTimeUs <= 0L) 0L else presentationTimeUs * EDITOR_SAMPLE_RATE / 1_000_000L

    private fun pcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
