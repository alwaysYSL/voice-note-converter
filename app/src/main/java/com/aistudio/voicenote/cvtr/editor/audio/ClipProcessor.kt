package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.audio.StreamingPitchShifter
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import kotlin.math.roundToInt

/** A streaming effect processor used by one clip at a time. */
internal interface ClipEffectProcessor : AutoCloseable {
    fun process(input: ShortArray, outputFrames: Int): ShortArray

    override fun close()
}

/** Injectable native-effect seam; JVM tests can verify effect parameters without JNI. */
internal fun interface ClipEffectProcessorFactory {
    fun create(speed: Float, pitchSemitones: Float): ClipEffectProcessor
}

/** Applies source conversion, speed, pitch, clip gain and clip fades in that order. */
internal class ClipProcessor(
    private val effectFactory: ClipEffectProcessorFactory = ClipEffectProcessorFactory {
            speed, pitchSemitones ->
        NativeClipEffectProcessor(speed, pitchSemitones)
    },
) : AutoCloseable {
    private val effectsByClip = LinkedHashMap<String, ActiveEffects>()

    fun process(clip: AudioClip, sourceFrames: ShortArray, outputStartFrame: Long): FloatArray {
        if (sourceFrames.isEmpty()) return FloatArray(0)

        val effects = clip.effects
        val isNeutral = effects.normalizedSpeed == 1f && effects.normalizedPitchSemitones == 0f
        val outputFrameCount = if (isNeutral) {
            sourceFrames.size
        } else {
            (sourceFrames.size / effects.normalizedSpeed).roundToInt().coerceAtLeast(1)
        }
        val processed = if (isNeutral) {
            sourceFrames
        } else {
            val active = activeEffects(clip.id, effects.normalizedSpeed, effects.normalizedPitchSemitones)
            try {
                active.processor.process(sourceFrames, outputFrameCount)
            } catch (error: Throwable) {
                effectsByClip.remove(clip.id)?.let { runCatching { it.close() } }
                throw error
            }
        }

        val output = FloatArray(processed.size)
        val clipStartFrame = clip.timelineStartMs * EDITOR_SAMPLE_RATE / 1_000L
        val clipDurationFrames = clip.timelineDurationMs * EDITOR_SAMPLE_RATE / 1_000L
        val fadeInFrames = effects.fadeInMs * EDITOR_SAMPLE_RATE / 1_000L
        val fadeOutFrames = effects.fadeOutMs * EDITOR_SAMPLE_RATE / 1_000L
        for (index in processed.indices) {
            val localFrame = outputStartFrame + index - clipStartFrame
            val fadeIn = if (fadeInFrames <= 0L) {
                1f
            } else {
                (localFrame.toFloat() / fadeInFrames.toFloat()).coerceIn(0f, 1f)
            }
            val fadeOut = if (fadeOutFrames <= 0L) {
                1f
            } else {
                ((clipDurationFrames - localFrame).toFloat() / fadeOutFrames.toFloat())
                    .coerceIn(0f, 1f)
            }
            output[index] = processed[index] / PCM16_SCALE * effects.gain * minOf(fadeIn, fadeOut)
        }
        return output
    }

    override fun close() {
        effectsByClip.values.forEach { runCatching { it.close() } }
        effectsByClip.clear()
    }

    private fun activeEffects(clipId: String, speed: Float, pitch: Float): ActiveEffects {
        val current = effectsByClip[clipId]
        if (current != null && current.speed == speed && current.pitch == pitch) return current
        current?.let { runCatching { it.close() } }
        effectsByClip.remove(clipId)
        return ActiveEffects(speed, pitch, effectFactory.create(speed, pitch)).also {
            effectsByClip[clipId] = it
        }
    }

    private class ActiveEffects(
        val speed: Float,
        val pitch: Float,
        val processor: ClipEffectProcessor,
    ) : AutoCloseable {
        override fun close() = processor.close()
    }

    private class NativeClipEffectProcessor(
        speed: Float,
        pitchSemitones: Float,
    ) : ClipEffectProcessor {
        private val shifter = StreamingPitchShifter(
            sampleRate = EDITOR_SAMPLE_RATE,
            channels = EDITOR_CHANNEL_COUNT,
            semitones = pitchSemitones,
            tempoRatio = speed,
        )

        override fun process(input: ShortArray, outputFrames: Int): ShortArray =
            shifter.process(input, outputFrames)

        override fun close() = shifter.close()
    }

    private companion object {
        const val PCM16_SCALE = 32_768f
    }
}
