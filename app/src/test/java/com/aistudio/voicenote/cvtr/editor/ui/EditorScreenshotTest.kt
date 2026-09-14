package com.aistudio.voicenote.cvtr.editor.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h480dp-mdpi", sdk = [34])
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `layout A editor remains legible at smallest supported viewport`() {
        compose.setContent {
            MyApplicationTheme {
                EditorScreen(
                    state = compactEditorState(),
                    onIntent = {},
                )
            }
        }

        compose.onRoot().captureRoboImage(filePath = "src/test/screenshots/editor_layout_a.png")
    }

    private fun compactEditorState(): EditorUiState = EditorUiState(
        loading = false,
        session = EditorSession(
            id = "screenshot-session",
            tracks = listOf(
                EditorTrack(
                    id = "track-1",
                    name = "Narration",
                    clips = listOf(clip("clip-1", 0L, 5_000L)),
                ),
                EditorTrack(
                    id = "track-2",
                    name = "Room tone",
                    clips = listOf(clip("clip-2", 1_000L, 4_500L)),
                ),
                EditorTrack(
                    id = "track-3",
                    name = "Music",
                    clips = listOf(clip("clip-3", 2_000L, 6_000L)),
                ),
            ),
            selectedClipId = "clip-2",
            playheadMs = 2_500L,
        ),
    )

    private fun clip(id: String, startMs: Long, endMs: Long): AudioClip = AudioClip(
        id = id,
        source = AudioSourceRef("content://screenshot/$id", endMs - startMs),
        sourceStartMs = 0L,
        sourceEndMs = endMs - startMs,
        timelineStartMs = startMs,
    )
}
