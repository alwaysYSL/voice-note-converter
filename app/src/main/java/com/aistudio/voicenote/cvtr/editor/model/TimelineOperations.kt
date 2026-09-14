package com.aistudio.voicenote.cvtr.editor.model

import kotlin.math.roundToLong

/** Pure, immutable transformations over an [EditorSession]. */
internal object TimelineOperations {
    fun addTrack(session: EditorSession, track: EditorTrack): TimelineResult {
        if (session.tracks.size >= MAX_TRACKS) return TimelineResult.Rejected(TimelineError.TRACK_LIMIT)
        if (session.tracks.any { it.id == track.id }) return TimelineResult.Rejected(TimelineError.DUPLICATE_ID)
        return commit(session.copy(tracks = session.tracks + track))
    }

    /** Adds a clip to an existing track. This is used by import and is kept as a pure model operation. */
    fun addClip(session: EditorSession, trackId: String, clip: AudioClip): TimelineResult {
        val trackIndex = session.tracks.indexOfFirst { it.id == trackId }
        if (trackIndex < 0) return TimelineResult.Rejected(TimelineError.MISSING_ID)
        if (session.tracks.asSequence().flatMap { it.clips.asSequence() }.any { it.id == clip.id }) {
            return TimelineResult.Rejected(TimelineError.DUPLICATE_ID)
        }
        val tracks = session.tracks.toMutableList()
        val track = tracks[trackIndex]
        tracks[trackIndex] = track.copy(clips = track.clips + clip)
        return commit(session.copy(tracks = tracks))
    }

    fun splitClip(session: EditorSession, clipId: String, splitTimelineMs: Long): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        val clip = location.clip
        val sourceError = sourceRangeError(clip)
        if (sourceError != null) return TimelineResult.Rejected(sourceError)
        if (splitTimelineMs <= clip.timelineStartMs || splitTimelineMs >= clip.timelineEndMs) {
            return TimelineResult.Rejected(TimelineError.INVALID_TIMELINE_RANGE)
        }

        val sourceSplit = clip.sourceStartMs +
            ((splitTimelineMs - clip.timelineStartMs) * clip.effects.normalizedSpeed).roundToLong()
        if (sourceSplit <= clip.sourceStartMs || sourceSplit >= clip.sourceEndMs) {
            return TimelineResult.Rejected(TimelineError.INVALID_SOURCE_RANGE)
        }

        // Preserve the original identifier for the first segment so selection and command
        // history continue to refer to the same logical clip. Only the new segment needs
        // an identifier.
        val firstId = clip.id
        val secondId = uniqueClipId("${clip.id}-2", session, clipId)
        val first = clip.copy(
            id = firstId,
            sourceEndMs = sourceSplit,
            effects = clip.effects.copy(fadeOutMs = 0L),
        )
        val second = clip.copy(
            id = secondId,
            sourceStartMs = sourceSplit,
            timelineStartMs = splitTimelineMs,
            effects = clip.effects.copy(fadeInMs = 0L),
        )

