package com.aistudio.voicenote.cvtr.editor.command

import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import com.aistudio.voicenote.cvtr.editor.model.value
import java.util.ArrayDeque

/** Bounded snapshot history for rendered-audio edits and explicit selection changes. */
internal class CommandHistory(
    initial: EditorSession,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    var session: EditorSession = initial
        private set

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

    /** Applies a command and records the prior immutable snapshot only when accepted. */
    fun execute(command: EditorCommand): TimelineResult {
        val result = command.applyTo(session)
        if (result is TimelineResult.Accepted) {
            undo.addLast(session)
            trimToCapacity(undo)
            session = result.value
            redo.clear()
        }
        return result
    }

    /** Restores the latest snapshot, or leaves the current session unchanged if empty. */
    fun undo(): EditorSession {
        if (undo.isEmpty()) return session
        redo.addLast(session)
        session = undo.removeLast()
        return session
    }

    /** Reapplies the latest undone snapshot, or leaves the current session unchanged if empty. */
    fun redo(): EditorSession {
        if (redo.isEmpty()) return session
        undo.addLast(session)
        trimToCapacity(undo)
        session = redo.removeLast()
        return session
    }

    private fun trimToCapacity(stack: ArrayDeque<EditorSession>) {
        while (stack.size > capacity) stack.removeFirst()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 50
    }
}
