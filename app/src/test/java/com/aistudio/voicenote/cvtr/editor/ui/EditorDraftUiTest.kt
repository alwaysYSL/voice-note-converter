package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorDraftUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `dirty exit shows exact choices and clean exit is direct`() {
        var state by mutableStateOf(state(dirty = true))
        val intents = mutableListOf<EditorIntent>()
        var backCount = 0
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(state, intents::add, onBack = { backCount++ })
            }
        }

        compose.onNodeWithTag("editor_back").performClick()
        assertEquals(
            2,
            compose.onAllNodesWithText("Simpan sebagai draft").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("Buang sesi").assertIsDisplayed()
        compose.onNodeWithText("Batal").assertIsDisplayed()
        compose.onNodeWithText("Batal").performClick()
        compose.onNodeWithTag("editor_back").performClick()
        compose.onNodeWithText("Buang sesi").performClick()
        assertEquals(1, backCount)

        state = state(dirty = false)
        compose.onNodeWithTag("editor_back").performClick()
        assertEquals(2, backCount)
    }

    @Test
    fun `save failure and success states remain observable in editor`() {
        var state by mutableStateOf(state(dirty = true))
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme { EditorScreen(state, intents::add) }
        }

        state = state.copy(draft = EditorDraftUiState(
            status = EditorDraftSaveStatus.SAVING,
            progress = .5f,
        ))
        compose.waitForIdle()
        compose.onNodeWithTag("editor_draft_progress").assertIsDisplayed()

        state = state.copy(draft = EditorDraftUiState(
            status = EditorDraftSaveStatus.FAILED,
            error = "save failed",
            canRetry = true,
        ))
        compose.waitForIdle()
        compose.onNodeWithTag("editor_draft_error").assertIsDisplayed()

        state = state.copy(
            session = state.session.copy(dirty = false, draftId = "draft-1"),
            draft = EditorDraftUiState(
                status = EditorDraftSaveStatus.SUCCEEDED,
                draftId = "draft-1",
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("editor_draft_success").assertIsDisplayed()
        compose.onNodeWithText("Update draft").assertIsDisplayed()
    }

    private fun state(dirty: Boolean): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "draft-ui-session",
            dirty = dirty,
            tracks = listOf(
                EditorTrack(
                    id = "track-1",
                    name = "Voice",
                    clips = listOf(
                        AudioClip(
                            id = "clip-1",
                            source = AudioSourceRef("content://source", 1_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 1_000L,
                            timelineStartMs = 0L,
                        )
                    ),
                )
            ),
            selectedClipId = "clip-1",
        ),
    )
}
