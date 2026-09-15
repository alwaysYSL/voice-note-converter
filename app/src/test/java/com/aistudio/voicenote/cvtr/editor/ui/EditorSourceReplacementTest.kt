package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSourceReplacementTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `replacement shorter than referenced source range is rejected`() = runTest {
        val vm = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Converted(
                resultUri = Uri.parse("content://source/original"),
                originalUri = null,
                displayName = "Voice",
            ),
            sourceAnalyzer = { uri ->
                AudioSourceInfo(
                    displayName = uri.lastPathSegment.orEmpty(),
                    durationMs = if (uri.toString().endsWith("replacement")) 500L else 1_000L,
                )
            },
            waveformLoader = { emptyList() },
        )
        vm.awaitReady()

        vm.replaceMissingSource("clip-1", Uri.parse("content://source/replacement"))
        withTimeout(5_000L) {
            while (vm.uiState.value.message == null) delay(10L)
        }
        assertEquals(EditorMessage.SOURCE_REPLACEMENT_INVALID, vm.uiState.value.message)
        assertEquals("content://source/original", vm.uiState.value.session.tracks.single().clips.single().source.uri)
    }
}
