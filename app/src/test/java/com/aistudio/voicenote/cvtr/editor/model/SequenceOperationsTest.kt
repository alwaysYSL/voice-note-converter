package com.aistudio.voicenote.cvtr.editor.model

import com.aistudio.voicenote.cvtr.editor.command.AppendClipsCommand
import com.aistudio.voicenote.cvtr.editor.command.CommandHistory
import com.aistudio.voicenote.cvtr.editor.command.ReorderClipCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceOperationsTest {

    private fun dummySource(id: String, durationMs: Long = 10_000L) =
        AudioSourceRef("uri://$id", durationMs)

    private fun dummyClip(id: String, durationMs: Long = 5_000L, speed: Float = 1.0f): SequenceClip =
        SequenceClip(
            id = id,
            source = dummySource(id, durationMs),
            sourceStartMs = 0L,
            sourceEndMs = durationMs,
            effects = ClipEffects(speed = speed),
        )

    @Test
    fun `sequence clip duration correctly scales with playback speed`() {
        val normalClip = dummyClip("clip-1", durationMs = 10_000L, speed = 1.0f)
        assertEquals(10_000L, normalClip.durationMs)

        val fastClip = dummyClip("clip-2", durationMs = 10_000L, speed = 2.0f)
        assertEquals(5_000L, fastClip.durationMs)

        val slowClip = dummyClip("clip-3", durationMs = 10_000L, speed = 0.5f)
        assertEquals(20_000L, slowClip.durationMs)
    }

    @Test
    fun `sequence projection toRenderSession creates single track with contiguous timeline`() {
        val seq = SequenceSession(
            id = "seq-test",
            clips = listOf(
                dummyClip("clip-a", durationMs = 3_000L),
                dummyClip("clip-b", durationMs = 5_000L),
                dummyClip("clip-c", durationMs = 2_000L),
            ),
        )

        val rendered = seq.toRenderSession()

        assertEquals(1, rendered.tracks.size)
        val trackClips = rendered.tracks.single().clips
        assertEquals(3, trackClips.size)

        // Clip A: 0 -> 3000ms
        assertEquals(0L, trackClips[0].timelineStartMs)
        assertEquals(3_000L, trackClips[0].timelineEndMs)

        // Clip B: 3000 -> 8000ms
        assertEquals(3_000L, trackClips[1].timelineStartMs)
        assertEquals(8_000L, trackClips[1].timelineEndMs)

        // Clip C: 8000 -> 10000ms
        assertEquals(8_000L, trackClips[2].timelineStartMs)
        assertEquals(10_000L, trackClips[2].timelineEndMs)

        assertEquals(10_000L, seq.totalDurationMs)
    }

    @Test
    fun `multitrack legacy session flattens correctly to sequence`() {
        val legacySession = EditorSession(
            id = "legacy-session",
            tracks = listOf(
                EditorTrack(
                    id = "track-1",
                    name = "Track 1",
                    clips = listOf(
                        AudioClip(
                            id = "clip-1",
                            source = dummySource("source-1", 4_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 4_000L,
                            timelineStartMs = 0L,
                        )
                    )
                ),
                EditorTrack(
                    id = "track-2",
                    name = "Track 2",
                    clips = listOf(
                        AudioClip(
                            id = "clip-2",
                            source = dummySource("source-2", 6_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 6_000L,
                            timelineStartMs = 4_000L,
                        )
                    )
                ),
            )
        )

        val seq = legacySession.toSequenceSession()
        assertEquals(2, seq.clips.size)
        assertEquals("clip-1", seq.clips[0].id)
        assertEquals("clip-2", seq.clips[1].id)

        val flattened = seq.toRenderSession()
        assertEquals(1, flattened.tracks.size)
        assertEquals(2, flattened.tracks.single().clips.size)
        assertEquals(0L, flattened.tracks.single().clips[0].timelineStartMs)
        assertEquals(4_000L, flattened.tracks.single().clips[1].timelineStartMs)
        assertEquals(10_000L, flattened.tracks.single().clips[1].timelineEndMs)
    }

    @Test
    fun `reordering clips updates sequence positions and re-projects without overlap`() {
        val seq = SequenceSession(
            id = "seq-test",
            clips = listOf(
                dummyClip("clip-a", durationMs = 2_000L),
                dummyClip("clip-b", durationMs = 4_000L),
                dummyClip("clip-c", durationMs = 6_000L),
            ),
        )

        // Move clip-c (originally index 2) to index 0: [clip-c, clip-a, clip-b]
        val reordered = seq.reorderClip("clip-c", 0)
        assertEquals(listOf("clip-c", "clip-a", "clip-b"), reordered.clips.map { it.id })

        val rendered = reordered.toRenderSession()
        val clips = rendered.tracks.single().clips

        // Clip C: 0 -> 6000ms
        assertEquals(0L, clips[0].timelineStartMs)
        assertEquals(6_000L, clips[0].timelineEndMs)

        // Clip A: 6000 -> 8000ms
        assertEquals(6_000L, clips[1].timelineStartMs)
        assertEquals(8_000L, clips[1].timelineEndMs)

        // Clip B: 8000 -> 12000ms
        assertEquals(8_000L, clips[2].timelineStartMs)
        assertEquals(12_000L, clips[2].timelineEndMs)
    }

    @Test
    fun `batch append creates contiguous sequence and increments revision`() {
        val initialSession = SequenceSession(
            id = "seq-test",
            clips = listOf(dummyClip("clip-1", durationMs = 3_000L)),
        ).toRenderSession()

        val history = CommandHistory(initialSession)

        val newClips = listOf(
            AudioClip(
                id = "clip-2",
                source = dummySource("src-2", 4_000L),
                sourceStartMs = 0L,
                sourceEndMs = 4_000L,
                timelineStartMs = 0L,
            ),
            AudioClip(
                id = "clip-3",
                source = dummySource("src-3", 5_000L),
                sourceStartMs = 0L,
                sourceEndMs = 5_000L,
                timelineStartMs = 0L,
            ),
        )

        val result = history.execute(AppendClipsCommand(newClips))
        assertTrue(result is TimelineResult.Accepted)

        val updated = history.session
        assertEquals(1, updated.tracks.size)
        val clips = updated.tracks.single().clips
        assertEquals(3, clips.size)

        assertEquals("clip-1", clips[0].id)
        assertEquals(0L, clips[0].timelineStartMs)
        assertEquals(3_000L, clips[0].timelineEndMs)

        assertEquals("clip-2", clips[1].id)
        assertEquals(3_000L, clips[1].timelineStartMs)
        assertEquals(7_000L, clips[1].timelineEndMs)

        assertEquals("clip-3", clips[2].id)
        assertEquals(7_000L, clips[2].timelineStartMs)
        assertEquals(12_000L, clips[2].timelineEndMs)

        // One command = one revision increment
        assertEquals(1L, updated.revision)
    }

    @Test
    fun `reorder command executes atomically via command history`() {
        val initialSession = SequenceSession(
            id = "seq-test",
            clips = listOf(
                dummyClip("clip-1", durationMs = 1_000L),
                dummyClip("clip-2", durationMs = 2_000L),
            ),
        ).toRenderSession()

        val history = CommandHistory(initialSession)
        val result = history.execute(ReorderClipCommand("clip-2", 0))
        assertTrue(result is TimelineResult.Accepted)

        val clips = history.session.tracks.single().clips
        assertEquals("clip-2", clips[0].id)
        assertEquals(0L, clips[0].timelineStartMs)
        assertEquals(2_000L, clips[0].timelineEndMs)

        assertEquals("clip-1", clips[1].id)
        assertEquals(2_000L, clips[1].timelineStartMs)
        assertEquals(3_000L, clips[1].timelineEndMs)

        // Undo restores original order
        history.undo()
        val undoneClips = history.session.tracks.single().clips
        assertEquals("clip-1", undoneClips[0].id)
        assertEquals("clip-2", undoneClips[1].id)
    }

    @Test
    fun `batch append rejects when total duration exceeds MAX_TIMELINE_MS`() {
        val initialSession = SequenceSession(
            id = "seq-test",
            clips = listOf(dummyClip("clip-1", durationMs = MAX_TIMELINE_MS - 1_000L)),
        ).toRenderSession()

        val history = CommandHistory(initialSession)
        val newClips = listOf(
            AudioClip(
                id = "clip-2",
                source = dummySource("src-2", 2_000L),
                sourceStartMs = 0L,
                sourceEndMs = 2_000L,
                timelineStartMs = 0L,
            )
        )

        val result = history.execute(AppendClipsCommand(newClips))
        assertTrue(result is TimelineResult.Rejected)
        assertEquals(TimelineError.DURATION_LIMIT, (result as TimelineResult.Rejected).reason)
    }
}
