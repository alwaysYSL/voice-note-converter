package com.aistudio.voicenote.cvtr.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
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
import java.util.UUID
import java.nio.ByteOrder
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
    val originalSize: Long,
    val encoderBackend: String = "unknown"
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

internal fun trimProgressFraction(
    presentationTimeUs: Long,
    trimStartUs: Long,
    trimEndUs: Long,
    durationUs: Long
): Float {
    val effectiveStartUs = trimStartUs.coerceAtLeast(0L)
    val effectiveEndUs = if (trimEndUs == Long.MAX_VALUE) {
        durationUs.coerceAtLeast(effectiveStartUs + 1L)
    } else {
        minOf(durationUs.coerceAtLeast(effectiveStartUs + 1L), trimEndUs)
    }
    return ((presentationTimeUs - effectiveStartUs).toDouble() /
        (effectiveEndUs - effectiveStartUs).coerceAtLeast(1L))
        .toFloat()
        .coerceIn(0f, 1f)
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

private class HardwareOpusEncoder(
    codecName: String,
    private val oggWriter: OggOpusWriter,
    private val checkActive: () -> Unit
) : StreamingAudioEncoder {
    private val encoder = MediaCodec.createByCodecName(codecName)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var submittedSamples = 0L
    private var outputEnded = false
    private var started = false
    private var released = false
    override var preSkipSamples: Int? = null
        private set
    override val backendName: String = codecName

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

    override fun write(samples: ShortArray) {
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

    override fun finish() {
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

    override fun release() {
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

    private fun expectedWaveformSamples(durationUs: Long, sampleRate: Int): Long {
        if (durationUs <= 0L || sampleRate <= 0) return 0L
        return (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1L)
    }

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

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = pcmEncodingFrom(format)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val accumulator = WaveformAccumulator(
                targetBars = 100,
                expectedSamples = expectedWaveformSamples(durationUs, sampleRate)
            )

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
                            val decoded = PcmDecoder.decode(
                                outputBuffer = outputBuffer,
                                offset = bufferInfo.offset,
                                size = bufferInfo.size,
                                encoding = pcmEncoding,
                                channels = channels
                            )
                            val monoChunk = processPcmChunk(decoded, channels, sampleRate)
                            accumulator.add(monoChunk)
                        }
                    }
                    outputEnded = bufferInfo.flags and
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = decoder.outputFormat
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = pcmEncodingFrom(outputFormat)
                    accumulator.setExpectedSamples(expectedWaveformSamples(durationUs, sampleRate))
                }
            }
            accumulator.result()
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
        processingOptions: AudioProcessingOptions = AudioProcessingOptions(),
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
        } catch (error: Exception) {
            extractor.release()
            throw ConversionPipelineException(
                ConversionErrorCode.INPUT_UNREADABLE,
                ConversionStage.OPEN_EXTRACTOR,
                "File media tidak dapat dibaca. Pilih ulang atau gunakan format lain.",
                error
            )
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
            throw ConversionPipelineException(
                ConversionErrorCode.INPUT_UNREADABLE,
                ConversionStage.OPEN_EXTRACTOR,
                "Track audio tidak dapat dibaca. Gunakan file audio lain.",
                error
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
            throw ConversionPipelineException(
                ConversionErrorCode.INPUT_UNREADABLE,
                ConversionStage.OPEN_EXTRACTOR,
                "Track audio tidak dapat disiapkan. Gunakan file audio lain.",
                error
            )
        }

        val trimStartUs = trimStartMs * 1_000L
        val trimEndUs = if (trimEndMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            trimEndMs * 1_000L
        }
        val effectiveEndUs = when {
            trimEndUs == Long.MAX_VALUE && durationUs > trimStartUs -> durationUs
            trimEndUs == Long.MAX_VALUE -> Long.MAX_VALUE
            durationUs > 0L -> minOf(durationUs, trimEndUs)
            else -> trimEndUs
        }
        val expectedOutputSamples = if (
            effectiveEndUs != Long.MAX_VALUE && effectiveEndUs > trimStartUs
        ) {
            ((effectiveEndUs - trimStartUs) * TARGET_SAMPLE_RATE / 1_000_000L)
                .coerceAtLeast(1L)
        } else {
            0L
        }
        // 2. Decode audio to normalized PCM 16-bit samples.
        val inputMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
        val decoder = try {
            MediaCodec.createDecoderByType(inputMime)
        } catch (error: Exception) {
            extractor.release()
            throw ConversionPipelineException(
                ConversionErrorCode.DECODER_UNAVAILABLE,
                ConversionStage.CREATE_DECODER,
                "Decoder untuk $inputMime tidak tersedia. Gunakan format audio lain.",
                error
            )
        }

        val cacheFile = File(
            context.cacheDir,
            "voice_note_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.ogg"
        )
        var oggWriter: OggOpusWriter? = null
        var opusEncoder: StreamingAudioEncoder? = null
        var pitchShifter: StreamingPitchShifter? = null
        var decoderStarted = false
        var decoderReleased = false
        var encoderFinished = false
        var writerClosed = false
        var completed = false
        val waveformAccumulator = WaveformAccumulator(
            targetBars = 100,
            expectedSamples = expectedOutputSamples
        )
        var encodedSampleCount = 0L
        val boundaryFader = if (
            trimStartMs > 0L ||
            trimEndMs != Long.MAX_VALUE ||
            processingOptions.trimSilence
        ) {
            PcmBoundaryFader()
        } else {
            null
        }
        val audioEffects = StreamingAudioEffects(processingOptions)
        try {
            val writer = OggOpusWriter(FileOutputStream(cacheFile))
            oggWriter = writer
            val conversionContext = currentCoroutineContext()
            val encoder = createOpusEncoder(writer) {
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

            fun writeOutputChunk(samples: ShortArray) {
                if (samples.isEmpty()) return
                try {
                    encoder.write(samples)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    throw ConversionPipelineException(
                        ConversionErrorCode.OPUS_CONFIGURE_FAILED,
                        ConversionStage.ENCODE,
                        "Encoder Opus gagal memproses audio.",
                        error
                    )
                }
                encodedSampleCount += samples.size
                waveformAccumulator.add(samples)
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
            var pcmEncoding = pcmEncodingFrom(audioFormat)
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
                    if (presentationTimeUs >= trimEndUs) {
                        decoder.releaseOutputBuffer(outputIndex, false)
                        isDecoderEos = true
                        break
                    }
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val decoded = PcmDecoder.decode(
                            outputBuffer = outputBuffer,
                            offset = bufferInfo.offset,
                            size = bufferInfo.size,
                            encoding = pcmEncoding,
                            channels = srcChannels
                        )
                        val safeChannels = srcChannels.coerceAtLeast(1)
                        val frameRange = trimFrameRange(
                            bufferStartUs = presentationTimeUs,
                            frameCount = decoded.size / safeChannels,
                            sampleRate = srcSampleRate,
                            trimStartUs = trimStartUs,
                            trimEndUs = trimEndUs
                        )
                        if (!frameRange.isEmpty()) {
                            val startSample = frameRange.first * safeChannels
                            val endSample = (frameRange.last + 1) * safeChannels
                            val trimmed = if (
                                startSample == 0 && endSample == decoded.size
                            ) {
                                decoded
                            } else {
                                decoded.copyOfRange(startSample, endSample)
                            }
                            val resampledChunk = pcmProcessor.process(trimmed)
                            val finalChunk = pitchShifter
                                ?.takeIf { resampledChunk.isNotEmpty() }
                                ?.process(resampledChunk)
                                ?: resampledChunk
                            if (finalChunk.isNotEmpty()) {
                                val effectedChunk = audioEffects.process(finalChunk)
                                val outputChunk = boundaryFader?.process(effectedChunk) ?: effectedChunk
                                writeOutputChunk(outputChunk)
                            }
                        }
                        if (durationUs > 0L || trimEndUs != Long.MAX_VALUE) {
                            val decodeFraction = trimProgressFraction(
                                presentationTimeUs = presentationTimeUs,
                                trimStartUs = trimStartUs,
                                trimEndUs = trimEndUs,
                                durationUs = durationUs
                            )
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
                    pcmEncoding = pcmEncodingFrom(newFormat)
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
                    val effectedTail = audioEffects.process(tail)
                    val outputChunk = boundaryFader?.process(effectedTail) ?: effectedTail
                    writeOutputChunk(outputChunk)
                }
            }

            val effectTail = audioEffects.finish()
            if (effectTail.isNotEmpty()) {
                val outputChunk = boundaryFader?.process(effectTail) ?: effectTail
                writeOutputChunk(outputChunk)
            }
            boundaryFader?.finish()?.let(::writeOutputChunk)

            decoder.stop()
            decoderStarted = false
            decoder.release()
            decoderReleased = true
            try {
                encoder.finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                throw ConversionPipelineException(
                    ConversionErrorCode.OPUS_CONFIGURE_FAILED,
                    ConversionStage.ENCODE,
                    "Encoder Opus gagal menyelesaikan output.",
                    error
                )
            }
            encoderFinished = true
            val preSkipSamples = encoder.preSkipSamples
                ?: throw ConversionFailedException(
                    "Encoder Opus tidak melaporkan delay untuk container Ogg."
                )
            try {
                writer.close(finalGranulePosition = encodedSampleCount + preSkipSamples)
            } catch (error: Throwable) {
                throw ConversionPipelineException(
                    ConversionErrorCode.OUTPUT_INVALID,
                    ConversionStage.WRITE_OGG,
                    "Container OGG gagal diselesaikan.",
                    error
                )
            }
            writerClosed = true

            if (encodedSampleCount == 0L) {
                throw ConversionFailedException("Gagal mengekstrak audio: stream audio kosong.")
            }

            val waveformBars = waveformAccumulator.result()
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
                originalSize = originalSize,
                encoderBackend = encoder.backendName
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

    private fun createOpusEncoder(
        writer: OggOpusWriter,
        checkActive: () -> Unit
    ): StreamingAudioEncoder {
        var softwareError: Throwable? = null
        when (val availability = OpusEncoderProbe.probeSoftware()) {
            is EncoderAvailability.Available -> {
                try {
                    return SoftwareOpusEncoder(writer, checkActive).also { encoder ->
                        Log.i(
                            TAG,
                            "Opus backend=${encoder.backendName}, api=${Build.VERSION.SDK_INT}, " +
                                "abi=${Build.SUPPORTED_ABIS.joinToString()}"
                        )
                    }
                } catch (error: Throwable) {
                    softwareError = error
                }
            }
            EncoderAvailability.Missing -> {
                softwareError = UnsatisfiedLinkError("Bundled libopus tidak dapat dimuat")
            }
            is EncoderAvailability.Broken -> {
                softwareError = availability.cause
            }
        }

        val hardwareCodec = findOpusEncoder()
            ?: throw ConversionPipelineException(
                errorCode = ConversionErrorCode.OPUS_ENCODER_UNAVAILABLE,
                stage = ConversionStage.CREATE_ENCODER,
                safeMessage = "Encoder Opus software gagal dimuat dan perangkat tidak menyediakan fallback.",
                cause = softwareError
            )
        return try {
            HardwareOpusEncoder(hardwareCodec, writer, checkActive).also { encoder ->
                Log.w(
                    TAG,
                    "Software Opus unavailable; fallback=${encoder.backendName}, " +
                        "api=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()}",
                    softwareError
                )
            }
        } catch (hardwareFailure: Throwable) {
            softwareError?.let(hardwareFailure::addSuppressed)
            throw ConversionPipelineException(
                errorCode = ConversionErrorCode.OPUS_CONFIGURE_FAILED,
                stage = ConversionStage.CREATE_ENCODER,
                safeMessage = "Semua backend encoder Opus gagal dimulai.",
                cause = hardwareFailure
            )
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
