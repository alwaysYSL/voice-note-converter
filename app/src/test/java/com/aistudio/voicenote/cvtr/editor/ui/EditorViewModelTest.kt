package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private suspend fun awaitTrackCount(vm: EditorViewModel, count: Int) {
        withTimeout(5_000L) {
            while (vm.uiState.value.session.tracks.size < count) delay(10L)
        }
    }

    private fun editorViewModel(
        source: EditorLaunchSource,
        durationMs: Long = 10_000L
    ): EditorViewModel = EditorViewModel(
        application = app,
        launchSource = source,
        sourceAnalyzer = { uri ->
            AudioSourceInfo(
                displayName = uri.lastPathSegment.orEmpty(),
                durationMs = if (uri.toString().contains("long")) 30_000L else durationMs
            )
        },
        waveformLoader = { emptyList() }
    )
}
