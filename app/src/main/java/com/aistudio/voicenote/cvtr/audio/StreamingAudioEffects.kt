package com.aistudio.voicenote.cvtr.audio

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToInt

internal class StreamingAudioEffects(
    options: AudioProcessingOptions,
    private val silenceThreshold: Int = 512,
    minSilenceSamples: Int = 2_400
) {
    private val silenceTrimmer = if (options.trimSilence) {
        StreamingSilenceTrimmer(silenceThreshold, minSilenceSamples)
    } else {
        null
    }
    private val normalizer = if (options.normalizeAudio) {
        StreamingPeakNormalizer()
    } else {
        null
    }

    fun process(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val trimmed = silenceTrimmer?.process(samples) ?: samples
        return normalizer?.process(trimmed) ?: trimmed
    }

    fun finish(): ShortArray {
        val trimmed = silenceTrimmer?.finish() ?: ShortArray(0)
        return normalizer?.process(trimmed) ?: trimmed
    }
}

private class StreamingSilenceTrimmer(
    private val threshold: Int,
    private val minSilenceSamples: Int
) {
    private val pendingSilence = ArrayDeque<Short>()
    private var started = false

    fun process(samples: ShortArray): ShortArray {
        val output = ShortArrayBuilder(samples.size)
        samples.forEach { sample ->
            if (!started) {
                if (isAudible(sample)) {
                    started = true
                    pendingSilence.clear()
                    output.add(sample)
                }
            } else if (isAudible(sample)) {
                while (pendingSilence.isNotEmpty()) {
                    output.add(pendingSilence.removeFirst())
                }
                output.add(sample)
            } else {
                pendingSilence.addLast(sample)
                if (pendingSilence.size > minSilenceSamples) {
                    output.add(pendingSilence.removeFirst())
                }
            }
        }
        return output.toArray()
    }

    fun finish(): ShortArray {
        pendingSilence.clear()
        return ShortArray(0)
    }

    private fun isAudible(sample: Short): Boolean = abs(sample.toInt()) >= threshold
}

private class StreamingPeakNormalizer(
    private val targetPeak: Float = 31_129f,
    private val maximumGain: Float = 32f
) {
    private var observedPeak = 1f

    fun process(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        samples.forEach { sample ->
            observedPeak = maxOf(observedPeak, abs(sample.toInt()).toFloat())
        }
        val gain = (targetPeak / observedPeak).coerceIn(0.25f, maximumGain)
        return ShortArray(samples.size) { index ->
            (samples[index] * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }
}

private class ShortArrayBuilder(initialCapacity: Int) {
    private var values = ShortArray(initialCapacity.coerceAtLeast(1))
    private var size = 0

    fun add(value: Short) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(): ShortArray = values.copyOf(size)
}
