package com.aistudio.voicenote.cvtr.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.aistudio.voicenote.cvtr.editor.data.EditorDraft
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `draft section opens and deletes a saved draft`() {
        val draft = EditorDraft("draft-1", "Interview", 1L, 2L, ExportPreset.VOICE_NOTE_32)
        val opened = AtomicReference<String>()
        val deleted = AtomicReference<EditorDraft>()
        compose.setContent {
            MyApplicationTheme {
                DraftEditorSection(
                    drafts = listOf(draft),
                    onOpen = opened::set,
                    onDelete = deleted::set,
                )
            }
        }

        compose.onNodeWithTag("history_draft_section").assertIsDisplayed()
        compose.onNodeWithTag("history_draft_open_draft-1").performClick()
        compose.onNodeWithTag("history_draft_delete_draft-1").performClick()
        assertEquals("draft-1", opened.get())
        assertEquals(draft, deleted.get())
    }
}
