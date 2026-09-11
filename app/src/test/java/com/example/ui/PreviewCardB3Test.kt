package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.example.ui.components.TrimState
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewCardB3Test {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `converted waveform seeks in every non-busy state`() {
        var status by mutableStateOf(ProcessStatus.IDLE)
        var seekCount = 0
        composeRule.setContent {
            MyApplicationTheme {
                PreviewCard(
                    fileName = "converted.ogg",
                    hasConvertedResult = true,
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    isPlaying = false,
                    currentPositionMs = 0,
                    fallbackDurationSec = 10,
                    trimState = TrimState(),
                    processStatus = status,
                    onTogglePlayback = {},
                    onSeek = { seekCount++ },
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

        listOf(
            ProcessStatus.IDLE,
            ProcessStatus.CONVERTED,
            ProcessStatus.SENT,
            ProcessStatus.FAILED
        ).forEachIndexed { index, nonBusyStatus ->
            composeRule.runOnIdle { status = nonBusyStatus }
            composeRule.onNodeWithTag("preview_waveform").performTouchInput { click() }
            composeRule.runOnIdle { assertEquals(index + 1, seekCount) }
        }
    }

    @Test
    fun `converted waveform ignores seek while processing`() {
        var status by mutableStateOf(ProcessStatus.ANALYZING)
        var seekCount = 0
        composeRule.setContent {
            MyApplicationTheme {
                PreviewCard(
                    fileName = "converted.ogg",
                    hasConvertedResult = true,
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    isPlaying = false,
                    currentPositionMs = 0,
                    fallbackDurationSec = 10,
                    trimState = TrimState(),
                    processStatus = status,
                    onTogglePlayback = {},
                    onSeek = { seekCount++ },
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

        listOf(
            ProcessStatus.ANALYZING,
            ProcessStatus.CONVERTING,
            ProcessStatus.SENDING
        ).forEach { busyStatus ->
            composeRule.runOnIdle { status = busyStatus }
            composeRule.onNodeWithTag("preview_waveform").performTouchInput { click() }
        }

        composeRule.runOnIdle { assertEquals(0, seekCount) }
    }

    @Test
    fun `ready source preview shows pitch control`() {
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
                    trimState = TrimState(),
                    processStatus = ProcessStatus.IDLE,
                    onTogglePlayback = {},
                    onSeek = {},
                    onTrimRangeChange = {},
                    onToggleTrim = {},
                    onPreviewTrim = {},
                    onResetTrim = {},
                    playbackError = null
                )
            }
        }

        composeRule.onNodeWithText("🎵  Pitch").assertExists()
        composeRule.onNodeWithTag("preview_tools_divider").assertExists()
    }
}
