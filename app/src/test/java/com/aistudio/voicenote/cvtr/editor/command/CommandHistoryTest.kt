package com.aistudio.voicenote.cvtr.editor.command

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.TimelineOperations
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import com.aistudio.voicenote.cvtr.editor.model.value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHistoryTest {
    @Test
    fun `undo restores a ripple-deleted clip and shifted siblings`() {
        val history = CommandHistory(initialSession())
        val before = history.session

        val result = history.execute(DeleteClipCommand("track-1", "clip-b", ripple = true))
        assertTrue(result is TimelineResult.Accepted)
        assertTrue(history.canUndo)
        history.undo()

        assertEquals(before, history.session)
    }

    @Test
    fun `new command after undo clears redo and history is capped`() {
        val history = CommandHistory(initialSession(), capacity = 50)

        repeat(55) { history.execute(SetTrackVolumeCommand("track-1", 0.5f + it / 200f)) }
        history.undo()
        history.execute(SetTrackMutedCommand("track-1", true))

        assertFalse(history.canRedo)
        assertEquals(50, history.undoDepth)
    }

    @Test
    fun `capacity above fifty is capped at fifty undo snapshots`() {
        val history = CommandHistory(initialSession(), capacity = 51)

        repeat(51) { history.execute(SetTrackVolumeCommand("track-1", 0.5f + it / 200f)) }

        assertEquals(50, history.undoDepth)
    }

    @Test
    fun `undo and redo preserve the current transient playhead`() {
        val history = CommandHistory(initialSession().copy(playheadMs = 1_000L))

        history.execute(AudioAndPlayheadCommand(0.75f, 9_000L))
        history.undo()
        assertEquals(9_000L, history.session.playheadMs)

        history.redo()
        assertEquals(9_000L, history.session.playheadMs)
    }

    @Test
    fun `redo reapplies an undone command`() {
        val history = CommandHistory(initialSession())
        history.execute(SetTrackMutedCommand("track-1", true))
        val changed = history.session

        history.undo()
        assertTrue(history.canRedo)
        history.redo()

        assertEquals(changed, history.session)
    }

    @Test
    fun `rejected command does not change session or history`() {
        val history = CommandHistory(initialSession())
        val before = history.session

        val result = history.execute(SetTrackVolumeCommand("missing", 1f))

        assertEquals(before, history.session)
        assertEquals(0, history.undoDepth)
        assertTrue(result is com.aistudio.voicenote.cvtr.editor.model.TimelineResult.Rejected)
    }

    @Test
    fun `selection update is transient and does not consume history or clear redo`() {
        val history = CommandHistory(initialSession())

        history.execute(SetTrackMutedCommand("track-1", true))
        history.undo()
        assertTrue(history.canRedo)
        val depthBeforeSelection = history.undoDepth

        val result = history.execute(SelectClipCommand("clip-b"))

        assertTrue(result is TimelineResult.Accepted)
        assertEquals("clip-b", history.session.selectedClipId)
        assertEquals(depthBeforeSelection, history.undoDepth)
        assertTrue(history.canRedo)
    }

    @Test
    fun `undo back to saved content is clean while redo becomes dirty again`() {
        val history = CommandHistory(initialSession()).also { it.markClean("draft-1") }

        history.execute(SetTrackMutedCommand("track-1", true))
        assertTrue(history.session.dirty)

        history.undo()
        assertFalse(history.session.dirty)
        history.redo()
        assertTrue(history.session.dirty)

        // Selection and playhead are transient and do not dirty a durable baseline.
        history.updateSelection("clip-b")
        assertTrue(history.session.dirty)
        history.undo()
        history.updateSelection("clip-c")
        assertFalse(history.session.dirty)
    }

    @Test
    fun `caller-owned lists cannot mutate active session or history snapshots`() {
        val clips = mutableListOf(
            AudioClip("clip-a", AudioSourceRef("content://source", 60_000), 0, 1_000, 0),
        )
        val tracks = mutableListOf(EditorTrack("track-1", "Track 1", clips = clips))
        val history = CommandHistory(EditorSession("session", tracks))
        val initial = history.session

        clips.clear()
        tracks.clear()
        assertEquals(initial, history.session)

        history.execute(SetTrackMutedCommand("track-1", true))
        history.undo()
        assertEquals(initial, history.session)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (history.session.tracks as MutableList<EditorTrack>).clear()
        }
        assertEquals(initial, history.session)
    }

    @Test
    fun `core edit commands update their immutable session fields`() {
        val source = AudioSourceRef("content://source", durationMs = 60_000)
        val clip = AudioClip("clip-a", source, 0, 10_000, 0)
        val history = CommandHistory(
            EditorSession(
                "session",
                listOf(EditorTrack("track-1", "Track 1", clips = listOf(clip))),
            ),
        )

        history.execute(SetClipFadeCommand("clip-a", 1_000, 2_000))
        history.execute(SetClipPitchCommand("clip-a", 2f))
        history.execute(SetClipSpeedCommand("clip-a", 1.5f))
        history.execute(TrimClipCommand("clip-a", 1_000, 9_000))
        history.execute(MoveClipCommand("clip-a", 2_000))

        val updated = history.session.tracks.single().clips.single()
        assertEquals(1_000L, updated.sourceStartMs)
        assertEquals(9_000L, updated.sourceEndMs)
        assertEquals(2_000L, updated.timelineStartMs)
        assertEquals(1_000L, updated.effects.fadeInMs)
        assertEquals(2_000L, updated.effects.fadeOutMs)
        assertEquals(2f, updated.effects.pitchSemitones)
        assertEquals(1.5f, updated.effects.speed)
    }

    @Test
    fun `trim and split invalidate a processed source key`() {
        val source = AudioSourceRef("content://source", durationMs = 60_000)
        val clip = AudioClip(
            "clip-a",
            source,
            0,
            10_000,
            0,
            com.aistudio.voicenote.cvtr.editor.model.ClipEffects(processedCacheKey = "cached"),
        )
        val history = CommandHistory(
            EditorSession("session", listOf(EditorTrack("track-1", "Track 1", clips = listOf(clip))))
        )

        history.execute(TrimClipCommand("clip-a", 1_000, 9_000))
        assertEquals(null, history.session.tracks.single().clips.single().effects.processedCacheKey)
        history.undo()
        history.execute(SplitClipCommand("clip-a", 4_000))
        assertTrue(history.session.tracks.single().clips.all { it.effects.processedCacheKey == null })
    }

    private fun initialSession(): EditorSession {
        val source = AudioSourceRef("content://source", durationMs = 60_000)
        val first = AudioClip("clip-a", source, 0, 1_000, 0)
        val deleted = AudioClip("clip-b", source, 0, 1_000, 2_000)
        val later = AudioClip("clip-c", source, 0, 1_000, 4_000)
        return EditorSession(
            "session",
            listOf(EditorTrack("track-1", "Track 1", clips = listOf(first, deleted, later))),
        )
    }

    private data class AudioAndPlayheadCommand(
        val volume: Float,
        val playheadMs: Long,
    ) : EditorCommand {
        override fun applyTo(session: EditorSession): TimelineResult = when (
            val result = TimelineOperations.setTrackVolume(session, "track-1", volume)
        ) {
            is TimelineResult.Accepted -> TimelineResult.Accepted(result.value.copy(playheadMs = playheadMs))
            is TimelineResult.Rejected -> result
        }
    }
}
