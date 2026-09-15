package com.aistudio.voicenote.cvtr.audio

import kotlin.math.roundToInt

/**
 * Streaming wrapper around Signalsmith Stretch for mono PCM voice-note audio.
 */
internal class StreamingPitchShifter(
    sampleRate: Int = 48_000,
    channels: Int = 1,
    semitones: Float = 0f,
    tonalityLimit: Float = 8000f / sampleRate.toFloat(),
    tempoRatio: Float = 1f,
) : AutoCloseable {
    private var handle: Long = 0L
    private val configuredTempoRatio = tempoRatio

    init {
        require(tempoRatio.isFinite() && tempoRatio > 0f) { "tempoRatio must be positive and finite" }
        handle = PitchShifterJni.create(sampleRate, channels)
        require(handle != 0L) { "Failed to create native pitch shifter instance" }
        try {
            PitchShifterJni.setTranspose(handle, semitones, tonalityLimit)
        } catch (error: Throwable) {
            runCatching { PitchShifterJni.destroy(handle) }
            handle = 0L
            throw error
        }
    }

    fun process(samples: ShortArray): ShortArray {
        val outputFrames = (samples.size / configuredTempoRatio).roundToInt().coerceAtLeast(0)
        return process(samples, outputFrames)
    }

    /** Processes [samples] into an independently requested number of output frames. */
    fun process(samples: ShortArray, outputFrames: Int): ShortArray {
        check(handle != 0L) { "PitchShifter has been destroyed" }
        require(outputFrames >= 0) { "outputFrames must be non-negative" }
        if (samples.isEmpty() || outputFrames == 0) return ShortArray(0)
        return if (outputFrames == samples.size) {
            PitchShifterJni.process(handle, samples, samples.size)
        } else {
            PitchShifterJni.processWithOutputFrames(handle, samples, samples.size, outputFrames)
        }
    }

    fun flush(): ShortArray {
        check(handle != 0L) { "PitchShifter has been destroyed" }
        return PitchShifterJni.flush(handle)
    }

    override fun close() {
        if (handle != 0L) {
            PitchShifterJni.destroy(handle)
            handle = 0L
        }
    }
}
