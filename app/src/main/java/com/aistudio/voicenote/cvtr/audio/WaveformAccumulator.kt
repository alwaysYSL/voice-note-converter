package com.aistudio.voicenote.cvtr.audio

import kotlin.math.abs

internal class WaveformAccumulator(
    private val targetBars: Int,
    expectedSamples: Long = 0L
) {
    private val sums = DoubleArray(targetBars.coerceAtLeast(1))
    private val counts = LongArray(sums.size)
    private var expectedSamples = expectedSamples
    private var samplesSeen = 0L

    fun setExpectedSamples(value: Long) {
        expectedSamples = value.coerceAtLeast(0L)
    }

    fun add(samples: ShortArray) {
        samples.forEach { sample ->
            val bucket = if (expectedSamples > 0L) {
                ((samplesSeen * sums.size) / expectedSamples)
                    .toInt()
                    .coerceIn(0, sums.lastIndex)
            } else {
                (samplesSeen % sums.size).toInt()
            }
            sums[bucket] += abs(sample.toInt()).toDouble()
            counts[bucket]++
            samplesSeen++
        }
    }

    fun result(): List<Int> {
        if (samplesSeen == 0L) return List(sums.size) { 1 }
        val averages = sums.indices.map { index ->
            if (counts[index] == 0L) 0f else (sums[index] / counts[index]).toFloat()
        }
        val maxPeak = averages.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return averages.map { (it / maxPeak * 31f).toInt().coerceIn(1, 31) }
    }
}
