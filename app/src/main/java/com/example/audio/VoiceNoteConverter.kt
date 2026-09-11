package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

data class AudioConversionResult(
    val outputFile: File,
    val durationSeconds: Int,
    val waveform: List<Int>, // 100 bars (0..31) for Telegram UI bubble
    val bitrateKbps: Int = 32,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val originalFileName: String,
    val originalSize: Long
)

sealed class AudioConversionException(message: String) : Exception(message)
class NoAudioTrackException(message: String = "File input tidak memiliki track audio.") : AudioConversionException(message)
class UnsupportedAudioFormatException(message: String = "Format file tidak didukung untuk ekstraksi audio.") : AudioConversionException(message)
class ConversionFailedException(message: String) : AudioConversionException(message)

internal fun opusPacketDurationSamples(packetData: ByteArray): Long {
    if (packetData.isEmpty()) return 0L
    val toc = packetData[0].toInt() and 0xFF
    val configuration = toc ushr 3
    val samplesPerFrame = when (configuration) {
        in 0..11 -> when (configuration % 4) {
            0 -> 480
            1 -> 960
            2 -> 1_920
            else -> 2_880
        }
        in 12..15 -> if (configuration % 2 == 0) 480 else 960
        else -> when (configuration % 4) {
            0 -> 120
            1 -> 240
            2 -> 480
            else -> 960
        }
    }
    val frameCount = when (toc and 0x03) {
        0 -> 1
        1, 2 -> 2
        else -> if (packetData.size >= 2) packetData[1].toInt() and 0x3F else return 0L
    }
    val totalSamples = samplesPerFrame.toLong() * frameCount
    return totalSamples.takeIf { frameCount > 0 && it <= 5_760L } ?: 0L
}

internal fun opusPreSkipSamples(outputFormat: MediaFormat): Int {
    val delayNs = outputFormat.getByteBuffer("csd-1")?.duplicate()?.let { buffer ->
        buffer.order(ByteOrder.nativeOrder())
        if (buffer.remaining() >= Long.SIZE_BYTES) buffer.long else null
    }
    val fromNanoseconds = delayNs?.takeIf { it >= 0L }?.let {
        ((it * 48_000L + 500_000_000L) / 1_000_000_000L).toInt()
    }
    val fromDelayKey = if (outputFormat.containsKey("encoder-delay")) {
        outputFormat.getInteger("encoder-delay")
    } else {
        null
    }
    val fromIdentificationHeader = outputFormat.getByteBuffer("csd-0")
        ?.duplicate()
        ?.takeIf { it.remaining() >= 12 }
        ?.let { buffer ->
            val start = buffer.position()
            (buffer.get(start + 10).toInt() and 0xFF) or
                ((buffer.get(start + 11).toInt() and 0xFF) shl 8)
        }
    val defaultOpusPreSkip = 312
    return (fromNanoseconds ?: fromDelayKey ?: fromIdentificationHeader)
        ?.takeIf { it in 0..65_535 }
        ?: run {
            Log.w(
                "VoiceNoteConverter",
                "Encoder did not report pre-skip delay; using default $defaultOpusPreSkip samples"
            )
            defaultOpusPreSkip
        }
}

internal fun trimFrameRange(
    bufferStartUs: Long,
    frameCount: Int,
    sampleRate: Int,
    trimStartUs: Long,
    trimEndUs: Long
): IntRange {
    if (frameCount <= 0 || sampleRate <= 0) return IntRange.EMPTY
    val bufferDurationUs = frameCount.toLong() * 1_000_000L / sampleRate
    val bufferEndUs = bufferStartUs + bufferDurationUs
    if (bufferEndUs <= trimStartUs || bufferStartUs >= trimEndUs) return IntRange.EMPTY

    fun frameOffsetAtOrAfter(timeUs: Long): Int {
        val relativeUs = (timeUs - bufferStartUs).coerceAtLeast(0L)
        val numerator = relativeUs * sampleRate
        return ((numerator + 999_999L) / 1_000_000L).toInt().coerceIn(0, frameCount)
    }

    val startFrame = frameOffsetAtOrAfter(trimStartUs)
    val endFrame = if (trimEndUs == Long.MAX_VALUE) {
        frameCount
    } else {
        frameOffsetAtOrAfter(trimEndUs)
    }
    return if (startFrame < endFrame) startFrame until endFrame else IntRange.EMPTY
}

