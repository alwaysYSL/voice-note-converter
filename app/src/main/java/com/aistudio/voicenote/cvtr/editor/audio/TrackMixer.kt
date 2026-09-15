package com.aistudio.voicenote.cvtr.editor.audio

/** A normalized mono chunk from one track, before master processing. */
internal data class TrackChunk(
    val samples: FloatArray,
    val volume: Float = 1f,
    val muted: Boolean = false,
)

/** Sums active track chunks into a bounded-size float buffer. */
internal class TrackMixer {
    fun mix(chunks: List<TrackChunk>, frameCount: Int): FloatArray {
        require(frameCount >= 0) { "frameCount must be non-negative" }
        val output = FloatArray(frameCount)
        for (chunk in chunks) {
            if (chunk.muted) continue
            val volume = chunk.volume.takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: 0f
            if (volume == 0f) continue
            val count = minOf(frameCount, chunk.samples.size)
            for (frame in 0 until count) {
                val sample = chunk.samples[frame]
                if (sample.isFinite()) output[frame] += sample * volume
            }
        }
        return output
    }
}
