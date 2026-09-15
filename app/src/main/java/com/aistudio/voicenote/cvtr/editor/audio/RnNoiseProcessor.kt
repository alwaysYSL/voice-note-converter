package com.aistudio.voicenote.cvtr.editor.audio

import java.io.Closeable
import kotlin.math.roundToInt

internal enum class CleanupStrength(val wetMix: Float) {
    OFF(0f), LIGHT(0.35f), MEDIUM(0.65f), STRONG(1f)
}

internal class RnNoiseProcessor : Closeable {
    private var handle: Long = 0
    private var buffer = ShortArray(FRAME_SIZE)
    private var bufferPos = 0

    private val processedBuffer = ShortArray(FRAME_SIZE)

    init {
        handle = try {
            RnNoiseJni.create()
        } catch (e: UnsatisfiedLinkError) {
            0L
        }
    }

    val isReady: Boolean get() = handle != 0L

    fun processAll(input: ShortArray, strength: CleanupStrength): ShortArray {
        if (strength == CleanupStrength.OFF) return input.clone()
        val p = process(input, strength)
        val f = flush(strength)
        val result = ShortArray(p.size + f.size)
        System.arraycopy(p, 0, result, 0, p.size)
        System.arraycopy(f, 0, result, p.size, f.size)
        return result
    }

    fun process(input: ShortArray, strength: CleanupStrength): ShortArray {
        if (strength == CleanupStrength.OFF) return input.clone()
        if (handle == 0L) throw IllegalStateException("Native library not loaded")

        // First calculate how many full frames we will produce
        val totalAvailable = bufferPos + input.size
        val outputFrames = totalAvailable / FRAME_SIZE
        val output = ShortArray(outputFrames * FRAME_SIZE)
        
        var inputPos = 0
        var outputPos = 0

        while (inputPos < input.size) {
            val toCopy = minOf(FRAME_SIZE - bufferPos, input.size - inputPos)
            System.arraycopy(input, inputPos, buffer, bufferPos, toCopy)
            inputPos += toCopy
            bufferPos += toCopy

            if (bufferPos == FRAME_SIZE) {
                processAndBlendFrame(strength, output, outputPos, FRAME_SIZE)
                outputPos += FRAME_SIZE
                bufferPos = 0
            }
        }
        
        return output
    }

    fun flush(strength: CleanupStrength = CleanupStrength.MEDIUM): ShortArray {
        if (bufferPos == 0) return ShortArray(0)
        if (strength == CleanupStrength.OFF) {
            val output = ShortArray(bufferPos)
            System.arraycopy(buffer, 0, output, 0, bufferPos)
            bufferPos = 0
            return output
        }
        if (handle == 0L) throw IllegalStateException("Native library not loaded")

        val validSamples = bufferPos
        val output = ShortArray(validSamples)
        
        // zero pad the rest
        for (i in bufferPos until FRAME_SIZE) {
            buffer[i] = 0
        }
        
        processAndBlendFrame(strength, output, 0, validSamples)
        bufferPos = 0
        
        return output
    }

    private fun processAndBlendFrame(strength: CleanupStrength, output: ShortArray, outputPos: Int, validSamples: Int) {
        RnNoiseJni.processFrame(handle, buffer, processedBuffer)
        val wetMix = strength.wetMix
        val dryMix = 1f - wetMix
        for (i in 0 until validSamples) {
            val dry = buffer[i].toFloat()
            val wet = processedBuffer[i].toFloat()
            val mixed = dry * dryMix + wet * wetMix
            val outSample = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[outputPos + i] = outSample.toShort()
        }
    }

    override fun close() {
        if (handle != 0L) {
            RnNoiseJni.destroy(handle)
            handle = 0L
        }
    }

    companion object {
        const val FRAME_SIZE = 480
    }
}

internal object RnNoiseJni {
    init {
        try {
            System.loadLibrary("pitchshifter")
        } catch (e: UnsatisfiedLinkError) {
            // Ignored for JVM tests
        }
    }

    external fun create(): Long
    external fun processFrame(handle: Long, frame: ShortArray, outFrame: ShortArray)
    external fun destroy(handle: Long)
}
