package com.aistudio.voicenote.cvtr.editor.model

import java.util.Collections

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
    val processedCacheKey: String? = null,
) {
    init {
        require(gain.isFinite()) { "gain must be finite" }
    }

    val fadeInMs: Long = fadeInMs.coerceIn(0L, MAX_FADE_MS)
    val fadeOutMs: Long = fadeOutMs.coerceIn(0L, MAX_FADE_MS)
    val pitchSemitones: Float = pitchSemitones
        .takeIf { it.isFinite() }
        ?.coerceIn(-4f, 4f)
        ?: 0f
    val speed: Float = speed
        .takeIf { it.isFinite() }
        ?.coerceIn(0.5f, 2f)
        ?: 1f

    val pitch: Float
        get() = pitchSemitones

    fun copy(
        fadeInMs: Long = this.fadeInMs,
        fadeOutMs: Long = this.fadeOutMs,
        gain: Float = this.gain,
        pitchSemitones: Float = this.pitchSemitones,
        speed: Float = this.speed,
        processedCacheKey: String? = this.processedCacheKey,
    ): ClipEffects = ClipEffects(fadeInMs, fadeOutMs, gain, pitchSemitones, speed, processedCacheKey)

    operator fun component1(): Long = fadeInMs
    operator fun component2(): Long = fadeOutMs
    operator fun component3(): Float = gain
    operator fun component4(): Float = pitchSemitones
    operator fun component5(): Float = speed
    operator fun component6(): String? = processedCacheKey

    override fun equals(other: Any?): Boolean = other is ClipEffects &&
        fadeInMs == other.fadeInMs &&
        fadeOutMs == other.fadeOutMs &&
        gain == other.gain &&
        pitchSemitones == other.pitchSemitones &&
        speed == other.speed &&
        processedCacheKey == other.processedCacheKey

    override fun hashCode(): Int {
        var result = fadeInMs.hashCode()
        result = 31 * result + fadeOutMs.hashCode()
        result = 31 * result + gain.hashCode()
        result = 31 * result + pitchSemitones.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + (processedCacheKey?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ClipEffects(fadeInMs=$fadeInMs, fadeOutMs=$fadeOutMs, gain=$gain, " +
            "pitchSemitones=$pitchSemitones, speed=$speed, processedCacheKey=$processedCacheKey)"

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

/**
 * Immutable-at-the-boundary track value. The explicit copy implementation is intentional:
 * Kotlin data-class generated copies would otherwise retain a caller-owned mutable list.
 */
internal class EditorTrack(
    val id: String,
    val name: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    clips: List<AudioClip> = emptyList(),
) {
    val clips: List<AudioClip> = immutableList(clips)

    operator fun component1(): String = id
    operator fun component2(): String = name
    operator fun component3(): Float = volume
    operator fun component4(): Boolean = muted
    operator fun component5(): List<AudioClip> = clips

    fun copy(
        id: String = this.id,
        name: String = this.name,
        volume: Float = this.volume,
        muted: Boolean = this.muted,
        clips: List<AudioClip> = this.clips,
    ): EditorTrack = EditorTrack(id, name, volume, muted, clips)

    override fun equals(other: Any?): Boolean = other is EditorTrack &&
        id == other.id && name == other.name && volume == other.volume &&
        muted == other.muted && clips == other.clips

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + volume.hashCode()
        result = 31 * result + muted.hashCode()
        result = 31 * result + clips.hashCode()
        return result
    }

    override fun toString(): String =
        "EditorTrack(id=$id, name=$name, volume=$volume, muted=$muted, clips=$clips)"
}

/** Immutable session value with defensive copies at every list boundary. */
internal class EditorSession(
    val id: String,
    tracks: List<EditorTrack>,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val exportPreset: ExportPreset = ExportPreset.VOICE_NOTE_32,
    val dirty: Boolean = false,
) {
    val tracks: List<EditorTrack> = immutableList(tracks)

    operator fun component1(): String = id
    operator fun component2(): List<EditorTrack> = tracks
    operator fun component3(): String? = selectedClipId
    operator fun component4(): Long = playheadMs
    operator fun component5(): ExportPreset = exportPreset
    operator fun component6(): Boolean = dirty

    fun copy(
        id: String = this.id,
        tracks: List<EditorTrack> = this.tracks,
        selectedClipId: String? = this.selectedClipId,
        playheadMs: Long = this.playheadMs,
        exportPreset: ExportPreset = this.exportPreset,
        dirty: Boolean = this.dirty,
    ): EditorSession = EditorSession(id, tracks, selectedClipId, playheadMs, exportPreset, dirty)

    override fun equals(other: Any?): Boolean = other is EditorSession &&
        id == other.id && tracks == other.tracks && selectedClipId == other.selectedClipId &&
        playheadMs == other.playheadMs && exportPreset == other.exportPreset && dirty == other.dirty

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + tracks.hashCode()
        result = 31 * result + (selectedClipId?.hashCode() ?: 0)
        result = 31 * result + playheadMs.hashCode()
        result = 31 * result + exportPreset.hashCode()
        result = 31 * result + dirty.hashCode()
        return result
    }

    override fun toString(): String =
        "EditorSession(id=$id, tracks=$tracks, selectedClipId=$selectedClipId, " +
            "playheadMs=$playheadMs, exportPreset=$exportPreset, dirty=$dirty)"

    companion object {
        internal fun empty(id: String = "session"): EditorSession = EditorSession(id = id, tracks = emptyList())
    }
}

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(values.toList())

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
