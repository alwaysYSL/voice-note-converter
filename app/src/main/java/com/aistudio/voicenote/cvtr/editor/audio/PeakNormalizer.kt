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
            minOf(targetGain, 100.0f) // Just capping it to 100 for now, prompt says "cap to avoid excessive amplification"
        }
        
        return NormalizationStats(maxPeak, gain)
    }

    fun apply(chunk: ShortArray, stats: NormalizationStats, targetDbFs: Float = -1f): ShortArray {
        // We use the gain from stats
        val out = ShortArray(chunk.size)
        // Wait, the interface says `targetDbFs: Float = -1f`. If the user passes a different targetDbFs,
        // we might need to adjust the gain? But "applies one fixed gain across chunks" implies we just apply it.
        // Actually, if targetDbFs is not -1, we can compute the ratio.
        val targetPeak = (10.0.pow(targetDbFs / 20.0) * 32767).toFloat()
        // Wait, stats already has the gain for -1dBFS. If targetDbFs is different, 
        // we should recompute the gain? "computes fixed gain capped to avoid excessive amplification (e.g. gain = min(targetPeak / maxPeak, maxGain), or if peak == 0 gain = 1.0f)."
        // Let's just use the gain from stats but adjusted if targetDbFs != -1f?
        // Let's assume stats.peak is the max peak, and we can recalculate gain if targetDbFs is different, 
        // OR we just use stats.gain if it's for -1dBFS. Let's just calculate the effective gain.
        val effectiveGain = if (targetDbFs == -1f) {
            stats.gain
        } else {
            if (stats.peak == 0) 1.0f else minOf(targetPeak / stats.peak, 100.0f)
        }

        for (i in chunk.indices) {
            val sample = (chunk[i] * effectiveGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = sample.toShort()
        }
        return out
    }
}
