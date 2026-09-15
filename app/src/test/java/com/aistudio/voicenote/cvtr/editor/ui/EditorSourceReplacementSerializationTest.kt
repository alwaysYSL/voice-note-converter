package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftLoad
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSourceReplacementSerializationTest {
    @Test
    fun `replacement serializes edits and reloads durable truth after post-commit failure`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val original = session()
        var durable = original
        var persisted: EditorSession? = null
        val probeStarted = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val viewModel = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Draft("draft-1"),
            persistDraft = { session, _ ->
                persisted = session
                durable = session.copy(dirty = false, draftId = "draft-1")
                throw IOException("cache lease transition")
            },
            loadDraft = { EditorDraftLoad(durable, emptyList()) },
            sourceAnalyzer = { uri ->
                if (uri.toString().endsWith("replacement")) {
                    probeStarted.complete(Unit)
                    releaseProbe.await()
                }
                AudioSourceInfo("replacement.wav", 1_000L)
            },
            waveformLoader = { emptyList() },
        )
        viewModel.awaitReady()

        viewModel.replaceMissingSource("clip-1", Uri.parse("content://source/replacement"))
        probeStarted.await()
        viewModel.dispatch(EditorIntent.SetTrackMuted("track-1", true))
        releaseProbe.complete(Unit)

        withTimeout(5_000L) {
            while (!viewModel.uiState.value.session.tracks.single().muted) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10L)
            }
        }
        assertFalse("queued edit must not enter replacement save", persisted!!.tracks.single().muted)
        assertTrue(viewModel.uiState.value.session.dirty)
        assertTrue(viewModel.uiState.value.session.tracks.single().clips.single().source.uri.endsWith("replacement"))
    }

    private fun session(): EditorSession = EditorSession(
        id = "draft-1",
        draftId = "draft-1",
        tracks = listOf(
            EditorTrack(
                id = "track-1",
                name = "Voice",
                clips = listOf(
                    AudioClip(
                        id = "clip-1",
                        source = AudioSourceRef("/private/draft/source.wav", 1_000L),
                        sourceStartMs = 0L,
                        sourceEndMs = 1_000L,
                        timelineStartMs = 0L,
                    )
                ),
            )
        ),
        selectedClipId = "clip-1",
    )
}
