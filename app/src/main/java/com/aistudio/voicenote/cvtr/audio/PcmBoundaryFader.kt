package com.aistudio.voicenote.cvtr.audio

import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Holds the tail so a short fade-out can be applied once the trim window ends. */
internal class PcmBoundaryFader(
    private val fadeSamples: Int = 240
) {
    private val pending = ArrayDeque<Short>()
    private var totalSamples = 0L
    private var emittedSamples = 0L

    fun process(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        samples.forEach(pending::addLast)
        totalSamples += samples.size
        val output = ShortArray((pending.size - fadeSamples.coerceAtLeast(0)).coerceAtLeast(0))
        var index = 0
        while (pending.size > fadeSamples.coerceAtLeast(0)) {
            output[index++] = applyFadeIn(pending.removeFirst(), emittedSamples)
            emittedSamples++
        }
        return output
    }

    fun finish(): ShortArray {
        if (pending.isEmpty()) return ShortArray(0)
        val output = ShortArray(pending.size)
        var index = 0
        while (pending.isNotEmpty()) {
            val sample = pending.removeFirst()
            val fadedIn = applyFadeIn(sample, emittedSamples)
            val distanceFromEnd = totalSamples - emittedSamples
            val fadeOutFactor = if (fadeSamples <= 0) {
                1.0
            } else {
                (distanceFromEnd.toDouble() / fadeSamples).coerceIn(0.0, 1.0)
            }
            output[index++] = scale(fadedIn, fadeOutFactor)
            emittedSamples++
        }
        return output
    }

    private fun applyFadeIn(sample: Short, position: Long): Short {
        if (fadeSamples <= 0) return sample
        val factor = ((position + 1L).toDouble() / fadeSamples).coerceAtMost(1.0)
        return scale(sample, factor)
    }

    private fun scale(sample: Short, factor: Double): Short =
        (sample.toDouble() * factor)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
}
