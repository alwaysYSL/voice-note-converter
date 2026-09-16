package com.aistudio.voicenote.cvtr.editor.command

import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.CleanupEffectConfig
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import com.aistudio.voicenote.cvtr.editor.model.TimelineError
import com.aistudio.voicenote.cvtr.editor.model.TimelineOperations
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult

/** A single immutable edit that can be applied to an editor session. */
internal interface EditorCommand {
    fun applyTo(session: EditorSession): TimelineResult
}

internal data class AddTrackCommand(
    val track: EditorTrack,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.addTrack(session, track)
}

internal data class RemoveTrackCommand(
    val trackId: String,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult {
        if (session.tracks.none { it.id == trackId }) {
            return TimelineResult.Rejected(TimelineError.MISSING_ID)
        }

        val tracks = session.tracks.filterNot { it.id == trackId }
        val remainingClipIds = tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .map { it.id }
            .toSet()
        val selectedClipId = session.selectedClipId?.takeIf { it in remainingClipIds }
        return validateRenderedMutation(
            session.copy(
                tracks = tracks,
                selectedClipId = selectedClipId,
            ),
        )
    }
}

internal data class SplitClipCommand(
    val clipId: String,
    val splitTimelineMs: Long,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.splitClip(session, clipId, splitTimelineMs)
}

internal data class TrimClipCommand(
    val clipId: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.trimClip(session, clipId, sourceStartMs, sourceEndMs)
}

internal data class ReplaceClipSourceCommand(
    val clipId: String,
    val source: com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = source.durationMs,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.replaceClipSource(session, clipId, source, sourceStartMs, sourceEndMs)
}

internal data class MoveClipCommand(
    val clipId: String,
    val timelineStartMs: Long,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.moveClip(session, clipId, timelineStartMs)
}

internal data class ReorderClipCommand(
    val clipId: String,
    val targetIndex: Int,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.reorderClip(session, clipId, targetIndex)
}

internal data class AppendClipsCommand(
    val newClips: List<com.aistudio.voicenote.cvtr.editor.model.AudioClip>,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.appendClips(session, newClips)
}

internal data class DeleteClipCommand(
    val trackId: String,
    val clipId: String,
    val ripple: Boolean = true,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult {
        val track = session.tracks.firstOrNull { it.id == trackId }
            ?: return TimelineResult.Rejected(TimelineError.MISSING_ID)
        if (track.clips.none { it.id == clipId }) {
            return TimelineResult.Rejected(TimelineError.MISSING_ID)
        }
        return TimelineOperations.deleteClip(session, clipId, ripple = ripple)
    }
}

internal data class SetClipFadeCommand(
    val clipId: String,
    val fadeInMs: Long,
    val fadeOutMs: Long,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.setClipFade(session, clipId, fadeInMs, fadeOutMs)
}

internal data class SetClipPitchCommand(
    val clipId: String,
    val pitchSemitones: Float,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.setClipPitch(session, clipId, pitchSemitones)
}

internal data class SetClipSpeedCommand(
    val clipId: String,
    val speed: Float,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.setClipSpeed(session, clipId, speed)
}

internal data class SetExportPresetCommand(
    val preset: ExportPreset,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.validate(session.copy(exportPreset = preset, dirty = true))
}

internal data class SetTrackVolumeCommand(
    val trackId: String,
    val volume: Float,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.setTrackVolume(session, trackId, volume)
}

internal data class SetTrackMutedCommand(
    val trackId: String,
    val muted: Boolean,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult {
        val trackIndex = session.tracks.indexOfFirst { it.id == trackId }
        if (trackIndex < 0) return TimelineResult.Rejected(TimelineError.MISSING_ID)

        val tracks = session.tracks.toMutableList()
        tracks[trackIndex] = tracks[trackIndex].copy(muted = muted)
        return validateRenderedMutation(session.copy(tracks = tracks))
    }
}

/** Changes only selection; playhead and other view state deliberately remain outside history. */
internal data class SelectClipCommand(
    val clipId: String?,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult {
        if (clipId != null && session.tracks.none { track -> track.clips.any { it.id == clipId } }) {
            return TimelineResult.Rejected(TimelineError.MISSING_ID)
        }
        return TimelineOperations.validate(session.copy(selectedClipId = clipId))
    }
}

/** Applies validation while marking a command as a rendered-audio mutation. */
private fun validateRenderedMutation(session: EditorSession): TimelineResult =
    TimelineOperations.validate(session.copy(dirty = true))

internal data class ApplyProcessedSourceCommand(
    val clipId: String,
    val processedCacheKey: String,
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult {
        return TimelineOperations.applyProcessedSource(session, clipId, processedCacheKey)
    }
}

/** Applies a completed cleanup batch as one undoable mutation. */
internal data class ApplyProcessedSourcesCommand(
    val processedCacheKeys: Map<String, String>,
    val cleanupConfigs: Map<String, CleanupEffectConfig> = emptyMap(),
) : EditorCommand {
    override fun applyTo(session: EditorSession): TimelineResult =
        TimelineOperations.applyProcessedSources(session, processedCacheKeys, cleanupConfigs)
}
