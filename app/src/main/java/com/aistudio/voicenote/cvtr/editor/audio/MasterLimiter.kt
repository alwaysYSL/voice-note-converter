package com.aistudio.voicenote.cvtr.editor.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stateful peak limiter for normalized mono audio. It keeps only the current gain state, so
 * each call can process a small render chunk without retaining the session's PCM.
 */
internal class MasterLimiter(
    private val ceiling: Float = 0.891f,
    private val releasePerFrame: Float = 0.001f,
) {
    init {
        require(ceiling in 0f..1f) { "ceiling must be between 0 and 1" }
        require(releasePerFrame in 0f..1f) { "releasePerFrame must be between 0 and 1" }
    }

    private var gain = 1f

    /** Exposed only for deterministic state-boundary tests. */
    internal val gainForTest: Float
        get() = gain

    fun process(input: FloatArray): ShortArray {
        val output = ShortArray(input.size)
        for (index in input.indices) {
            val sample = input[index].takeIf { it.isFinite() } ?: 0f
            var lookAheadPeak = abs(sample)
            val lookAheadEnd = minOf(input.lastIndex, index + LOOK_AHEAD_FRAMES)
            for (lookAheadIndex in (index + 1)..lookAheadEnd) {
                lookAheadPeak = maxOf(
                    lookAheadPeak,
                    abs(input[lookAheadIndex].takeIf { it.isFinite() } ?: 0f),
                )
            }
            val requiredGain = if (lookAheadPeak > ceiling) ceiling / lookAheadPeak else 1f
            gain = if (requiredGain < gain) {
                requiredGain
            } else {
                (gain + releasePerFrame).coerceAtMost(1f)
            }
            val limited = (sample * gain).coerceIn(-ceiling, ceiling)
            output[index] = (limited * PCM16_MAX).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return output
    }

    private companion object {
        const val PCM16_MAX = 32_767f
        const val LOOK_AHEAD_FRAMES = 32
    }
}
