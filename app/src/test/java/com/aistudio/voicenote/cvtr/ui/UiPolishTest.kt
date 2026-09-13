package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performClick
import com.aistudio.voicenote.cvtr.ui.components.TrimState
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPolishTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `bottom navigation is a compact icon only floating island`() {
        composeRule.setContent {
            MyApplicationTheme {
                AppNavigation()
            }
        }

        composeRule.onNodeWithTag("floating_bottom_navigation").assertExists()
        composeRule.onNodeWithText("Converter").assertDoesNotExist()
        composeRule.onNodeWithText("Riwayat").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Converter").assertExists()
        composeRule.onNodeWithContentDescription("Riwayat").assertExists()
        composeRule.onNodeWithContentDescription("Converter").assertIsSelected()
        composeRule.onNodeWithTag("floating_bottom_navigation").assertHeightIsAtLeast(60.dp)
    }

    @Test
    fun `converter header clears the system status area`() {
        val viewModel = MainViewModel(application())

        composeRule.setContent {
            MyApplicationTheme {
                MainScreen(viewModel, statusBarInset = 24.dp)
            }
        }

        val headerTop = composeRule.onNodeWithText("Voice note studio")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue("converter header should not start inside the status area", headerTop >= 36f)
    }

    @Test
    fun `selected source uses a compact summary instead of a second full picker`() {
        val viewModel = MainViewModel(application())
        viewModel.handleIncomingUri(Uri.parse("content://media/selected"))

        composeRule.setContent {
            MyApplicationTheme {
                MainScreen(viewModel)
            }
        }

        composeRule.onNodeWithTag("source_summary").assertExists()
        composeRule.onNodeWithText("Pilih audio atau video").assertDoesNotExist()
    }

    @Test
    fun `converter never shows the unused telegram destination section`() {
        val viewModel = MainViewModel(application())
        viewModel.handleIncomingUri(Uri.parse("content://media/selected"))

        composeRule.setContent {
            MyApplicationTheme {
                MainScreen(viewModel)
            }
        }

        composeRule.onNodeWithText("Tujuan Telegram").assertDoesNotExist()
    }

    @Test
    fun `converter action stays available in a sticky action bar`() {
        val viewModel = MainViewModel(application())
        viewModel.handleIncomingUri(Uri.parse("content://media/selected"))

        composeRule.setContent {
            MyApplicationTheme {
                MainScreen(viewModel)
            }
        }

        composeRule.onNodeWithTag("converter_action_bar").assertExists()
        val actionBounds = composeRule.onNodeWithTag("converter_action_bar")
            .fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("action tray should leave room around its edges", actionBounds.width < rootBounds.width)
    }

    @Test
    fun `advanced audio controls start collapsed and open on demand`() {
        composeRule.setContent {
            MyApplicationTheme {
                PreviewCard(
                    fileName = "recording.m4a",
                    hasConvertedResult = false,
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    isPlaying = false,
                    currentPositionMs = 0,
                    fallbackDurationSec = 10,
                    trimState = TrimState(endMs = 10_000, totalDurationMs = 10_000),
                    processStatus = ProcessStatus.IDLE,
                    onTogglePlayback = {},
                    onSeek = {},
                    onTrimRangeChange = {},
                    onToggleTrim = {},
                    onPreviewTrim = {},
                    onResetTrim = {},
                    pitchSemitones = 0f,
                    onPitchChange = {},
                    playbackError = null
                )
            }
        }

        composeRule.onNodeWithTag("audio_editor_toggle").assertExists()
        composeRule.onNodeWithText("🎵  Pitch").assertDoesNotExist()
        composeRule.onNodeWithText("Potong bagian audio").assertDoesNotExist()

        composeRule.onNodeWithText("Edit audio").performClick()
        composeRule.onNodeWithText("🎵  Pitch").assertExists()
        composeRule.onNodeWithText("Potong bagian audio").assertExists()
    }

    private fun application(): Application =
        ApplicationProvider.getApplicationContext()
}
