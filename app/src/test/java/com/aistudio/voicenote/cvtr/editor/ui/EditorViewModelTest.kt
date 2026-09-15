package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.aistudio.voicenote.cvtr.editor.work.EditorExportWork
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `converted result becomes selected track-one clip`() = runTest {
        val resultUri = Uri.parse("content://media/result.ogg")
        val originalUri = Uri.parse("content://media/original.m4a")
        val vm = editorViewModel(
            EditorLaunchSource.Converted(resultUri, originalUri, "voice.ogg")
        )

        vm.awaitReady()

        assertEquals(1, vm.uiState.value.session.tracks.size)
        assertNotNull(vm.uiState.value.session.selectedClipId)
        assertEquals(
            originalUri.toString(),
            vm.uiState.value.session.tracks.single().clips.single().source.uri
        )
    }

    @Test
    fun `track import starts at playhead and rejects duration beyond limit`() = runTest {
        val vm = editorViewModel(
            EditorLaunchSource.Converted(
                Uri.parse("content://media/result.ogg"),
                null,
                "voice.ogg"
            ),
            durationMs = 290_000L
        )
        vm.awaitReady()
        vm.dispatch(EditorIntent.Seek(280_000L))
        vm.importTrack(Uri.parse("content://media/long.wav"))
        advanceUntilIdle()
        withTimeout(5_000L) {
            while (vm.uiState.value.message == null) delay(10L)
        }

        assertEquals(EditorMessage.TIMELINE_LIMIT, vm.uiState.value.message)
    }

    @Test
    fun `import after removing a non-last track gets a unique accepted track id`() = runTest {
        val vm = editorViewModel(
            EditorLaunchSource.Converted(
                Uri.parse("content://media/result.ogg"),
                null,
                "voice.ogg"
            )
        )
        vm.awaitReady()

        vm.importTrack(Uri.parse("content://media/first.wav"))
        awaitTrackCount(vm, 2)
        vm.importTrack(Uri.parse("content://media/second.wav"))
        awaitTrackCount(vm, 3)

        vm.dispatch(EditorIntent.RemoveTrack("track-1"))
        assertEquals(2, vm.uiState.value.session.tracks.size)

        vm.importTrack(Uri.parse("content://media/third.wav"))
        awaitTrackCount(vm, 3)

        val tracks = vm.uiState.value.session.tracks
        assertEquals(3, tracks.map { it.id }.toSet().size)
        assertEquals(
            "content://media/third.wav",
            tracks.single { track -> track.clips.single().source.uri.endsWith("third.wav") }
                .clips.single().source.uri
        )
    }

    @Test
    fun `converted launch metadata failure clears loading and publishes an error`() = runTest {
        val vm = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Converted(
                resultUri = Uri.parse("content://media/result.ogg"),
                originalUri = Uri.parse("content://media/original.m4a"),
                displayName = "voice.ogg"
            ),
            sourceAnalyzer = { throw IllegalStateException("metadata unavailable") },
            waveformLoader = { emptyList() }
        )

        vm.awaitReady()

        assertFalse(vm.uiState.value.loading)
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun `undo exposes redo and redo reapplies the rendered edit`() = runTest {
        val vm = editorViewModel(
            EditorLaunchSource.Converted(
                Uri.parse("content://media/result.ogg"),
                null,
                "voice.ogg",
            )
        )
        vm.awaitReady()

        vm.dispatch(EditorIntent.Move(1_000L))
        assertTrue(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)

        vm.dispatch(EditorIntent.Undo)
        assertTrue(vm.uiState.value.canRedo)
        assertEquals(0L, vm.uiState.value.session.tracks.single().clips.single().timelineStartMs)

        vm.dispatch(EditorIntent.Redo)
        assertFalse(vm.uiState.value.canRedo)
        assertEquals(1_000L, vm.uiState.value.session.tracks.single().clips.single().timelineStartMs)
    }

    @Test
    fun `import selects the new clip without adding a second undo step`() = runTest {
        val vm = editorViewModel(
            EditorLaunchSource.Converted(Uri.parse("content://media/result.ogg"), null, "voice.ogg")
        )
        vm.awaitReady()

        vm.importTrack(Uri.parse("content://media/import.wav"))
        awaitTrackCount(vm, 2)
        assertEquals(1, vm.uiState.value.canUndo.let { if (it) 1 else 0 })

        vm.dispatch(EditorIntent.Undo)

        assertEquals(1, vm.uiState.value.session.tracks.size)
    }

    @Test
    fun `concurrent imports are serialized and expose an in flight gate`() = runTest {
        val firstAnalyzerStarted = CompletableDeferred<Unit>()
        val releaseFirstAnalyzer = CompletableDeferred<Unit>()
        val vm = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Converted(
                Uri.parse("content://media/result.ogg"), null, "voice.ogg"
            ),
            sourceAnalyzer = { uri ->
                if (uri.toString().endsWith("first.wav")) {
                    firstAnalyzerStarted.complete(Unit)
                    releaseFirstAnalyzer.await()
                }
                AudioSourceInfo(uri.lastPathSegment.orEmpty(), 1_000L)
            },
            waveformLoader = { emptyList() },
        )
        vm.awaitReady()

        vm.importTrack(Uri.parse("content://media/first.wav"))
        firstAnalyzerStarted.await()
        vm.importTrack(Uri.parse("content://media/second.wav"))
        assertTrue(vm.uiState.value.importInFlight)
        releaseFirstAnalyzer.complete(Unit)
        withTimeout(5_000L) {
            while (vm.uiState.value.session.tracks.size < 3) delay(10L)
            while (vm.uiState.value.importInFlight) delay(10L)
        }

        assertFalse(vm.uiState.value.importInFlight)
        assertEquals(
            setOf("content://media/first.wav", "content://media/second.wav"),
            vm.uiState.value.session.tracks.drop(1).map { it.clips.single().source.uri }.toSet(),
        )
    }

    @Test
    fun `rapid double export start enqueues one stable unique attempt`() = runBlocking {
        val scheduler = RecordingExportScheduler()
        val vm = editorViewModel(
            EditorLaunchSource.Converted(Uri.parse("content://media/result.ogg"), null, "voice.ogg"),
            exportScheduler = scheduler,
        )
        vm.awaitReady()

        vm.dispatch(EditorIntent.StartExport)
        vm.dispatch(EditorIntent.StartExport)
        scheduler.awaitFirst()

        assertEquals(1, scheduler.uniqueRequests.size)
        assertTrue(scheduler.uniqueNames.single().startsWith("editor.export."))
        assertEquals(
            scheduler.uniqueNames.single(),
            EditorExportWork.uniqueWorkName(scheduler.uniqueRequests.single().workSpec.input.getString(EditorExportWork.EXPORT_ATTEMPT_ID)!!),
        )
    }

    private suspend fun awaitTrackCount(vm: EditorViewModel, count: Int) {
        withTimeout(5_000L) {
            while (vm.uiState.value.session.tracks.size < count) delay(10L)
        }
    }

    private fun editorViewModel(
        source: EditorLaunchSource,
        durationMs: Long = 10_000L,
        exportScheduler: EditorExportScheduler = RecordingExportScheduler(),
    ): EditorViewModel = EditorViewModel(
        application = app,
        launchSource = source,
        sourceAnalyzer = { uri ->
            AudioSourceInfo(
                displayName = uri.lastPathSegment.orEmpty(),
                durationMs = if (uri.toString().contains("long")) 30_000L else durationMs
            )
        },
        waveformLoader = { emptyList() },
        exportScheduler = exportScheduler,
    )

    private class RecordingExportScheduler : EditorExportScheduler {
        val uniqueNames = mutableListOf<String>()
        val uniqueRequests = mutableListOf<OneTimeWorkRequest>()
        private val firstEnqueue = CompletableDeferred<Unit>()

        override fun enqueue(request: OneTimeWorkRequest): UUID {
            uniqueRequests += request
            return request.id
        }

        override fun enqueueUnique(
            uniqueName: String,
            request: OneTimeWorkRequest,
            replaceExisting: Boolean,
        ): UUID {
            uniqueNames += uniqueName
            uniqueRequests += request
            firstEnqueue.complete(Unit)
            return request.id
        }

        override fun cancel(id: UUID) = Unit
        override fun observe(id: UUID): Flow<WorkInfo?> = emptyFlow()

        suspend fun awaitFirst() = withTimeout(5_000L) { firstEnqueue.await() }
    }
}
