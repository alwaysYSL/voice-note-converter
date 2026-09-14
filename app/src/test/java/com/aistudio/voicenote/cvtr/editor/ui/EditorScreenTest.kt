package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
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
import org.junit.Assert.assertTrue

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

    @Test
    fun `redo becomes enabled after undo and dispatches redo`() {
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(
                    state = stateWithTwoClips().copy(canUndo = true, canRedo = true),
                    onIntent = intents::add,
                )
            }
        }

        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("redo").assertIsEnabled().performClick()

        assertTrue(intents.last() is EditorIntent.Redo)
    }

    @Test
    fun `required timeline and track tags exist at compact viewport`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(stateWithTwoClips(), {})
            }
        }

        compose.onNodeWithTag("editor_timeline").assertIsDisplayed()
        compose.onNodeWithTag("track-a").assertIsDisplayed()
        compose.onNodeWithTag("undo").assertIsDisplayed()
        compose.onNodeWithTag("redo").assertIsDisplayed()

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("editor_timeline", "track-a", "undo", "redo").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag must remain within the compact viewport bounds=$bounds root=$rootBounds",
                bounds.left >= rootBounds.left &&
                    bounds.top >= rootBounds.top &&
                    bounds.right <= rootBounds.right &&
                    bounds.bottom <= rootBounds.bottom,
            )
        }
    }

    @Test
    fun `playhead overlay spans clip rows so it stays visible over waveform content`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(
                    state = stateWithTwoClips().copy(
                        session = stateWithTwoClips().session.copy(playheadMs = 2_000L),
                    ),
                    onIntent = {},
                )
            }
        }

        val playhead = compose.onNodeWithTag("editor_playhead").fetchSemanticsNode().boundsInRoot
        val clip = compose.onNodeWithTag("clip-a").fetchSemanticsNode().boundsInRoot
        assertTrue("playhead should cover the clip row: $playhead vs $clip", playhead.top <= clip.top)
        assertTrue("playhead should cover the clip row: $playhead vs $clip", playhead.bottom >= clip.bottom)
    }

    @Test
    fun `short clip keeps a minimum interactive hit target`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(
                    state = stateWithShortClip(),
                    onIntent = {},
                )
            }
        }

        val bounds = compose.onNodeWithTag("clip-short").fetchSemanticsNode().boundsInRoot
        assertTrue("short clip hit target width bounds=$bounds", bounds.width >= 48f)
        assertTrue("short clip hit target height bounds=$bounds", bounds.height >= 48f)
    }

    @Test
    fun `trim drag accumulates multiple pointer events from original boundary`() {
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(stateWithTrimClip(), intents::add)
            }
        }

        compose.onNodeWithContentDescription("Trim start of trim", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(36f, 0f))
                moveBy(Offset(36f, 0f))
                up()
        }

        val trim = intents.filterIsInstance<EditorIntent.Trim>().lastOrNull()
            ?: error("expected trim intent, intents=$intents")
        assertTrue("trim includes both drag events trim=$trim", trim.sourceStartMs >= 2_500L)
    }

    @Test
    fun `trim gesture maps timeline delta through clip speed`() {
        var speed by mutableStateOf(.5f)
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme { EditorScreen(stateWithSpeedClip(speed), intents::add) }
        }
        compose.onNodeWithContentDescription("Trim start of speed", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(18f, 0f))
                up()
            }
        val halfSourceDelta = intents.filterIsInstance<EditorIntent.Trim>().last().sourceStartMs
        assertTrue("intents=$intents", halfSourceDelta > 0L)

        speed = 2f
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Trim start of speed", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                moveBy(Offset(18f, 0f))
                up()
            }
        val doubleSourceDelta = intents.filterIsInstance<EditorIntent.Trim>().last().sourceStartMs
        assertTrue(
            "half=$halfSourceDelta double=$doubleSourceDelta intents=$intents",
            doubleSourceDelta in (halfSourceDelta * 3L)..(halfSourceDelta * 5L),
        )
    }

    @Test
    fun `empty timeline tap seeks and clears selection`() {
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(stateWithTwoClips().copy(session = stateWithTwoClips().session.copy(playheadMs = 0L)), intents::add)
            }
        }

        compose.onNodeWithTag("editor_timeline").performTouchInput {
            click(Offset(315f, 100f))
        }

        assertTrue("intents=$intents", intents.any { it == EditorIntent.SelectClip(null) })
        assertTrue("intents=$intents", intents.any { it is EditorIntent.Seek && it.positionMs > 0L })
    }

    @Test
    fun `short selected clip uses its hit target for move instead of overlapping trim zones`() {
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme { EditorScreen(stateWithShortClip(), intents::add) }
        }

        compose.onNodeWithTag("clip-short").performTouchInput {
            down(center)
            moveBy(Offset(50f, 0f))
            up()
        }

        assertTrue(intents.any { it is EditorIntent.Move && it.timelineStartMs > 0L })
    }

    @Test
    fun `changing selection refreshes clip gesture behavior`() {
        var editorState by mutableStateOf(stateWithTwoClips())
        val intents = mutableListOf<EditorIntent>()
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(
                    state = editorState,
                    onIntent = { intent ->
                        intents += intent
                    if (intent is EditorIntent.SelectClip) {
                        editorState = editorState.copy(session = editorState.session.copy(selectedClipId = intent.clipId))
                    }
                    },
                )
            }
        }

        compose.onNodeWithTag("clip-b").performClick()
        compose.onNodeWithTag("clip-b").performTouchInput {
            down(center)
            moveBy(Offset(36f, 0f))
            up()
        }

        assertTrue(intents.last { it is EditorIntent.Move } is EditorIntent.Move)
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

    private fun stateWithShortClip(): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "session-short",
            tracks = listOf(
                EditorTrack(
                    id = "track-short",
                    name = "Short",
                    clips = listOf(clip("short", 0L, 400L)),
                )
            ),
            selectedClipId = "short",
        ),
    )

    private fun stateWithTrimClip(): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "session-trim",
            tracks = listOf(
                EditorTrack(
                    id = "track-trim",
                    name = "Trim",
                    clips = listOf(clip("trim", 0L, 10_000L)),
                )
            ),
            selectedClipId = "trim",
        ),
    )

    private fun stateWithSpeedClip(speed: Float): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "session-speed-$speed",
            tracks = listOf(
                EditorTrack(
                    id = "track-speed",
                    name = "Speed",
                    clips = listOf(
                        clip("speed", 0L, 20_000L).copy(
                            effects = ClipEffects(speed = speed),
                        ),
                    ),
                ),
            ),
            selectedClipId = "speed",
        ),
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
