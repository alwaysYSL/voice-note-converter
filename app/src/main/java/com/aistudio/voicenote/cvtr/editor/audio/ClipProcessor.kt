package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.audio.StreamingPitchShifter
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import kotlin.math.floor

/** A streaming effect processor used by one clip at a time. */
internal interface ClipEffectProcessor : AutoCloseable {
    fun process(input: ShortArray, outputFrames: Int): ShortArray

    fun flush(): ShortArray = ShortArray(0)

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

    fun process(
        clip: AudioClip,
        sourceFrames: ShortArray,
        outputStartFrame: Long,
        outputFrameCount: Int? = null,
    ): FloatArray {
        require(outputFrameCount == null || outputFrameCount >= 0) {
            "outputFrameCount must be non-negative"
        }
        if (sourceFrames.isEmpty()) return FloatArray(outputFrameCount ?: 0)

        val effects = clip.effects
        val isNeutral = effects.normalizedSpeed == 1f && effects.normalizedPitchSemitones == 0f
        if (isNeutral) {
            val requestedFrames = outputFrameCount ?: sourceFrames.size
            return renderPcm(
                clip,
                effects,
                fitSamples(sourceFrames, requestedFrames),
                outputStartFrame,
                requestedFrames,
            )
        }

        val active = activeEffects(
            clip.id,
            effects.normalizedSpeed,
            effects.normalizedPitchSemitones,
            outputStartFrame,
        )
        val requestedFrames = active.outputFrameCount(
            sourceFrameCount = sourceFrames.size,
            speed = effects.normalizedSpeed,
            requestedOutputFrameCount = outputFrameCount,
        )
        val processed = try {
            active.processor.process(sourceFrames, requestedFrames)
        } catch (error: Throwable) {
            effectsByClip.remove(clip.id)?.let { runCatching { it.close() } }
            throw error
        }
        active.nextOutputFrame = outputStartFrame + requestedFrames
        return renderPcm(
            clip,
            effects,
            fitSamples(processed, requestedFrames),
            outputStartFrame,
            requestedFrames,
        )
    }

    /** Drains a clip's native latency tail without exceeding the requested timeline boundary. */
    fun flush(clip: AudioClip, outputStartFrame: Long, outputFrameCount: Int): FloatArray {
        require(outputFrameCount >= 0) { "outputFrameCount must be non-negative" }
        val effects = clip.effects
        val active = effectsByClip.remove(clip.id)
            ?: return FloatArray(outputFrameCount)
        if (active.nextOutputFrame != null && active.nextOutputFrame != outputStartFrame) {
            runCatching { active.close() }
            return FloatArray(outputFrameCount)
        }
        if (outputFrameCount == 0) {
            runCatching { active.close() }
            return FloatArray(0)
        }
        val tail = try {
            active.processor.flush()
        } finally {
            runCatching { active.close() }
        }
        return renderPcm(
            clip,
            effects,
            fitSamples(tail, outputFrameCount),
            outputStartFrame,
            outputFrameCount,
        )
    }

    private fun renderPcm(
        clip: AudioClip,
        effects: ClipEffects,
        processed: ShortArray,
        outputStartFrame: Long,
        outputFrameCount: Int,
    ): FloatArray {
        val output = FloatArray(outputFrameCount)
        val clipStartFrame = clip.timelineStartMs * EDITOR_SAMPLE_RATE / 1_000L
        val clipDurationFrames = clip.timelineDurationMs * EDITOR_SAMPLE_RATE / 1_000L
        val fadeInFrames = effects.fadeInMs * EDITOR_SAMPLE_RATE / 1_000L
        val fadeOutFrames = effects.fadeOutMs * EDITOR_SAMPLE_RATE / 1_000L
        for (index in 0 until outputFrameCount) {
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
            output[index] = processed.getOrElse(index) { 0 }
                .toFloat() / PCM16_SCALE * effects.gain * minOf(fadeIn, fadeOut)
        }
        return output
    }

    private fun fitSamples(samples: ShortArray, frameCount: Int): ShortArray {
        if (samples.size == frameCount) return samples
        return ShortArray(frameCount).also {
            samples.copyInto(it, endIndex = minOf(samples.size, frameCount))
        }
    }

    override fun close() {
        effectsByClip.values.forEach { runCatching { it.close() } }
        effectsByClip.clear()
    }

    private fun activeEffects(
        clipId: String,
        speed: Float,
        pitch: Float,
        outputStartFrame: Long,
    ): ActiveEffects {
        val current = effectsByClip[clipId]
        if (current != null && current.speed == speed && current.pitch == pitch &&
            current.isContiguous(outputStartFrame)
        ) {
            return current
        }
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
        var nextOutputFrame: Long? = null
        private var fractionalOutputFrames = 0.0

        fun isContiguous(outputStartFrame: Long): Boolean =
            nextOutputFrame == null || nextOutputFrame == outputStartFrame

        fun outputFrameCount(
            sourceFrameCount: Int,
            speed: Float,
            requestedOutputFrameCount: Int?,
        ): Int {
            if (requestedOutputFrameCount != null) {
                fractionalOutputFrames = 0.0
                return requestedOutputFrameCount
            }
            val exactFrameCount = sourceFrameCount.toDouble() / speed + fractionalOutputFrames
            val frameCount = floor(exactFrameCount).toInt().coerceAtLeast(0)
            fractionalOutputFrames = exactFrameCount - frameCount
            return frameCount
        }

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

        override fun flush(): ShortArray = shifter.flush()

        override fun close() = shifter.close()
    }

    private companion object {
        const val PCM16_SCALE = 32_768f
    }
}
