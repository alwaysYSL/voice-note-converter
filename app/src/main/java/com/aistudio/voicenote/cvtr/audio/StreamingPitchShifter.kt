package com.aistudio.voicenote.cvtr.audio

import kotlin.math.roundToInt

internal interface PitchShifterEngine : AutoCloseable {
    fun setTranspose(semitones: Float, tonalityLimit: Float)

    fun process(input: ShortArray, inputFrames: Int): ShortArray

    fun processWithOutputFrames(
        input: ShortArray,
        inputFrames: Int,
        outputFrames: Int,
    ): ShortArray

    fun flush(): ShortArray
}

internal fun interface PitchShifterEngineFactory {
    fun create(sampleRate: Int, channels: Int): PitchShifterEngine
}

private class NativePitchShifterEngine(
    sampleRate: Int,
    channels: Int,
) : PitchShifterEngine {
    private var handle = PitchShifterJni.create(sampleRate, channels)

    init {
        require(handle != 0L) { "Failed to create native pitch shifter instance" }
    }

    override fun setTranspose(semitones: Float, tonalityLimit: Float) {
        PitchShifterJni.setTranspose(handle, semitones, tonalityLimit)
    }

    override fun process(input: ShortArray, inputFrames: Int): ShortArray =
        PitchShifterJni.process(handle, input, inputFrames)

    override fun processWithOutputFrames(
        input: ShortArray,
        inputFrames: Int,
        outputFrames: Int,
    ): ShortArray = PitchShifterJni.processWithOutputFrames(handle, input, inputFrames, outputFrames)

    override fun flush(): ShortArray = PitchShifterJni.flush(handle)

    override fun close() {
        if (handle != 0L) {
            runCatching { PitchShifterJni.destroy(handle) }
            handle = 0L
        }
    }
}

/**
 * Streaming wrapper around Signalsmith Stretch for interleaved PCM voice-note audio.
 */
internal class StreamingPitchShifter(
    sampleRate: Int = 48_000,
    channels: Int = 1,
    semitones: Float = 0f,
    tonalityLimit: Float = 8000f / sampleRate.toFloat(),
    tempoRatio: Float = 1f,
    engineFactory: PitchShifterEngineFactory = PitchShifterEngineFactory { requestedSampleRate, requestedChannels ->
        NativePitchShifterEngine(requestedSampleRate, requestedChannels)
    },
) : AutoCloseable {
    private val channelCount = channels.also {
        require(it > 0) { "channels must be positive" }
    }
    private val configuredTempoRatio = tempoRatio
    private var engine: PitchShifterEngine? = null

    init {
        require(tempoRatio.isFinite() && tempoRatio > 0f) {
            "tempoRatio must be positive and finite"
        }
        val created = engineFactory.create(sampleRate, channelCount)
        engine = created
        try {
            created.setTranspose(semitones, tonalityLimit)
        } catch (error: Throwable) {
            runCatching { created.close() }
            engine = null
            throw error
        }
    }

    fun process(samples: ShortArray): ShortArray {
        require(samples.size % channelCount == 0) {
            "samples must contain complete interleaved frames"
        }
        val inputFrames = samples.size / channelCount
        val outputFrames = (inputFrames / configuredTempoRatio).roundToInt().coerceAtLeast(0)
        return process(samples, outputFrames)
    }

    /** Processes [samples] into an independently requested number of output frames. */
    fun process(samples: ShortArray, outputFrames: Int): ShortArray {
        val activeEngine = checkNotNull(engine) { "PitchShifter has been destroyed" }
        require(samples.size % channelCount == 0) {
            "samples must contain complete interleaved frames"
        }
        require(outputFrames >= 0) { "outputFrames must be non-negative" }
        val inputFrames = samples.size / channelCount
        if (inputFrames == 0 || outputFrames == 0) return ShortArray(0)
        return if (outputFrames == inputFrames) {
            activeEngine.process(samples, inputFrames)
        } else {
            activeEngine.processWithOutputFrames(samples, inputFrames, outputFrames)
        }
    }

    fun flush(): ShortArray = checkNotNull(engine) { "PitchShifter has been destroyed" }.flush()

    override fun close() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }
}