internal class StreamingPcmProcessor(
    private val channels: Int,
    private val sourceSampleRate: Int,
    private val targetSampleRate: Int = 48_000
) {
    private var totalInputFrames = 0L
    private var nextSourcePosition = 0.0
    private var previousSample: Short? = null

    fun process(interleavedSamples: ShortArray): ShortArray {
        val safeChannels = channels.coerceAtLeast(1)
        val frameCount = interleavedSamples.size / safeChannels
        if (frameCount == 0 || sourceSampleRate <= 0 || targetSampleRate <= 0) {
            return ShortArray(0)
        }
        val mono = ShortArray(frameCount) { frame ->
            var sum = 0L
            repeat(safeChannels) { channel ->
                sum += interleavedSamples[frame * safeChannels + channel]
            }
            (sum / safeChannels).toShort()
        }
        if (sourceSampleRate == targetSampleRate) {
            totalInputFrames += mono.size
            previousSample = mono.last()
            nextSourcePosition = totalInputFrames.toDouble()
            return mono
        }

        val chunkStart = totalInputFrames
        val chunkEnd = chunkStart + mono.lastIndex
        val step = sourceSampleRate.toDouble() / targetSampleRate.toDouble()
        val output = ArrayList<Short>((mono.size * targetSampleRate / sourceSampleRate) + 2)
        while (nextSourcePosition <= chunkEnd.toDouble()) {
            val lowerIndex = floor(nextSourcePosition).toLong()
            val upperIndex = kotlin.math.ceil(nextSourcePosition).toLong()
            if (lowerIndex < chunkStart && previousSample == null) break

            fun sampleAt(index: Long): Short = if (index < chunkStart) {
                requireNotNull(previousSample)
            } else {
                mono[(index - chunkStart).toInt()]
            }

            val lower = sampleAt(lowerIndex).toDouble()
            val upper = sampleAt(upperIndex).toDouble()
            val fraction = nextSourcePosition - lowerIndex
            output += (lower + (upper - lower) * fraction)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            nextSourcePosition += step
        }
        totalInputFrames += mono.size
        previousSample = mono.last()
        return output.toShortArray()
    }
}

private class StreamingOpusEncoder(
    codecName: String,
    private val oggWriter: OggOpusWriter,
    private val checkActive: () -> Unit
) {
    private val encoder = MediaCodec.createByCodecName(codecName)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var submittedSamples = 0L
    private var outputEnded = false
    private var started = false
    private var released = false
    var preSkipSamples: Int? = null
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_COMPLEXITY, 5)
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            started = true
        } catch (error: Throwable) {
            runCatching { encoder.release() }
            released = true
            throw error
        }
    }

    fun write(samples: ShortArray) {
        var offset = 0
        var emptyPolls = 0
        while (offset < samples.size) {
            checkActive()
            val inputIndex = encoder.dequeueInputBuffer(10_000L)
            if (inputIndex < 0) {
                if (++emptyPolls > 1_000) {
                    throw ConversionFailedException("Encoder Opus berhenti merespons.")
                }
                drain(waitForEnd = false)
                continue
            }
            emptyPolls = 0
            val inputBuffer = encoder.getInputBuffer(inputIndex)
                ?: throw ConversionFailedException("Buffer input encoder Opus tidak tersedia.")
            inputBuffer.clear()
            inputBuffer.order(ByteOrder.nativeOrder())
            val count = minOf(samples.size - offset, inputBuffer.remaining() / 2)
            if (count == 0) {
                throw ConversionFailedException("Buffer input encoder Opus terlalu kecil.")
            }
            repeat(count) { inputBuffer.putShort(samples[offset + it]) }
            val presentationTimeUs = submittedSamples * 1_000_000L / 48_000L
            encoder.queueInputBuffer(inputIndex, 0, count * 2, presentationTimeUs, 0)
            offset += count
            submittedSamples += count
            drain(waitForEnd = false)
        }
    }

    fun finish() {
        try {
            var queued = false
            var emptyPolls = 0
            while (!queued) {
                checkActive()
                val inputIndex = encoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val presentationTimeUs = submittedSamples * 1_000_000L / 48_000L
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    queued = true
                } else {
                    if (++emptyPolls > 1_000) {
                        throw ConversionFailedException("Encoder Opus berhenti saat finalisasi.")
                    }
                    drain(waitForEnd = false)
                }
            }
            drain(waitForEnd = true)
        } finally {
            release()
        }
    }

    fun release() {
        if (released) return
        if (started) runCatching { encoder.stop() }
        runCatching { encoder.release() }
        started = false
        released = true
    }

    private fun drain(waitForEnd: Boolean) {
        var emptyPolls = 0
        while (!outputEnded) {
            checkActive()
            when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEnd) return
                    if (++emptyPolls > 1_000) {
                        throw ConversionFailedException("Encoder Opus tidak menghasilkan EOS.")
                    }
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emptyPolls = 0
                    if (preSkipSamples == null) {
                        val delay = opusPreSkipSamples(encoder.outputFormat)
                        preSkipSamples = delay
                        oggWriter.writeHeader(
                            sampleRate = 48_000,
                            channels = 1,
                            preSkipSamples = delay
                        )
                    }
                }
                else -> if (outputIndex >= 0) {
                    emptyPolls = 0
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        check(preSkipSamples != null) {
                            "Encoder Opus menghasilkan audio sebelum format output tersedia."
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        val packetDuration = opusPacketDurationSamples(packet)
                        if (packetDuration == 0L) {
                            throw ConversionFailedException("Encoder menghasilkan paket Opus tidak valid.")
                        }
                        oggWriter.writeAudioPacket(packet, samplesInPacket = packetDuration)
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }
}

