package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.editor.model.AudioTrackClip
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var repository: ConversionHistoryRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversionHistoryRepository(db.conversionHistoryDao())
        viewModel = EditorViewModel(application, repository)
    }

    @Test
    fun testEditorScreenRendersEmptyTracks() {
        composeTestRule.setContent {
            EditorScreen(
                viewModel = viewModel,
                historyList = emptyList()
            )
        }

        composeTestRule.onNodeWithText("Audio Editor & Mixer").assertExists()
        composeTestRule.onNodeWithText("+ Track 1 (Kosong)", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("+ Track 2 (Kosong)", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("+ Track 3 (Kosong)", useUnmergedTree = true).assertExists()
    }
}