        val tracks = session.tracks.toMutableList()
        val clips = location.track.clips.toMutableList()
        clips[location.clipIndex] = first
        clips.add(location.clipIndex + 1, second)
        tracks[location.trackIndex] = location.track.copy(clips = clips)
        val selected = session.selectedClipId
        return commit(session.copy(tracks = tracks, selectedClipId = selected))
    }

    fun trimClip(
        session: EditorSession,
        clipId: String,
        sourceStartMs: Long,
        sourceEndMs: Long,
    ): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        val candidate = location.clip.copy(sourceStartMs = sourceStartMs, sourceEndMs = sourceEndMs)
        sourceRangeError(candidate)?.let { return TimelineResult.Rejected(it) }
        return commit(session.replaceClip(location, candidate))
    }

    fun moveClip(session: EditorSession, clipId: String, timelineStartMs: Long): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        if (timelineStartMs < 0L) return TimelineResult.Rejected(TimelineError.INVALID_TIMELINE_RANGE)
        return commit(session.replaceClip(location, location.clip.copy(timelineStartMs = timelineStartMs)))
    }

    /**
     * Deletes a clip. By default the deleted duration is rippled through later clips on
     * the same track. [ripple] = false preserves the gap.
     *
     * The named aliases make the intent explicit for callers that use `rippleDelete` or
     * `preserveGap` while retaining the compact positional API.
     */
    fun deleteClip(
        session: EditorSession,
        clipId: String,
        ripple: Boolean = true,
        preserveGap: Boolean? = null,
        rippleDelete: Boolean? = null,
    ): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        val shouldRipple = rippleDelete ?: preserveGap?.not() ?: ripple
        val deleted = location.clip
        val shift = deleted.timelineDurationMs
        val remaining = location.track.clips.asSequence()
            .filter { it.id != clipId }
            .map { clip ->
                if (shouldRipple && clip.timelineStartMs >= deleted.timelineEndMs) {
                    clip.copy(timelineStartMs = clip.timelineStartMs - shift)
                } else {
                    clip
                }
            }
            .toList()
        val tracks = session.tracks.toMutableList()
        tracks[location.trackIndex] = location.track.copy(clips = remaining)
        val selected = if (session.selectedClipId == clipId) null else session.selectedClipId
        return commit(session.copy(tracks = tracks, selectedClipId = selected))
    }

    fun setTrackVolume(session: EditorSession, trackId: String, volume: Float): TimelineResult {
        val trackIndex = session.tracks.indexOfFirst { it.id == trackId }
        if (trackIndex < 0) return TimelineResult.Rejected(TimelineError.MISSING_ID)
        val boundedVolume = if (volume.isNaN()) 0f else volume.coerceIn(0f, 1.5f)
        val tracks = session.tracks.toMutableList()
        tracks[trackIndex] = tracks[trackIndex].copy(volume = boundedVolume)
        return commit(session.copy(tracks = tracks))
    }

    fun setClipEffects(session: EditorSession, clipId: String, effects: ClipEffects): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        return commit(session.replaceClip(location, location.clip.copy(effects = effects)))
    }

    fun updateClipEffects(session: EditorSession, clipId: String, effects: ClipEffects): TimelineResult =
        setClipEffects(session, clipId, effects)

    fun setClipFade(
        session: EditorSession,
        clipId: String,
        fadeInMs: Long,
        fadeOutMs: Long,
    ): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        return setClipEffects(
            session,
            clipId,
            location.clip.effects.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs),
        )
    }

    fun setClipPitch(session: EditorSession, clipId: String, pitchSemitones: Float): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        return setClipEffects(session, clipId, location.clip.effects.copy(pitchSemitones = pitchSemitones))
    }

    fun setClipSpeed(session: EditorSession, clipId: String, speed: Float): TimelineResult {
        val location = session.findClip(clipId) ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        return setClipEffects(session, clipId, location.clip.effects.copy(speed = speed))
    }

    /** Validates and canonicalizes one immutable session without mutating the caller's lists. */
    fun validate(session: EditorSession): TimelineResult {
        if (session.tracks.size > MAX_TRACKS) return TimelineResult.Rejected(TimelineError.TRACK_LIMIT)
        if (session.playheadMs !in 0L..MAX_TIMELINE_MS) {
            return TimelineResult.Rejected(TimelineError.INVALID_TIMELINE_RANGE)
        }

        val trackIds = HashSet<String>(session.tracks.size)
        val clipIds = HashSet<String>()
        var canonical = session
        for ((trackIndex, track) in session.tracks.withIndex()) {
            if (!trackIds.add(track.id)) return TimelineResult.Rejected(TimelineError.DUPLICATE_ID)
            val volume = if (track.volume.isNaN()) 0f else track.volume.coerceIn(0f, 1.5f)
            val sortedClips = track.clips.sortedBy { it.timelineStartMs }
            var previous: AudioClip? = null
            val normalizedClips = ArrayList<AudioClip>(sortedClips.size)
            for (clip in sortedClips) {
                if (!clipIds.add(clip.id)) return TimelineResult.Rejected(TimelineError.DUPLICATE_ID)
                sourceRangeError(clip)?.let { return TimelineResult.Rejected(it) }
                if (clip.timelineStartMs < 0L || clip.timelineDurationMs <= 0L || clip.timelineEndMs < clip.timelineStartMs) {
                    return TimelineResult.Rejected(TimelineError.INVALID_TIMELINE_RANGE)
                }
                if (clip.timelineEndMs > MAX_TIMELINE_MS) {
                    return TimelineResult.Rejected(TimelineError.DURATION_LIMIT)
                }
                if (previous != null && previous.timelineEndMs > clip.timelineStartMs) {
                    return TimelineResult.Rejected(TimelineError.OVERLAP)
                }
                previous = clip
                normalizedClips += clip.copy(effects = canonicalEffects(clip))
            }
            if (track.volume != volume || normalizedClips != track.clips) {
                val tracks = canonical.tracks.toMutableList()
                tracks[trackIndex] = track.copy(volume = volume, clips = normalizedClips)
                canonical = canonical.copy(tracks = tracks)
            }
        }

        if (canonical.selectedClipId != null && canonical.selectedClipId !in clipIds) {
            return TimelineResult.Rejected(TimelineError.MISSING_ID)
        }
        return TimelineResult.Accepted(canonical)
    }

    private fun commit(session: EditorSession): TimelineResult = validate(session.copy(dirty = true))

    private fun sourceRangeError(clip: AudioClip): TimelineError? {
        if (clip.source.uri.isBlank() || clip.sourceStartMs < 0L || clip.sourceEndMs <= clip.sourceStartMs) {
            return TimelineError.INVALID_SOURCE_RANGE
        }
        if (clip.source.durationMs >= 0L && clip.sourceEndMs > clip.source.durationMs) {
            return TimelineError.INVALID_SOURCE_RANGE
        }
        return null
    }

    private fun canonicalEffects(clip: AudioClip): ClipEffects = clip.effects
        .copy(
            pitchSemitones = clip.effects.normalizedPitchSemitones,
            speed = clip.effects.normalizedSpeed,
        )
        .clampedForDuration(clip.timelineDurationMs)

    private data class ClipLocation(
        val trackIndex: Int,
        val clipIndex: Int,
        val track: EditorTrack,
        val clip: AudioClip,
    )

    private fun EditorSession.findClip(clipId: String): ClipLocation? {
        for ((trackIndex, track) in tracks.withIndex()) {
            val clipIndex = track.clips.indexOfFirst { it.id == clipId }
            if (clipIndex >= 0) return ClipLocation(trackIndex, clipIndex, track, track.clips[clipIndex])
        }
        return null
    }

    private fun EditorSession.replaceClip(location: ClipLocation, clip: AudioClip): EditorSession {
        val tracks = tracks.toMutableList()
        val clips = location.track.clips.toMutableList()
        clips[location.clipIndex] = clip
        tracks[location.trackIndex] = location.track.copy(clips = clips)
        return copy(tracks = tracks)
    }

    private fun uniqueClipId(base: String, session: EditorSession, vararg excluded: String): String {
        val used = session.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .map { it.id }
            .filterNot { it in excluded }
            .toHashSet()
        if (base !in used) return base
        var suffix = 2
        while ("$base-$suffix" in used) suffix++
        return "$base-$suffix"
    }
}
