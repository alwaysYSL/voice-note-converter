package com.aistudio.voicenote.cvtr.editor.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class DecodedPcmInfo(
    val pcmFile: File,
    val durationMs: Long,
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val waveformPoints: List<Float>
)

object EditorPcmDecoder {

    const val TARGET_SAMPLE_RATE = 48000
    const val TARGET_CHANNELS = 1

    /**
     * Decodes audio from Uri to standard 48kHz 16-bit Mono PCM file.
     */
    fun decodeToPcm(context: Context, uri: Uri, outputFile: File): DecodedPcmInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor for: ")
            pfd.use {
                extractor.setDataSource(it.fileDescriptor)
            }
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found in: ")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val inputSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 48000
        val inputChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1

        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val outStream = BufferedOutputStream(FileOutputStream(outputFile))
        var totalDecodedSamples = 0L

        try {
            var isInputEOS = false
            var isOutputEOS = false
            val timeoutUs = 10000L

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val rawSamples = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(rawSamples)

                        // 1. Convert to Mono if needed
                        val monoSamples = if (inputChannels > 1) {
                            convertStereoToMono(rawSamples)
                        } else {
                            rawSamples
                        }

                        // 2. Resample to 48kHz if needed
                        val targetSamples = if (inputSampleRate != TARGET_SAMPLE_RATE) {
                            resampleLinear(monoSamples, inputSampleRate, TARGET_SAMPLE_RATE)
                        } else {
                            monoSamples
                        }

                        // Write to output file (16-bit little endian)
                        val byteBuf = ByteBuffer.allocate(targetSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (s in targetSamples) {
                            byteBuf.putShort(s)
                        }
                        outStream.write(byteBuf.array())
                        totalDecodedSamples += targetSamples.size
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }
        } finally {
            outStream.flush()
            outStream.close()
            codec.stop()
            codec.release()
            extractor.release()
        }

        val calculatedDurationMs = if (durationUs > 0) {
            durationUs / 1000L
        } else {
            (totalDecodedSamples * 1000L) / TARGET_SAMPLE_RATE
        }

        val waveform = extractWaveformFromPcm(outputFile, 100)

        return DecodedPcmInfo(
            pcmFile = outputFile,
            durationMs = calculatedDurationMs,
            sampleRate = TARGET_SAMPLE_RATE,
            channelCount = TARGET_CHANNELS,
            waveformPoints = waveform
        )
    }

    /**
     * Converts stereo ShortArray (interleaved L, R) to mono by averaging.
     */
    fun convertStereoToMono(stereoSamples: ShortArray): ShortArray {
        val monoSize = stereoSamples.size / 2
        val mono = ShortArray(monoSize)
        for (i in 0 until monoSize) {
            val left = stereoSamples[i * 2].toInt()
            val right = stereoSamples[i * 2 + 1].toInt()
            mono[i] = ((left + right) / 2).toShort()
        }
        return mono
    }

    /**
     * Simple linear interpolation resampler.
     */
    fun resampleLinear(inputSamples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || inputSamples.isEmpty()) return inputSamples
        val outLength = ((inputSamples.size.toLong() * toRate) / fromRate).toInt()
        val output = ShortArray(outLength)
        val ratio = fromRate.toDouble() / toRate.toDouble()

        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = (srcPos - srcIndex).toFloat()

            val s1 = inputSamples[srcIndex.coerceIn(0, inputSamples.size - 1)].toFloat()
            val s2 = inputSamples[(srcIndex + 1).coerceIn(0, inputSamples.size - 1)].toFloat()

            val interpolated = s1 + frac * (s2 - s1)
            output[i] = interpolated.toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    /**
     * Extracts normalized RMS amplitude points (0.0f..1.0f) for waveform visualization.
     */
    fun extractWaveformFromPcm(pcmFile: File, bucketCount: Int = 100): List<Float> {
        if (!pcmFile.exists() || pcmFile.length() < 2) {
            return List(bucketCount) { 0.1f }
        }

        val totalBytes = pcmFile.length()
        val totalSamples = (totalBytes / 2).toInt()
        if (totalSamples == 0) return List(bucketCount) { 0.1f }

        val samplesPerBucket = max(1, totalSamples / bucketCount)
        val amplitudes = ArrayList<Float>(bucketCount)
        val raf = RandomAccessFile(pcmFile, "r")
        val buffer = ByteArray(4096)
        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)

        try {
            var maxRms = 1.0f
            val rawRmsList = FloatArray(bucketCount)

            for (bucket in 0 until bucketCount) {
                val startSample = bucket.toLong() * samplesPerBucket
                val endSample = if (bucket == bucketCount - 1) totalSamples.toLong() else (bucket + 1).toLong() * samplesPerBucket
                val count = (endSample - startSample).toInt().coerceAtLeast(1)

                raf.seek(startSample * 2)
                var sumSquares = 0.0
                var readSamples = 0

                while (readSamples < count) {
                    val toRead = minOf(buffer.size, (count - readSamples) * 2)
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break

                    byteBuffer.position(0)
                    val shortCount = read / 2
                    for (i in 0 until shortCount) {
                        val sample = byteBuffer.short.toDouble()
                        sumSquares += sample * sample
                    }
                    readSamples += shortCount
                }

                val rms = if (readSamples > 0) sqrt(sumSquares / readSamples).toFloat() else 0f
                rawRmsList[bucket] = rms
                if (rms > maxRms) maxRms = rms
            }

            for (rms in rawRmsList) {
                val normalized = (rms / maxRms).coerceIn(0.05f, 1.0f)
                amplitudes.add(normalized)
            }
        } finally {
            raf.close()
        }

        return amplitudes
    }
}