package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceStorage
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftRepository
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorCorruptDraftSourceTest {
    @Test
    fun `nonempty corrupt private source is offline until replaced`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.cacheDir, "corrupt-draft-${System.nanoTime()}").apply { mkdirs() }
        val source = File(root, "source.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = EditorDraftRepository(
            database = database,
            sourceStorage = DraftSourceStorage(File(root, "drafts")),
        )
        try {
            val draftId = repository.save(
                EditorSession(
                    id = "corrupt-draft",
                    tracks = listOf(
                        EditorTrack(
                            id = "track-1",
                            name = "Voice",
                            clips = listOf(
                                AudioClip(
                                    id = "clip-1",
                                    source = AudioSourceRef(source.absolutePath, 1_000L),
                                    sourceStartMs = 0L,
                                    sourceEndMs = 1_000L,
                                    timelineStartMs = 0L,
                                )
                            ),
                        )
                    ),
                )
            )
            val privatePath = requireNotNull(repository.load(draftId))
                .tracks.single().clips.single().source.uri
            File(privatePath).writeText("not an audio stream")

            val viewModel = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Draft(draftId),
                draftRepository = repository,
                sourceAnalyzer = { AudioSourceInfo("source.wav", 1_000L) },
                waveformLoader = { throw IOException("decode failed") },
            )
            viewModel.awaitReady()

            assertEquals(setOf("clip-1"), viewModel.uiState.value.offlineClipIds)
            assertTrue(viewModel.uiState.value.sourceError?.contains("unavailable") == true)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
