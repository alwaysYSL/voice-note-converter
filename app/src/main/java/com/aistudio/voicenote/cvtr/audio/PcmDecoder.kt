package com.aistudio.voicenote.cvtr.audio

import android.media.AudioFormat
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class UnsupportedPcmEncodingException(encoding: Int) :
    AudioConversionException("Encoding PCM $encoding belum didukung.")

internal fun pcmEncodingFrom(format: MediaFormat): Int =
    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }

internal object PcmDecoder {
    fun decode(
        outputBuffer: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
        channels: Int
    ): ShortArray {
        val safeChannels = channels.coerceAtLeast(1)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw UnsupportedPcmEncodingException(encoding)
        }
        val frameBytes = bytesPerSample * safeChannels
        require(size >= 0 && size % frameBytes == 0) {
            "Buffer PCM tidak berisi frame lengkap."
        }
        val frameCount = size / frameBytes
        val decoded = ShortArray(frameCount * safeChannels)
        val buffer = outputBuffer.duplicate()
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                position(offset)
                limit(offset + size)
            }
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = buffer.asShortBuffer()
                shorts.get(decoded)
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                for (index in decoded.indices) {
                    val sample = buffer.float
                    val safeSample = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                    decoded[index] = (safeSample * 32_767f)
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }
        }
        return decoded
    }
}
