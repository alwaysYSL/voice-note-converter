package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.editor.command.CommandHistory
import com.aistudio.voicenote.cvtr.editor.command.MoveClipCommand
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.MoveGestureTracker
import com.aistudio.voicenote.cvtr.editor.model.TimelineError
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EditorGestureIntegrationTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `drag distance is based on immutable origin`() {
        val originMs = 1_000L
        val pxPerMs = 0.10f
        val tracker = MoveGestureTracker(originMs, pxPerMs)

        tracker.addDeltaPx(10f)
        tracker.addDeltaPx(10f)
        tracker.addDeltaPx(10f)

        // 1000 + 30px / 0.10 = 1300ms, proving linear accumulation over fixed baseline
        assertEquals(1_300L, tracker.previewStartMs)
    }

    @Test
    fun `one drag produces one undo entry after 50 pointer updates`() = runTest {
        val vm = createEditorViewModel()
        vm.awaitReady()

        vm.importTrack(Uri.parse("content://media/clip_b.ogg"))
        // Wait until two tracks exist
        while (vm.uiState.value.committedSession.tracks.size < 2) {
            kotlinx.coroutines.delay(10L)
        }

        val clipBId = vm.uiState.value.committedSession.tracks[1].clips.first().id
        val initialUndoDepth = vm.debugUndoDepth()

        vm.dispatch(EditorIntent.BeginMove(clipBId))
        repeat(50) { i ->
            vm.dispatch(EditorIntent.UpdateMove(clipBId, 2_000L + (i * 20L)))
        }
        vm.dispatch(EditorIntent.CommitMove(clipBId))

        assertEquals(initialUndoDepth + 1, vm.debugUndoDepth())
    }

    @Test
    fun `direct drag on clip B modifies only clip B even if clip A is selected`() = runTest {
        val vm = createEditorViewModel()
        vm.awaitReady()

        vm.importTrack(Uri.parse("content://media/clip_b.ogg"))
        while (vm.uiState.value.committedSession.tracks.size < 2) {
            kotlinx.coroutines.delay(10L)
        }

        val clipAId = vm.uiState.value.committedSession.tracks[0].clips.first().id
        val clipBId = vm.uiState.value.committedSession.tracks[1].clips.first().id

        // Explicitly select Clip A
        vm.dispatch(EditorIntent.SelectClip(clipAId))
        assertEquals(clipAId, vm.uiState.value.committedSession.selectedClipId)

        val clipAOriginalStart = vm.uiState.value.committedSession.tracks[0].clips.first().timelineStartMs

        // Directly move Clip B without selecting it first
        vm.dispatch(EditorIntent.BeginMove(clipBId))
        vm.dispatch(EditorIntent.UpdateMove(clipBId, 5_000L))
        vm.dispatch(EditorIntent.CommitMove(clipBId))

        val updatedSession = vm.uiState.value.committedSession
        val updatedClipA = updatedSession.tracks[0].clips.first { it.id == clipAId }
        val updatedClipB = updatedSession.tracks[1].clips.first { it.id == clipBId }

        assertEquals(clipAOriginalStart, updatedClipA.timelineStartMs)
        assertEquals(5_000L, updatedClipB.timelineStartMs)
    }

    @Test
    fun `transient move updates visible session without modifying committed session`() = runTest {
        val vm = createEditorViewModel()
        vm.awaitReady()

        val clipAId = vm.uiState.value.committedSession.tracks[0].clips.first().id
        val originalStartMs = vm.uiState.value.committedSession.tracks[0].clips.first().timelineStartMs

        vm.dispatch(EditorIntent.BeginMove(clipAId))
        vm.dispatch(EditorIntent.UpdateMove(clipAId, 3_000L))

        // Visible session should reflect preview
        val visibleClip = vm.uiState.value.visibleSession.tracks[0].clips.first { it.id == clipAId }
        assertEquals(3_000L, visibleClip.timelineStartMs)

        // Committed session should remain unchanged
        val committedClip = vm.uiState.value.committedSession.tracks[0].clips.first { it.id == clipAId }
        assertEquals(originalStartMs, committedClip.timelineStartMs)

        // Cancel gesture should revert visible session
        vm.dispatch(EditorIntent.CancelGesture)
        val revertedClip = vm.uiState.value.visibleSession.tracks[0].clips.first { it.id == clipAId }
        assertEquals(originalStartMs, revertedClip.timelineStartMs)
    }

    @Test
    fun `command history rejects execution against stale baseline`() {
        val initialSession = EditorSession(
            id = "session-1",
            tracks = listOf(
                EditorTrack(
                    id = "track-1",
                    name = "Track 1",
                    clips = listOf(
                        AudioClip(
                            id = "clip-1",
                            source = AudioSourceRef("uri-1", 10_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 5_000L,
                            timelineStartMs = 0L,
                        )
                    )
                )
            ),
            revision = 0L,
        )
        val history = CommandHistory(initialSession)

        // Mutate session to increment revision
        val moveResult = history.execute(MoveClipCommand("clip-1", 1_000L))
        assertTrue(moveResult is TimelineResult.Accepted)
        assertEquals(1L, history.session.revision)

        // Attempting to execute from old baseline (revision 0) should be rejected
        val staleResult = history.executeFromBaseline(initialSession, MoveClipCommand("clip-1", 2_000L))
        assertTrue(staleResult is TimelineResult.Rejected)
        assertEquals(TimelineError.STALE_BASELINE, (staleResult as TimelineResult.Rejected).reason)
    }

    private fun createEditorViewModel(): EditorViewModel {
        return EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Converted(
                resultUri = Uri.parse("content://media/clip_a.ogg"),
                originalUri = null,
                displayName = "clip_a.ogg"
            ),
            sourceAnalyzer = { uri ->
                AudioSourceInfo(
                    displayName = uri.lastPathSegment.orEmpty(),
                    durationMs = 10_000L
                )
            },
            waveformLoader = { emptyList() }
        )
    }
}