object VoiceNoteConverter {
    private const val TAG = "VoiceNoteConverter"
    private const val TARGET_SAMPLE_RATE = 48000
    private const val TARGET_CHANNELS = 1 // Mono
    private const val TARGET_BITRATE = 32000 // 32 kbps

    @JvmStatic
    fun processPcmChunk(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int
    ): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val safeChannels = channels.coerceAtLeast(1)
        val mono = if (safeChannels == 1) {
            samples
        } else {
            ShortArray(samples.size / safeChannels) { frame ->
                var sum = 0L
                for (channel in 0 until safeChannels) {
                    sum += samples[frame * safeChannels + channel].toLong()
                }
                (sum / safeChannels).toShort()
            }
        }
        if (sampleRate <= 0 || sampleRate == TARGET_SAMPLE_RATE) return mono

        val ratio = TARGET_SAMPLE_RATE.toDouble() / sampleRate.toDouble()
        return ShortArray((mono.size * ratio).toInt().coerceAtLeast(1)) { outputIndex ->
            mono[(outputIndex / ratio).toInt().coerceIn(0, mono.lastIndex)]
        }
    }

    internal fun normalizeChunkPeaks(peaks: List<Float>, targetBars: Int): List<Int> {
        if (peaks.isEmpty()) return List(targetBars) { 1 }
        val grouped = List(targetBars) { bar ->
            val start = (bar * peaks.size / targetBars).coerceAtMost(peaks.lastIndex)
            val end = (((bar + 1) * peaks.size + targetBars - 1) / targetBars)
                .coerceIn(start + 1, peaks.size)
            peaks.subList(start, end).average().toFloat()
        }
        val maxPeak = grouped.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return grouped.map { ((it / maxPeak) * 31f).toInt().coerceIn(1, 31) }
    }

    suspend fun getMediaInfo(context: Context, uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri)
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durationMs = durStr?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving media metadata: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        Pair(fileName, durationMs)
    }

    suspend fun extractWaveform(context: Context, inputUri: Uri): List<Int> =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var decoderStarted = false
            try {
                extractor.setDataSource(context, inputUri, null)
                var audioFormat: MediaFormat? = null
                var trackIndex = -1
                for (index in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(index)
                    if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioFormat = candidate
                        trackIndex = index
                        break
                    }
                }
                val format = audioFormat
                    ?: throw NoAudioTrackException("File input tidak memiliki track audio.")
                extractor.selectTrack(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw UnsupportedAudioFormatException()
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
                decoderStarted = true

                val peaks = mutableListOf<Float>()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: throw ConversionFailedException(
                                    "Buffer input decoder tidak tersedia."
                                )
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outputIndex >= 0) {
                        decoder.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                            if (bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.order(ByteOrder.nativeOrder())
                                val shorts = outputBuffer.asShortBuffer()
                                val safeChannels = channels.coerceAtLeast(1)
                                while (shorts.remaining() >= safeChannels) {
                                    val frameCount = minOf(
                                        (4096 / safeChannels).coerceAtLeast(1),
                                        shorts.remaining() / safeChannels
                                    )
                                    val chunk = ShortArray(frameCount * safeChannels)
                                    shorts.get(chunk)
                                    val monoChunk = processPcmChunk(chunk, channels, sampleRate)
                                    if (monoChunk.isNotEmpty()) {
                                        peaks += monoChunk.maxOf { abs(it.toInt()) }.toFloat()
                                    }
                                }
                            }
                        }
                        outputEnded = bufferInfo.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
                normalizeChunkPeaks(peaks, targetBars = 100)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AudioConversionException) {
                throw error
            } catch (error: Exception) {
                throw UnsupportedAudioFormatException(
                    "Gagal menganalisis waveform: ${error.localizedMessage}"
                )
            } finally {
                if (decoderStarted) runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
            }
        }

    suspend fun convertToTelegramVoiceNote(
        context: Context,
        inputUri: Uri,
        trimStartMs: Long = 0L,
        trimEndMs: Long = Long.MAX_VALUE,
        pitchSemitones: Float = 0f,
        onProgress: (Float) -> Unit = {}
    ): AudioConversionResult = withContext(Dispatchers.IO) {
        require(trimStartMs >= 0L) { "Batas awal trim tidak valid." }
        require(trimEndMs == Long.MAX_VALUE || trimEndMs > trimStartMs) {
            "Batas akhir trim harus lebih besar dari batas awal."
        }
        val fileName = getFileName(context, uri = inputUri)
        val originalSize = getFileSize(context, inputUri)

        // 1. Verify Audio Track with MediaExtractor
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, inputUri, null)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException("Gagal membaca file media: ${e.localizedMessage}")
        }

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        try {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException(
                "Gagal membaca track audio: ${error.localizedMessage}"
            )
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            throw NoAudioTrackException("File input tidak memiliki track audio.")
        }

        val durationUs = try {
            extractor.selectTrack(audioTrackIndex)
            if (trimStartMs > 0L) {
                extractor.seekTo(trimStartMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
        } catch (error: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException(
                "Gagal menyiapkan track audio: ${error.localizedMessage}"
            )
        }
        // 2. Decode audio to raw PCM 16-bit
        val inputMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
        val decoder = try {
            MediaCodec.createDecoderByType(inputMime)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException("Decoder untuk $inputMime tidak ditemukan pada perangkat.")
        }

        val opusCodecName = findOpusEncoder()
            ?: run {
                extractor.release()
                decoder.release()
                throw UnsupportedAudioFormatException(
                    "Perangkat ini tidak memiliki encoder Opus. Konversi tidak dapat dilakukan."
                )
            }
        val cacheFile = File(context.cacheDir, "voice_note_${System.currentTimeMillis()}.ogg")
        var oggWriter: OggOpusWriter? = null
        var opusEncoder: StreamingOpusEncoder? = null
        var pitchShifter: StreamingPitchShifter? = null
        var decoderStarted = false
        var decoderReleased = false
        var encoderFinished = false
        var writerClosed = false
        var completed = false
        val waveformPeaks = mutableListOf<Float>()
        var encodedSampleCount = 0L
        try {
            val writer = OggOpusWriter(FileOutputStream(cacheFile))
            oggWriter = writer
            val conversionContext = currentCoroutineContext()
            val encoder = StreamingOpusEncoder(opusCodecName, writer) {
                conversionContext.ensureActive()
            }
            opusEncoder = encoder
            pitchShifter = if (pitchSemitones != 0f) {
                StreamingPitchShifter(
                    sampleRate = TARGET_SAMPLE_RATE,
                    channels = TARGET_CHANNELS,
                    semitones = pitchSemitones
                )
            } else {
                null
            }

            decoder.configure(audioFormat, null, null, 0)
            decoder.start()
            decoderStarted = true

            val bufferInfo = MediaCodec.BufferInfo()
            var isExtractorEos = false
            var isDecoderEos = false
            val timeoutUs = 10_000L
            var srcSampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmProcessor = StreamingPcmProcessor(srcChannels, srcSampleRate)

            onProgress(0.15f)

            while (!isDecoderEos) {
                currentCoroutineContext().ensureActive()
                if (!isExtractorEos) {
                    val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw ConversionFailedException(
                                "Buffer input decoder tidak tersedia."
                            )
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isExtractorEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    val presentationTimeUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                    val trimStartUs = trimStartMs * 1_000L
                    val trimEndUs = if (trimEndMs == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        trimEndMs * 1_000L
                    }
                    if (presentationTimeUs >= trimEndUs) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        isDecoderEos = true
                        break
                    }
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.nativeOrder())
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val safeChannels = srcChannels.coerceAtLeast(1)
                        val frameRange = trimFrameRange(
                            bufferStartUs = presentationTimeUs,
                            frameCount = shortBuffer.remaining() / safeChannels,
                            sampleRate = srcSampleRate,
                            trimStartUs = trimStartUs,
                            trimEndUs = trimEndUs
                        )
                        if (frameRange.isEmpty()) {
                            shortBuffer.limit(0)
                        } else {
                            shortBuffer.position(frameRange.first * safeChannels)
                            shortBuffer.limit((frameRange.last + 1) * safeChannels)
                        }
                        while (shortBuffer.hasRemaining()) {
                            currentCoroutineContext().ensureActive()
                            val maxChunkFrames = (4096 / safeChannels).coerceAtLeast(1)
                            val frameCount = minOf(
                                maxChunkFrames,
                                shortBuffer.remaining() / safeChannels
                            )
                            val count = frameCount * safeChannels
                            if (count == 0) break
                            val decodedChunk = ShortArray(count)
                            shortBuffer.get(decodedChunk)
                            val resampledChunk = pcmProcessor.process(decodedChunk)
                            val finalChunk = pitchShifter
                                ?.takeIf { resampledChunk.isNotEmpty() }
                                ?.process(resampledChunk)
                                ?: resampledChunk
                            if (finalChunk.isNotEmpty()) {
                                encoder.write(finalChunk)
                                encodedSampleCount += finalChunk.size
                                waveformPeaks += finalChunk
                                    .maxOf { abs(it.toInt()) }
                                    .toFloat()
                            }
                        }
                        if (durationUs > 0L) {
                            val decodeFraction = (
                                bufferInfo.presentationTimeUs.toFloat() / durationUs
                                ).coerceIn(0f, 1f)
                            onProgress(0.15f + decodeFraction * 0.75f)
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEos = true
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    srcSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(encodedSampleCount == 0L) {
                        "Format PCM berubah setelah encoding dimulai."
                    }
                    pcmProcessor = StreamingPcmProcessor(srcChannels, srcSampleRate)
                }
            }

            pitchShifter?.let { shifter ->
                val tail = shifter.flush()
                if (tail.isNotEmpty()) {
                    encoder.write(tail)
                    encodedSampleCount += tail.size
                    waveformPeaks += tail.maxOf { abs(it.toInt()) }.toFloat()
                }
            }

            decoder.stop()
            decoderStarted = false
            decoder.release()
            decoderReleased = true
            encoder.finish()
            encoderFinished = true
            val preSkipSamples = encoder.preSkipSamples
                ?: throw ConversionFailedException(
                    "Encoder Opus tidak melaporkan delay untuk container Ogg."
                )
            writer.close(finalGranulePosition = encodedSampleCount + preSkipSamples)
            writerClosed = true

            if (encodedSampleCount == 0L) {
                throw ConversionFailedException("Gagal mengekstrak audio: stream audio kosong.")
            }

            val waveformBars = normalizeChunkPeaks(waveformPeaks, targetBars = 100)
            onProgress(1.0f)
            completed = true

            AudioConversionResult(
                outputFile = cacheFile,
                durationSeconds = max(
                    1,
                    ((encodedSampleCount + TARGET_SAMPLE_RATE - 1) / TARGET_SAMPLE_RATE).toInt()
                ),
                waveform = waveformBars,
                bitrateKbps = TARGET_BITRATE / 1000,
                sampleRate = TARGET_SAMPLE_RATE,
                channels = TARGET_CHANNELS,
                originalFileName = fileName,
                originalSize = originalSize
            )
        } finally {
            if (!decoderReleased) {
                if (decoderStarted) runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
            runCatching { extractor.release() }
            if (!encoderFinished) opusEncoder?.release()
            if (!writerClosed) {
                runCatching { oggWriter?.close(finalGranulePosition = encodedSampleCount) }
            }
            pitchShifter?.close()
            if (!completed) cacheFile.delete()
        }
    }

    private fun findOpusEncoder(): String? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)) {
                    return info.name
                }
            }
        }
        return null
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "media_file"
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx) ?: name
                    }
                }
            } catch (_: Exception) {}
        } else if (uri.scheme == "file") {
            uri.lastPathSegment?.let { name = it }
        }
        return name
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) size = cursor.getLong(idx)
                }
            }
        } catch (_: Exception) {}
        return size
    }
}
