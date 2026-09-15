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

    init {
        handle = try {
            RnNoiseJni.create()
        } catch (e: UnsatisfiedLinkError) {
            0L
        }
    }

    fun process(input: ShortArray, strength: CleanupStrength): ShortArray {
        if (strength == CleanupStrength.OFF) return input.clone()
        if (handle == 0L) throw IllegalStateException("Native library not loaded")

        val output = ShortArray(input.size)
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
        
        if (bufferPos > 0) {
            // zero pad the rest
            for (i in bufferPos until FRAME_SIZE) {
                buffer[i] = 0
            }
            processAndBlendFrame(strength, output, outputPos, bufferPos)
            bufferPos = 0
        }

        return output
    }

    private fun processAndBlendFrame(strength: CleanupStrength, output: ShortArray, outputPos: Int, validSamples: Int) {
        val processed = RnNoiseJni.processFrame(handle, buffer)
        val wetMix = strength.wetMix
        val dryMix = 1f - wetMix
        for (i in 0 until validSamples) {
            val dry = buffer[i].toFloat()
            val wet = processed[i].toFloat()
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
    external fun processFrame(handle: Long, frame: ShortArray): ShortArray
    external fun destroy(handle: Long)
}
