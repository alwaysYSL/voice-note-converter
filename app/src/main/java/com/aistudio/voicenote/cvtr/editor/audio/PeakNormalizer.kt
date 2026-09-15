package com.aistudio.voicenote.cvtr.editor.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class NormalizationStats(val peak: Int, val gain: Float)

internal object PeakNormalizer {

    fun analyze(sequence: Sequence<ShortArray>): NormalizationStats {
        var maxPeak = 0
        for (chunk in sequence) {
            for (sample in chunk) {
                val a = abs(sample.toInt())
                if (a > maxPeak) {
                    maxPeak = a
                }
            }
        }
        
        val targetPeak = (0.89125f * 32767).toInt() // approx -1 dBFS
        
        val gain = if (maxPeak == 0) {
            1.0f
        } else {
            val targetGain = targetPeak.toFloat() / maxPeak.toFloat()
            // cap to avoid excessive amplification
            minOf(targetGain, MAX_NORMALIZATION_GAIN)
        }
        
        return NormalizationStats(maxPeak, gain)
    }

    fun apply(chunk: ShortArray, stats: NormalizationStats, targetDbFs: Float = -1f): ShortArray {
        val targetPeak = (10.0.pow(targetDbFs / 20.0) * 32767).toFloat()
        val effectiveGain = if (targetDbFs == -1f) {
            stats.gain
        } else {
            if (stats.peak == 0) 1.0f else minOf(targetPeak / stats.peak, MAX_NORMALIZATION_GAIN)
        }
        val out = ShortArray(chunk.size)
        for (i in chunk.indices) {
            val sample = (chunk[i] * effectiveGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = sample.toShort()
        }
        return out
    }
    
    const val MAX_NORMALIZATION_GAIN = 100.0f
}
