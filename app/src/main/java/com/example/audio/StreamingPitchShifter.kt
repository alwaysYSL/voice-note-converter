package com.example.audio

/**
 * Streaming wrapper around Signalsmith Stretch for mono PCM voice-note audio.
 */
internal class StreamingPitchShifter(
    sampleRate: Int = 48_000,
    channels: Int = 1,
    semitones: Float,
    tonalityLimit: Float = 8000f / sampleRate.toFloat()
) : AutoCloseable {
    private var handle: Long = PitchShifterJni.create(sampleRate, channels)

    init {
        require(handle != 0L) { "Failed to create native pitch shifter instance" }
        PitchShifterJni.setTranspose(handle, semitones, tonalityLimit)
    }

    fun process(samples: ShortArray): ShortArray {
        check(handle != 0L) { "PitchShifter has been destroyed" }
        if (samples.isEmpty()) return ShortArray(0)
        return PitchShifterJni.process(handle, samples, samples.size)
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
