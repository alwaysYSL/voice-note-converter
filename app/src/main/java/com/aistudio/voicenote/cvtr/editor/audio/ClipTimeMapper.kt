package com.aistudio.voicenote.cvtr.editor.audio

import kotlin.math.floor

/** Sample-frame mapping for one clip after its speed effect has been applied. */
internal class ClipTimeMapper(
    val sourceStartFrame: Long,
    val timelineStartFrame: Long,
    speed: Float,
    val sourceEndFrame: Long? = null,
    sourceDurationFrames: Long? = null,
) {
    init {
        require(sourceDurationFrames == null || sourceEndFrame == null) {
            "Specify either sourceEndFrame or sourceDurationFrames, not both."
        }
        require(sourceStartFrame >= 0L) { "sourceStartFrame must be non-negative" }
        require(timelineStartFrame >= 0L) { "timelineStartFrame must be non-negative" }
        require(sourceEndFrame == null || sourceEndFrame >= sourceStartFrame) {
            "sourceEndFrame must not precede sourceStartFrame"
        }
        require(sourceDurationFrames == null || sourceDurationFrames >= 0L) {
            "sourceDurationFrames must be non-negative"
        }
    }

    private val effectiveSourceEndFrame: Long? = sourceEndFrame ?: sourceDurationFrames?.let {
        addSaturated(sourceStartFrame, it)
    }

    /** Phase 1 normalizes speed to this range; retaining the same normalization keeps mapping deterministic. */
    val speed: Float = speed
        .takeIf { it.isFinite() }
        ?.coerceIn(0.5f, 2f)
        ?: 1f

    /** Maps the sample at a timeline frame to the corresponding source sample. */
    fun sourceFrameForTimelineFrame(timelineFrame: Long): Long {
        val timelineDelta = timelineFrame.toDouble() - timelineStartFrame.toDouble()
        val sourceDelta = (timelineDelta * speed.toDouble()).let(::floor).toLong()
        val mappedFrame = addSaturated(sourceStartFrame, sourceDelta)
        val endFrame = effectiveSourceEndFrame ?: return mappedFrame
        if (endFrame <= sourceStartFrame) return sourceStartFrame
        return mappedFrame.coerceIn(sourceStartFrame, endFrame - 1L)
    }

    /** The half-open output frame range occupied by the configured source range. */
    val activeOutputRange: LongRange
        get() = effectiveSourceEndFrame?.let(::activeOutputRange) ?: LongRange.EMPTY

    /** Returns the occupied timeline frames for an explicit exclusive source end. */
    fun activeOutputRange(sourceEndFrame: Long): LongRange {
        if (sourceEndFrame <= sourceStartFrame) return LongRange.EMPTY
        val sourceLength = sourceEndFrame.toDouble() - sourceStartFrame.toDouble()
        val outputLength = (sourceLength / speed.toDouble()).toLong()
        if (outputLength <= 0L) return LongRange.EMPTY
        val timelineEnd = addSaturated(timelineStartFrame, outputLength)
        return if (timelineEnd <= timelineStartFrame) {
            LongRange.EMPTY
        } else {
            timelineStartFrame until timelineEnd
        }
    }

    companion object {
        private fun addSaturated(left: Long, right: Long): Long = when {
            right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
            right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
            else -> left + right
        }
    }
}
