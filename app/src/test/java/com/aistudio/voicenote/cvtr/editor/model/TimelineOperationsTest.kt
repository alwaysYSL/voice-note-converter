package com.aistudio.voicenote.cvtr.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOperationsTest {
    @Test
    fun `session rejects a sixth track`() {
        val session = sessionWithTracks(5)

        val result = TimelineOperations.addTrack(session, emptyTrack("six"))

        assertEquals(TimelineError.TRACK_LIMIT, (result as TimelineResult.Rejected).reason)
    }

    @Test
    fun `session rejects clips beyond the five minute timeline`() {
        val session = EditorSession("session", emptyList())
        val longClip = clip(sourceStartMs = 0, sourceEndMs = 10_000, timelineStartMs = 300_000)

        val result = TimelineOperations.addTrack(session, EditorTrack("track", "Track", clips = listOf(longClip)))

        assertEquals(TimelineError.DURATION_LIMIT, (result as TimelineResult.Rejected).reason)
    }

    @Test
    fun `same track overlap is rejected while different tracks may overlap`() {
        val first = clip(id = "first", sourceStartMs = 0, sourceEndMs = 5_000, timelineStartMs = 0)
        val overlap = clip(id = "overlap", sourceStartMs = 0, sourceEndMs = 5_000, timelineStartMs = 4_000)
        val session = sessionWith(first)

        val sameTrack = TimelineOperations.addClip(session, "track", overlap)

        assertEquals(TimelineError.OVERLAP, (sameTrack as TimelineResult.Rejected).reason)
        val differentTrack = TimelineOperations.addTrack(session, EditorTrack("other", "Other", clips = listOf(overlap)))
        assertTrue(differentTrack is TimelineResult.Accepted)
    }

    @Test
    fun `split preserves source mapping and resets inner fades`() {
        val clip = clip(
            sourceStartMs = 1_000,
            sourceEndMs = 11_000,
            timelineStartMs = 5_000,
            effects = ClipEffects(fadeInMs = 1_000, fadeOutMs = 1_000),
        )

        val updated = TimelineOperations.splitClip(sessionWith(clip), clip.id, 9_000).value
        val clips = updated.tracks.single().clips

        assertEquals(listOf(1_000L to 5_000L, 5_000L to 11_000L), clips.map { it.sourceStartMs to it.sourceEndMs })
        assertEquals(0L, clips[0].effects.fadeOutMs)
        assertEquals(0L, clips[1].effects.fadeInMs)
        assertEquals(1_000L, clips[0].effects.fadeInMs)
        assertEquals(1_000L, clips[1].effects.fadeOutMs)
    }

    @Test
    fun `trim changes source range without moving clip`() {
        val original = clip(sourceStartMs = 1_000, sourceEndMs = 11_000, timelineStartMs = 5_000)

        val updated = TimelineOperations.trimClip(sessionWith(original), original.id, 3_000, 8_000).value
        val trimmed = updated.tracks.single().clips.single()

        assertEquals(3_000L, trimmed.sourceStartMs)
        assertEquals(8_000L, trimmed.sourceEndMs)
        assertEquals(5_000L, trimmed.timelineStartMs)
        assertEquals(5_000L, trimmed.timelineDurationMs)
    }

    @Test
    fun `move changes timeline start and rejects overlap`() {
        val first = clip(id = "first", sourceStartMs = 0, sourceEndMs = 5_000, timelineStartMs = 0)
        val second = clip(id = "second", sourceStartMs = 0, sourceEndMs = 5_000, timelineStartMs = 10_000)
        val session = sessionWith(first, second)

        val moved = TimelineOperations.moveClip(session, second.id, 20_000).value
        assertEquals(20_000L, moved.tracks.single().clips.single { it.id == second.id }.timelineStartMs)
        val rejected = TimelineOperations.moveClip(session, second.id, 2_000)
        assertEquals(TimelineError.OVERLAP, (rejected as TimelineResult.Rejected).reason)
    }

    @Test
    fun `ripple delete closes only the active track gap`() {
        val first = clip(id = "first", sourceStartMs = 0, sourceEndMs = 1_000, timelineStartMs = 0)
        val later = clip(id = "later", sourceStartMs = 0, sourceEndMs = 1_000, timelineStartMs = 3_000)
        val other = clip(id = "other", sourceStartMs = 0, sourceEndMs = 1_000, timelineStartMs = 3_000)
        val session = EditorSession(
            "session",
            listOf(
                EditorTrack("track", "Track", clips = listOf(first, later)),
                EditorTrack("other-track", "Other", clips = listOf(other)),
            ),
        )

        val updated = TimelineOperations.deleteClip(session, first.id).value

        assertEquals(2_000L, updated.tracks[0].clips.single().timelineStartMs)
        assertEquals(3_000L, updated.tracks[1].clips.single().timelineStartMs)
    }

    @Test
    fun `gap delete preserves later clip positions`() {
        val first = clip(id = "first", sourceStartMs = 0, sourceEndMs = 1_000, timelineStartMs = 0)
        val later = clip(id = "later", sourceStartMs = 0, sourceEndMs = 1_000, timelineStartMs = 3_000)
        val session = sessionWith(first, later)

        val updated = TimelineOperations.deleteClip(session, first.id, ripple = false).value

        assertEquals(3_000L, updated.tracks.single().clips.single().timelineStartMs)
    }

    @Test
    fun `volume is clamped to the supported track range`() {
        val session = sessionWithTracks(1)

        val high = TimelineOperations.setTrackVolume(session, "track-1", 9f).value
        val low = TimelineOperations.setTrackVolume(session, "track-1", -2f).value

        assertEquals(1.5f, high.tracks.single().volume)
        assertEquals(0f, low.tracks.single().volume)
    }

    @Test
    fun `effects clamp pitch speed and fades`() {
        val effects = ClipEffects(fadeInMs = 20_000, fadeOutMs = 20_000, pitchSemitones = 20f, speed = 20f)
        val clip = clip(sourceStartMs = 0, sourceEndMs = 2_000, effects = effects)
        val normalized = TimelineOperations.validate(sessionWith(clip)).value.tracks.single().clips.single()

        assertEquals(4f, normalized.effects.pitchSemitones)
        assertEquals(2f, normalized.effects.speed)
        assertEquals(500L, normalized.effects.fadeInMs)
        assertEquals(500L, normalized.effects.fadeOutMs)
        assertEquals(1_000L, normalized.timelineDurationMs)
    }

    @Test
    fun `missing ids are rejected`() {
        val session = sessionWithTracks(1)

        assertEquals(TimelineError.MISSING_ID, (TimelineOperations.splitClip(session, "missing", 1_000) as TimelineResult.Rejected).reason)
        assertEquals(TimelineError.MISSING_ID, (TimelineOperations.trimClip(session, "missing", 0, 1_000) as TimelineResult.Rejected).reason)
        assertEquals(TimelineError.MISSING_ID, (TimelineOperations.moveClip(session, "missing", 1_000) as TimelineResult.Rejected).reason)
        assertEquals(TimelineError.MISSING_ID, (TimelineOperations.deleteClip(session, "missing") as TimelineResult.Rejected).reason)
        assertEquals(TimelineError.MISSING_ID, (TimelineOperations.setTrackVolume(session, "missing", 1f) as TimelineResult.Rejected).reason)
    }

    @Test
    fun `invalid source ranges are rejected`() {
        val invalid = clip(sourceStartMs = 8_000, sourceEndMs = 70_000, timelineStartMs = 0)

        val result = TimelineOperations.addTrack(EditorSession("session", emptyList()), EditorTrack("track", "Track", clips = listOf(invalid)))

        assertEquals(TimelineError.INVALID_SOURCE_RANGE, (result as TimelineResult.Rejected).reason)
    }

    private fun sessionWithTracks(count: Int): EditorSession = EditorSession(
        id = "session",
        tracks = (1..count).map { emptyTrack("track-$it") },
    )

    private fun sessionWith(vararg clips: AudioClip): EditorSession = EditorSession(
        id = "session",
        tracks = listOf(EditorTrack("track", "Track", clips = clips.toList())),
    )

    private fun emptyTrack(id: String): EditorTrack = EditorTrack(id = id, name = id)

    private fun clip(
        id: String = "clip",
        sourceStartMs: Long,
        sourceEndMs: Long,
        timelineStartMs: Long = 0,
        effects: ClipEffects = ClipEffects(),
    ): AudioClip = AudioClip(
        id = id,
        source = AudioSourceRef(uri = "content://source", durationMs = 60_000),
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs,
        timelineStartMs = timelineStartMs,
        effects = effects,
    )
}
