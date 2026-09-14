package com.aistudio.voicenote.cvtr.editor.command

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHistoryTest {
    @Test
    fun `undo restores a ripple-deleted clip and shifted siblings`() {
        val history = CommandHistory(initialSession())
        val before = history.session

        history.execute(DeleteClipCommand("track-1", "clip-b", ripple = true))
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
}
