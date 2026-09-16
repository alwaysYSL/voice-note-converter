package com.aistudio.voicenote.cvtr.editor.command

import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.TimelineError
import com.aistudio.voicenote.cvtr.editor.model.TimelineOperations
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import com.aistudio.voicenote.cvtr.editor.model.value
import java.util.ArrayDeque

/** Bounded snapshot history for rendered-audio edits; selection remains transient. */
internal class CommandHistory(
    initial: EditorSession,
    capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val maxUndoDepth: Int = capacity.coerceAtMost(MAX_CAPACITY)

    var session: EditorSession = initial
        private set

    /** Durable rendered-audio baseline. Selection/playhead/session ids never participate. */
    private var durableBaseline: EditorSession = initial

    private val undo = ArrayDeque<EditorSession>()
    private val redo = ArrayDeque<EditorSession>()

    val canUndo: Boolean
        get() = undo.isNotEmpty()

    val canRedo: Boolean
        get() = redo.isNotEmpty()

    val undoDepth: Int
        get() = undo.size

    val redoDepth: Int
        get() = redo.size

    /** Marks the current timeline as the durable baseline after an explicit draft save. */
    @Synchronized
    fun markClean(draftId: String = session.draftId ?: session.id): EditorSession {
        durableBaseline = session.copy(id = draftId, draftId = draftId, dirty = false)
        session = session.copy(id = draftId, draftId = draftId, dirty = false)
        return session
    }

    /** All snapshots that can still become audible through the current history branch. */
    @Synchronized
    fun referencedSessions(): List<EditorSession> = buildList {
        add(session)
        addAll(undo)
        addAll(redo)
    }

    /** Applies a command and records the prior immutable snapshot only when accepted. */
    @Synchronized
    fun execute(command: EditorCommand): TimelineResult {
        if (command is SelectClipCommand) return updateSelection(command.clipId)
        val result = command.applyTo(session)
        if (result is TimelineResult.Accepted) {
            undo.addLast(session)
            trimToCapacity(undo)
            session = withDirtyBaseline(result.value.copy(revision = session.revision + 1))
            redo.clear()
        }
        return result
    }

    /** Applies a command from an explicit baseline snapshot, rejecting stale revisions. */
    @Synchronized
    fun executeFromBaseline(
        baseline: EditorSession,
        command: EditorCommand,
    ): TimelineResult {
        if (!sameRevision(session, baseline)) {
            return TimelineResult.Rejected(TimelineError.STALE_BASELINE)
        }

        return when (val result = command.applyTo(baseline)) {
            is TimelineResult.Rejected -> result
            is TimelineResult.Accepted -> {
                undo.addLast(session)
                trimToCapacity(undo)
                session = withDirtyBaseline(result.value.copy(revision = session.revision + 1))
                redo.clear()
                TimelineResult.Accepted(session)
            }
        }
    }

    private fun sameRevision(left: EditorSession, right: EditorSession): Boolean =
        left.revision == right.revision && sameRenderedContent(left, right)

    /** Restores the latest snapshot, or leaves the current session unchanged if empty. */
    @Synchronized
    fun undo(): EditorSession {
        if (undo.isEmpty()) return session
        redo.addLast(session)
        session = withDirtyBaseline(restoreTransientState(undo.removeLast()))
        return session
    }

    /** Reapplies the latest undone snapshot, or leaves the current session unchanged if empty. */
    @Synchronized
    fun redo(): EditorSession {
        if (redo.isEmpty()) return session
        undo.addLast(session)
        trimToCapacity(undo)
        session = withDirtyBaseline(restoreTransientState(redo.removeLast()))
        return session
    }

    /** Updates selection without recording a snapshot or clearing the redo branch. */
    @Synchronized
    fun updateSelection(clipId: String?): TimelineResult {
        if (clipId != null && session.tracks.none { track -> track.clips.any { it.id == clipId } }) {
            return TimelineResult.Rejected(TimelineError.MISSING_ID)
        }
        session = TimelineOperations.validate(session.copy(selectedClipId = clipId)).let {
            when (it) {
                is TimelineResult.Accepted -> it.value
                is TimelineResult.Rejected -> return it
            }
        }.let(::withDirtyBaseline)
        return TimelineResult.Accepted(session)
    }

    private fun withDirtyBaseline(candidate: EditorSession): EditorSession =
        candidate.copy(dirty = !sameRenderedContent(candidate, durableBaseline))

    private fun restoreTransientState(snapshot: EditorSession): EditorSession {
        val selected = session.selectedClipId?.takeIf { clipId ->
            snapshot.tracks.any { track -> track.clips.any { it.id == clipId } }
        }
        return snapshot.copy(playheadMs = session.playheadMs, selectedClipId = selected)
    }

    private fun trimToCapacity(stack: ArrayDeque<EditorSession>) {
        while (stack.size > maxUndoDepth) stack.removeFirst()
    }

    private fun sameRenderedContent(left: EditorSession, right: EditorSession): Boolean {
        if (left.exportPreset != right.exportPreset || left.tracks.size != right.tracks.size) return false
        return left.tracks.zip(right.tracks).all { (leftTrack, rightTrack) ->
            leftTrack.id == rightTrack.id &&
                leftTrack.name == rightTrack.name &&
                leftTrack.volume == rightTrack.volume &&
                leftTrack.muted == rightTrack.muted &&
                leftTrack.clips.size == rightTrack.clips.size &&
                leftTrack.clips.zip(rightTrack.clips).all { (leftClip, rightClip) ->
                    leftClip.id == rightClip.id &&
                        leftClip.source == rightClip.source &&
                        leftClip.sourceStartMs == rightClip.sourceStartMs &&
                        leftClip.sourceEndMs == rightClip.sourceEndMs &&
                        leftClip.timelineStartMs == rightClip.timelineStartMs &&
                        leftClip.effects == rightClip.effects
                }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 50
        const val MAX_CAPACITY = 50
    }
}
