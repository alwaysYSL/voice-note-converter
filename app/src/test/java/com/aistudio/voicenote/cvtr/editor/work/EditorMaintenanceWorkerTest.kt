package com.aistudio.voicenote.cvtr.editor.work

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceInput
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceStorage
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftRepository
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorMaintenanceWorkerTest {
    @Test
    fun `old orphan partial is removed while draft history and in-flight files survive`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val old = now - EditorMaintenanceWorker.RETENTION_MS - 1L
        val exportRoot = File(app.cacheDir, "editor-export").apply { mkdirs() }
        val orphan = File(exportRoot, "orphan.partial").apply {
            writeText("stale")
            setLastModified(old)
        }
        val attemptId = "attempt-live-${UUID.randomUUID()}"
        val inFlight = File(exportRoot, "$attemptId.partial").apply {
            writeText("active")
            setLastModified(old)
        }
        val sourceFile = File(app.cacheDir, "task4-source-${UUID.randomUUID()}.wav").apply {
            writeText("source")
        }
        val draftId = "task4-draft-${UUID.randomUUID()}"
        val sourceStorage = DraftSourceStorage(
            root = File(app.filesDir, "editor-drafts"),
            sourceOpener = { sourceFile.inputStream() },
            sourceSizer = { sourceFile.length() },
            freeSpace = { Long.MAX_VALUE },
            safetyBytes = 0L,
        )
        val repository = EditorDraftRepository(
            database = AppDatabase.getDatabase(app),
            sourceStorage = sourceStorage,
            processedAudioCache = ProcessedAudioCache(File(app.cacheDir, "processed_audio")),
        )
        val sourceUri = "task4-source://${sourceFile.name}"
        repository.save(
            EditorSession(
                id = draftId,
                tracks = listOf(
                    EditorTrack(
                        id = "track-1",
                        name = "Voice",
                        clips = listOf(
                            AudioClip(
                                id = "clip-1",
                                source = AudioSourceRef(sourceUri, 1_000L),
                                sourceStartMs = 0L,
                                sourceEndMs = 1_000L,
                                timelineStartMs = 0L,
                            )
                        ),
                    )
                ),
            )
        )
        val draftSource = File(app.filesDir, "editor-drafts/$draftId")
        val historyOutput = File(app.filesDir, "history/task4-history-${UUID.randomUUID()}.ogg").apply {
            parentFile?.mkdirs()
            writeText("history")
        }
        AppDatabase.getDatabase(app).conversionHistoryDao().insert(
            ConversionHistory(
                originalFileName = "input.wav",
                outputFileName = "history.ogg",
                outputFilePath = historyOutput.absolutePath,
                durationSeconds = 1,
                fileSizeBytes = historyOutput.length(),
                waveform = "",
                bitrateKbps = 32,
                createdAt = now,
            )
        )
        val session = EditorSession(
            id = "in-flight",
            tracks = listOf(
                EditorTrack(
                    id = "track-flight",
                    name = "Flight",
                    clips = listOf(
                        AudioClip(
                            id = "clip-flight",
                            source = AudioSourceRef("content://source", 1_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 1_000L,
                            timelineStartMs = 0L,
                        )
                    ),
                )
            ),
        )
        EditorRenderManifest.writePrivate(app, session, exportAttemptId = attemptId)

        EditorMaintenanceWorker.runOnce(app, nowMs = now)

        assertFalse(orphan.exists())
        assertTrue(draftSource.exists())
        assertTrue(historyOutput.exists())
        assertTrue(inFlight.exists())
        repository.delete(draftId)
        sourceFile.delete()
        historyOutput.delete()
    }
}
