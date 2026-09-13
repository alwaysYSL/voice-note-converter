package com.aistudio.voicenote.cvtr.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WaveformVisualizerB2Test {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `start handle stops two percent before end handle`() {
        var changedRange: ClosedFloatingPointRange<Float>? = null
        composeRule.setContent {
            MyApplicationTheme {
                WaveformVisualizer(
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    modifier = Modifier.width(300.dp).testTag("waveform"),
                    trimRange = 0.2f..0.8f,
                    onTrimRangeChange = { changedRange = it },
                    showTrimHandles = true
                )
            }
        }

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.3f, centerY))
            moveTo(Offset(width.toFloat(), centerY))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0.78f, changedRange!!.start, 0.001f)
            assertEquals(0.8f, changedRange!!.endInclusive, 0.001f)
        }
    }

    @Test
    fun `end handle stops two percent after start handle`() {
        var changedRange: ClosedFloatingPointRange<Float>? = null
        composeRule.setContent {
            MyApplicationTheme {
                WaveformVisualizer(
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    modifier = Modifier.width(300.dp).testTag("waveform"),
                    trimRange = 0.2f..0.8f,
                    onTrimRangeChange = { changedRange = it },
                    showTrimHandles = true
                )
            }
        }

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(width * 0.8f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
            moveTo(Offset(0f, centerY))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0.2f, changedRange!!.start, 0.001f)
            assertEquals(0.22f, changedRange!!.endInclusive, 0.001f)
        }
    }

    @Test
    fun `overlapping handles select end handle so it can move right`() {
        var changedRange: ClosedFloatingPointRange<Float>? = null
        composeRule.setContent {
            MyApplicationTheme {
                WaveformVisualizer(
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    modifier = Modifier.width(300.dp).testTag("waveform"),
                    trimRange = 0.5f..0.5f,
                    onTrimRangeChange = { changedRange = it },
                    showTrimHandles = true
                )
            }
        }

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.8f, centerY))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0.5f, changedRange!!.start, 0.001f)
            assertEquals(0.8f, changedRange!!.endInclusive, 0.001f)
        }
    }

    @Test
    fun `collapsed range at right endpoint opens to minimum gap`() {
        var changedRange: ClosedFloatingPointRange<Float>? = null
        composeRule.setContent {
            MyApplicationTheme {
                WaveformVisualizer(
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    modifier = Modifier.width(300.dp).testTag("waveform"),
                    trimRange = 1f..1f,
                    onTrimRangeChange = { changedRange = it },
                    showTrimHandles = true
                )
            }
        }

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(width.toFloat(), centerY))
            moveTo(Offset(width * 0.9f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0.98f, changedRange!!.start, 0.001f)
            assertEquals(1f, changedRange!!.endInclusive, 0.001f)
        }
    }

    @Test
    fun `sub-minimum range at left endpoint expands to minimum gap`() {
        var changedRange: ClosedFloatingPointRange<Float>? = null
        composeRule.setContent {
            MyApplicationTheme {
                WaveformVisualizer(
                    waveform = listOf(1, 2, 3),
                    progress = 0f,
                    modifier = Modifier.width(300.dp).testTag("waveform"),
                    trimRange = 0f..0.01f,
                    onTrimRangeChange = { changedRange = it },
                    showTrimHandles = true
                )
            }
        }

        composeRule.onNodeWithTag("waveform").performTouchInput {
            down(Offset(0f, centerY))
            moveTo(Offset(-20f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(0f, changedRange!!.start, 0.001f)
            assertEquals(0.02f, changedRange!!.endInclusive, 0.001f)
        }
    }
}
