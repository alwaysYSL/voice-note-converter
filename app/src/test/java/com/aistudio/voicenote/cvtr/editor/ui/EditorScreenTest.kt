package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `single selected clip controls contextual toolbar`() {
        var editorState by mutableStateOf(stateWithTwoClips())
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(editorState, onIntent = { intent ->
                    if (intent is EditorIntent.SelectClip) {
                        editorState = editorState.copy(
                            session = editorState.session.copy(selectedClipId = intent.clipId)
                        )
                    }
                    }
                )
            }
        }

        compose.onNodeWithTag("clip-b").performClick()

        compose.onNodeWithTag("clip-a").assertIsNotSelected()
        compose.onNodeWithTag("clip-b").assertIsSelected()
        compose.onNodeWithText("Split").assertIsEnabled()
    }

    @Test
    fun `fifth track disables add track`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(stateWithTracks(5), {})
            }
        }

        compose.onNodeWithTag("add_track").assertIsNotEnabled()
    }

    @Test
    fun `export remains disabled until phase two renderer exists`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(stateWithTwoClips(), {})
            }
        }

        compose.onNodeWithTag("editor_export_disabled").assertIsNotEnabled()
    }

    private fun stateWithTwoClips(): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "session-test",
            tracks = listOf(
                EditorTrack(
                    id = "track-a",
                    name = "Voice",
                    clips = listOf(
                        clip(id = "clip-a", startMs = 0L, endMs = 4_000L),
                        clip(id = "clip-b", startMs = 4_000L, endMs = 8_000L),
                    )
                )
            ),
            selectedClipId = "clip-a",
        )
    )

    private fun stateWithTracks(count: Int): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "session-tracks",
            tracks = (0 until count).map { index ->
                EditorTrack(id = "track-$index", name = "Track ${index + 1}")
            }
        )
    )

    private fun clip(id: String, startMs: Long, endMs: Long): AudioClip = AudioClip(
        id = id,
        source = AudioSourceRef("content://test/$id", endMs - startMs),
        sourceStartMs = 0L,
        sourceEndMs = endMs - startMs,
        timelineStartMs = startMs,
        effects = ClipEffects(),
    )
}
