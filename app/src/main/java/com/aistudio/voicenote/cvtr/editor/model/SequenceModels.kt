package com.aistudio.voicenote.cvtr.editor.model

import kotlin.math.roundToLong

/**
 * A single audio clip in a contiguous sequence timeline.
 * Unlike [AudioClip], [SequenceClip] does not store an arbitrary [AudioClip.timelineStartMs];
 * its position in time is purely derived from its sequential position within the [SequenceSession].
 */
internal data class SequenceClip(
    val id: String,
    val source: AudioSourceRef,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = source.durationMs,
    val effects: ClipEffects = ClipEffects(),
) {
    init {
        require(id.isNotBlank()) { "Clip ID cannot be blank" }
        require(sourceStartMs >= 0L) { "sourceStartMs cannot be negative" }
        require(sourceEndMs >= sourceStartMs) { "sourceEndMs ($sourceEndMs) must be >= sourceStartMs ($sourceStartMs)" }
    }

    val sourceDurationMs: Long
        get() = sourceEndMs - sourceStartMs

    val durationMs: Long
        get() = if (effects.normalizedSpeed > 0f) {
            (sourceDurationMs / effects.normalizedSpeed).roundToLong()
        } else {
            sourceDurationMs
        }
}

/**
 * A strictly ordered, collision-free audio sequence (A -> B -> C).
 * Audio clips are laid out sequentially without gaps or overlaps.
 */
internal data class SequenceSession(
    val id: String,
    val clips: List<SequenceClip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val exportPreset: ExportPreset = ExportPreset.VOICE_NOTE_32,
    val dirty: Boolean = false,
    val draftId: String? = null,
    val revision: Long = 0L,
) {
    val totalDurationMs: Long
        get() = clips.sumOf { it.durationMs }

    fun findClip(clipId: String): SequenceClip? = clips.firstOrNull { it.id == clipId }

    fun indexOfClip(clipId: String): Int = clips.indexOfFirst { it.id == clipId }

    /**
     * Projects this sequence into a 1-track [EditorSession] for preview playback,
     * waveform rendering, and timeline canvas display.
     */
    fun toRenderSession(trackId: String = "track-sequence", trackName: String = "Sequence"): EditorSession {
        var currentTimelineMs = 0L
        val audioClips = clips.map { seqClip ->
            val clipDuration = seqClip.durationMs
            val audioClip = AudioClip(
                id = seqClip.id,
                source = seqClip.source,
                sourceStartMs = seqClip.sourceStartMs,
                sourceEndMs = seqClip.sourceEndMs,
                timelineStartMs = currentTimelineMs,
                effects = seqClip.effects,
            )
            currentTimelineMs += clipDuration
            audioClip
        }
        val track = EditorTrack(
            id = trackId,
            name = trackName,
            clips = audioClips,
        )
        return EditorSession(
            id = id,
            tracks = listOf(track),
            selectedClipId = selectedClipId,
            playheadMs = playheadMs.coerceIn(0L, currentTimelineMs.coerceAtLeast(0L)),
            exportPreset = exportPreset,
            dirty = dirty,
            draftId = draftId,
            revision = revision,
        )
    }
}

/**
 * Flattens any [EditorSession] (including legacy multitrack sessions) into a linear [SequenceSession].
 * Clips across all tracks are ordered by their [AudioClip.timelineStartMs] and laid out consecutively.
 */
internal fun EditorSession.toSequenceSession(): SequenceSession {
    val allClips = tracks.flatMap { it.clips }
        .sortedWith(compareBy({ it.timelineStartMs }, { it.id }))
        .map { clip ->
            SequenceClip(
                id = clip.id,
                source = clip.source,
                sourceStartMs = clip.sourceStartMs,
                sourceEndMs = clip.sourceEndMs,
                effects = clip.effects,
            )
        }
    return SequenceSession(
        id = id,
        clips = allClips,
        selectedClipId = selectedClipId,
        playheadMs = playheadMs,
        exportPreset = exportPreset,
        dirty = dirty,
        draftId = draftId,
        revision = revision,
    )
}

/**
 * Appends multiple new clips to the end of this sequence.
 */
internal fun SequenceSession.appendClips(newClips: List<SequenceClip>): SequenceSession {
    if (newClips.isEmpty()) return this
    val updatedClips = clips + newClips
    val newSelected = selectedClipId ?: newClips.first().id
    return copy(
        clips = updatedClips,
        selectedClipId = newSelected,
        dirty = true,
        revision = revision + 1L,
    )
}

/**
 * Reorders a clip from its current index to [targetIndex] within the sequence.
 */
internal fun SequenceSession.reorderClip(clipId: String, targetIndex: Int): SequenceSession {
    val currentIndex = indexOfClip(clipId)
    if (currentIndex < 0 || currentIndex == targetIndex) return this
    val validTarget = targetIndex.coerceIn(0, clips.lastIndex)
    if (currentIndex == validTarget) return this

    val mutableClips = clips.toMutableList()
    val moving = mutableClips.removeAt(currentIndex)
    mutableClips.add(validTarget, moving)

    return copy(
        clips = mutableClips,
        selectedClipId = clipId,
        dirty = true,
        revision = revision + 1L,
    )
}

/**
 * Removes a clip from the sequence.
 */
internal fun SequenceSession.removeClip(clipId: String): SequenceSession {
    val currentIndex = indexOfClip(clipId)
    if (currentIndex < 0) return this
    val updatedClips = clips.filterNot { it.id == clipId }
    val newSelected = if (selectedClipId == clipId) {
        updatedClips.getOrNull(currentIndex.coerceAtMost(updatedClips.lastIndex))?.id
    } else {
        selectedClipId
    }
    return copy(
        clips = updatedClips,
        selectedClipId = newSelected,
        dirty = true,
        revision = revision + 1L,
    )
}
