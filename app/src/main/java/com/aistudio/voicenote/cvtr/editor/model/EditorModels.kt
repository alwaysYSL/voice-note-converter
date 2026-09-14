package com.aistudio.voicenote.cvtr.editor.model

internal const val MAX_TRACKS = 5
internal const val MAX_TIMELINE_MS = 300_000L

/** A source file is referenced by URI and is never modified by timeline edits. */
internal data class AudioSourceRef(
    val uri: String,
    val durationMs: Long = Long.MAX_VALUE,
)

/** Parameters that are applied while a clip is rendered. */
internal class ClipEffects(
    fadeInMs: Long = 0L,
    fadeOutMs: Long = 0L,
    val gain: Float = 1f,
    pitchSemitones: Float = 0f,
    speed: Float = 1f,
) {
    init {
        require(gain.isFinite()) { "gain must be finite" }
    }

    val fadeInMs: Long = fadeInMs.coerceIn(0L, MAX_FADE_MS)
    val fadeOutMs: Long = fadeOutMs.coerceIn(0L, MAX_FADE_MS)
    val pitchSemitones: Float = pitchSemitones.coerceIn(-4f, 4f)
    val speed: Float = speed.coerceIn(0.5f, 2f)

    val pitch: Float
        get() = pitchSemitones

    fun copy(
        fadeInMs: Long = this.fadeInMs,
        fadeOutMs: Long = this.fadeOutMs,
        gain: Float = this.gain,
        pitchSemitones: Float = this.pitchSemitones,
        speed: Float = this.speed,
    ): ClipEffects = ClipEffects(fadeInMs, fadeOutMs, gain, pitchSemitones, speed)

    operator fun component1(): Long = fadeInMs
    operator fun component2(): Long = fadeOutMs
    operator fun component3(): Float = gain
    operator fun component4(): Float = pitchSemitones
    operator fun component5(): Float = speed

    override fun equals(other: Any?): Boolean = other is ClipEffects &&
        fadeInMs == other.fadeInMs &&
        fadeOutMs == other.fadeOutMs &&
        gain == other.gain &&
        pitchSemitones == other.pitchSemitones &&
        speed == other.speed

    override fun hashCode(): Int {
        var result = fadeInMs.hashCode()
        result = 31 * result + fadeOutMs.hashCode()
        result = 31 * result + gain.hashCode()
        result = 31 * result + pitchSemitones.hashCode()
        result = 31 * result + speed.hashCode()
        return result
    }

    override fun toString(): String =
        "ClipEffects(fadeInMs=$fadeInMs, fadeOutMs=$fadeOutMs, gain=$gain, " +
            "pitchSemitones=$pitchSemitones, speed=$speed)"

    companion object {
        const val MAX_FADE_MS = 5_000L
    }

    val normalizedPitchSemitones: Float
        get() = pitchSemitones

    val normalizedSpeed: Float
        get() = speed
}

internal fun ClipEffects.clampedForDuration(durationMs: Long): ClipEffects {
    val halfDuration = durationMs.coerceAtLeast(0L) / 2L
    val fadeLimit = minOf(ClipEffects.MAX_FADE_MS, halfDuration)
    return copy(
        fadeInMs = fadeInMs.coerceIn(0L, fadeLimit),
        fadeOutMs = fadeOutMs.coerceIn(0L, fadeLimit),
    )
}

internal data class AudioClip(
    val id: String,
    val source: AudioSourceRef,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val effects: ClipEffects = ClipEffects(),
) {
    val timelineDurationMs: Long
        get() = ((sourceEndMs - sourceStartMs) / effects.normalizedSpeed).toLong()

    val timelineEndMs: Long
        get() = timelineStartMs + timelineDurationMs
}

internal data class EditorTrack(
    val id: String,
    val name: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val clips: List<AudioClip> = emptyList(),
)

internal data class EditorSession(
    val id: String,
    val tracks: List<EditorTrack>,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val exportPreset: ExportPreset = ExportPreset.VOICE_NOTE_32,
    val dirty: Boolean = false,
) {
    companion object {
        internal fun empty(id: String = "session"): EditorSession = EditorSession(id = id, tracks = emptyList())
    }
}

internal enum class ExportPreset {
    VOICE_NOTE_32,
    HIGH_QUALITY_64,
}

internal enum class TimelineError {
    TRACK_LIMIT,
    DURATION_LIMIT,
    INVALID_SOURCE_RANGE,
    INVALID_TIMELINE_RANGE,
    OVERLAP,
    MISSING_ID,
    DUPLICATE_ID,
}

internal sealed class TimelineResult {
    internal data class Accepted(val value: EditorSession) : TimelineResult()
    internal data class Rejected(val reason: TimelineError) : TimelineResult()
}

/** Convenience accessor used by callers after they have established that a result was accepted. */
internal val TimelineResult.value: EditorSession
    get() = (this as TimelineResult.Accepted).value
