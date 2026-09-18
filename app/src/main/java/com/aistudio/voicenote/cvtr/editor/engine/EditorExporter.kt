package com.aistudio.voicenote.cvtr.editor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import com.aistudio.voicenote.cvtr.audio.OggOpusWriter
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformAccumulator
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.audio.opusPacketDurationSamples
import com.aistudio.voicenote.cvtr.audio.opusPreSkipSamples
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.local.DeliveryStatus
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.editor.model.EditorTimelineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

object EditorExporter {

    private const val SAMPLE_RATE = 48000
    private const val BIT_RATE = 32000
    private const val FRAME_MS = 20L
    private const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_MS / 1000L).toInt() // 960

    suspend fun exportTimeline(
        context: Context,
        state: EditorTimelineState,
        historyRepository: ConversionHistoryRepository,
        onProgress: (Float) -> Unit
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val activeTracks = state.tracks.filterNotNull().filter { it.activeDurationMs > 0 }
            if (activeTracks.isEmpty() || state.totalDurationMs <= 0) {
                throw IllegalStateException("Timeline tidak memiliki audio aktif untuk di-export.")
            }

            val totalDurationMs = state.totalDurationMs
            val totalSamples = (totalDurationMs * SAMPLE_RATE / 1000L)
            val waveformAccumulator = WaveformAccumulator(100, totalSamples)

            val tempOggFile = File(context.cacheDir, "editor_export_${System.currentTimeMillis()}.ogg")
            val fos = BufferedOutputStream(FileOutputStream(tempOggFile))
            val oggWriter = OggOpusWriter(fos)

            // Find Opus Encoder
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_COMPLEXITY, 5)
            }
            val codecName = codecList.findEncoderForFormat(encoderFormat)
                ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).name

            val encoder = MediaCodec.createByCodecName(codecName)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var preSkipSamples: Int? = null
            var currentPositionMs = 0L
            var submittedSamples = 0L
            var isEosQueued = false
            var isOutputEos = false
            val timeoutUs = 10000L

            try {
                while (!isOutputEos) {
                    // 1. Feed mixed PCM to encoder
                    if (!isEosQueued) {
                        val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inBuffer = encoder.getInputBuffer(inputIndex)
                            if (inBuffer != null) {
                                inBuffer.clear()
                                inBuffer.order(ByteOrder.nativeOrder())

                                if (currentPositionMs < totalDurationMs) {
                                    val mixedFrame = EditorAudioEngine.mixTimelineFrame(activeTracks, currentPositionMs, FRAME_MS)
                                    waveformAccumulator.add(mixedFrame)

                                    val count = minOf(mixedFrame.size, inBuffer.remaining() / 2)
                                    for (i in 0 until count) {
                                        inBuffer.putShort(mixedFrame[i])
                                    }

                                    val presentationTimeUs = submittedSamples * 1000000L / SAMPLE_RATE
                                    encoder.queueInputBuffer(inputIndex, 0, count * 2, presentationTimeUs, 0)
                                    submittedSamples += count
                                    currentPositionMs += FRAME_MS

                                    val progress = (currentPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 0.9f)
                                    onProgress(progress)
                                } else {
                                    // End of timeline: send EOS
                                    val presentationTimeUs = submittedSamples * 1000000L / SAMPLE_RATE
                                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isEosQueued = true
                                }
                            }
                        }
                    }

                    // 2. Drain encoded Opus packets
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when (outIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (preSkipSamples == null) {
                                val delay = opusPreSkipSamples(encoder.outputFormat)
                                preSkipSamples = delay
                                oggWriter.writeHeader(
                                    sampleRate = SAMPLE_RATE,
                                    channels = 1,
                                    preSkipSamples = delay
                                )
                            }
                        }
                        else -> if (outIndex >= 0) {
                            val outBuffer = encoder.getOutputBuffer(outIndex)
                            if (outBuffer != null && bufferInfo.size > 0 &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                if (preSkipSamples == null) {
                                    preSkipSamples = 312
                                    oggWriter.writeHeader(sampleRate = SAMPLE_RATE, channels = 1, preSkipSamples = 312)
                                }
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val packet = ByteArray(bufferInfo.size)
                                outBuffer.get(packet)

                                val packetDuration = opusPacketDurationSamples(packet)
                                oggWriter.writeAudioPacket(packet, samplesInPacket = if (packetDuration > 0) packetDuration else 960L)
                            }
                            isOutputEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } finally {
                encoder.stop()
                encoder.release()
                fos.flush()
                fos.close()
            }

            onProgress(0.95f)

            // Save to MediaStore
            val fileName = VoiceNoteStorage.generateFileName()
            val publicUri = VoiceNoteStorage.saveToPublicStorage(context, tempOggFile, fileName)

            // Record to Room Database
            val waveformData = WaveformCodec.encode(waveformAccumulator.result())
            val historyRecord = ConversionHistory(
                originalFileName = "Editor_Mix_${activeTracks.size}_tracks",
                outputFileName = fileName,
                outputFilePath = publicUri.toString(),
                durationSeconds = (totalDurationMs / 1000L).toInt().coerceAtLeast(1),
                fileSizeBytes = tempOggFile.length(),
                waveform = waveformData,
                bitrateKbps = BIT_RATE / 1000,
                createdAt = System.currentTimeMillis(),
                deliveryStatus = DeliveryStatus.READY
            )
            historyRepository.insert(historyRecord)

            tempOggFile.delete()
            onProgress(1.0f)
            publicUri
        }
    }
}