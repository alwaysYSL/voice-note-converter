package com.aistudio.voicenote.cvtr.editor.work

import com.aistudio.voicenote.cvtr.editor.audio.TimelineRenderer
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertThrows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorExportWorkerTest {
    @Test
    fun `manifest round trip excludes selection and transient state`() {
        val session = session().copy(selectedClipId = "clip", playheadMs = 1_200L, dirty = true)
        val manifest = EditorRenderManifest.fromSession(
            session = session,
            sourceHistoryId = 41L,
            preset = ExportPreset.HIGH_QUALITY_64,
        )
        val file = File.createTempFile("editor-manifest", ".json")
        try {
            manifest.writeTo(file)
            val restored = EditorRenderManifest.readValidated(file)
            assertEquals(manifest, restored)
            assertFalse(restored.renderSession.selectedClipId != null)
            assertEquals(0L, restored.renderSession.playheadMs)
            assertTrue(restored.renderSession.tracks.isNotEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `manifest rejects an empty timeline before any render`() {
        assertThrows(IllegalArgumentException::class.java) {
            EditorRenderManifest.fromSession(EditorSession.empty())
        }
    }

    @Test
    fun `successful 64 kbps export inserts one history row`() = runBlocking {
        val storage = FakeStorage()
        val history = FakeHistory()
        val runner = runner(storage, history)
        val result = runner.run(manifest(), "mix.ogg", ExportPreset.HIGH_QUALITY_64)

        assertTrue(result.toString(), result is EditorExportResult.Success)
        assertEquals(64, history.rows.single().bitrateKbps)
        assertTrue(storage.published.single().toString().endsWith(".ogg"))
        assertTrue(storage.partial.isEmpty())
    }

    @Test
    fun `cancelled export removes partial and published output without history`() = runBlocking {
        val storage = FakeStorage()
        val history = FakeHistory()
        val runner = runner(storage, history, cancelAfterFirstChunk = true)

        val result = runner.run(manifest(), "mix.ogg", ExportPreset.HIGH_QUALITY_64)

        assertTrue(result.toString(), result is EditorExportResult.Cancelled)
        assertTrue(storage.partial.isEmpty())
        assertTrue(storage.published.isEmpty())
        assertTrue(history.rows.isEmpty())

        val failedStorage = FakeStorage(validate = false)
        val failedHistory = FakeHistory()
        val failed = runner(failedStorage, failedHistory).run(
            manifest(), "mix.ogg", ExportPreset.HIGH_QUALITY_64
        )
        assertTrue(failed is EditorExportResult.Failure)
        assertTrue(failedStorage.partial.isEmpty())
        assertTrue(failedStorage.published.isEmpty())
        assertTrue(failedHistory.rows.isEmpty())
    }

    @Test
    fun `retrying the same export attempt reuses its published output and history`() = runBlocking {
        val storage = FakeStorage()
        val history = FakeHistory()
        val snapshot = manifest()

        assertTrue(runner(storage, history).run(snapshot, "mix.ogg", snapshot.preset) is EditorExportResult.Success)
        assertTrue(runner(storage, history).run(snapshot, "mix.ogg", snapshot.preset) is EditorExportResult.Success)

        assertEquals(1, history.rows.size)
        assertEquals(1, storage.published.size)
    }

    private fun runner(
        storage: FakeStorage,
        history: FakeHistory,
        cancelAfterFirstChunk: Boolean = false,
    ): EditorExportRunner = EditorExportRunner(
        rendererFactory = { object : TimelineRenderer {
            private var chunks = 0
            override fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray {
                chunks++
                if (cancelAfterFirstChunk && chunks > 1) throw CancellationException("cancelled")
                return ShortArray(frameCount)
            }

            override fun invalidate(sourceIds: Set<String>) = Unit
            override fun close() = Unit
        } },
        encoderFactory = { output, _, _ -> FakeEncoder(output) },
        storage = storage,
        history = history,
    )

    private fun manifest(): EditorRenderManifest = EditorRenderManifest.fromSession(
        session(), sourceHistoryId = 41L, preset = ExportPreset.HIGH_QUALITY_64
    )

    private fun session(): EditorSession = EditorSession(
        id = "session",
        tracks = listOf(
            EditorTrack(
                id = "track",
                name = "Voice",
                clips = listOf(
                    AudioClip(
                        id = "clip",
                        source = AudioSourceRef("file:///input.wav", 1_000L),
                        sourceStartMs = 0L,
                        sourceEndMs = 1_000L,
                        timelineStartMs = 0L,
                        effects = ClipEffects(),
                    )
                ),
            )
        ),
        selectedClipId = "clip",
    )

    private class FakeEncoder(private val output: OutputStream) : EditorExportEncoder {
        override fun write(samples: ShortArray) = output.write(samples.size)
        override fun finish() = Unit
        override fun close() = output.close()
    }

    private class FakeStorage(private val validate: Boolean = true) : EditorExportStorage {
        val partial = mutableListOf<File>()
        val published = mutableListOf<java.net.URI>()
        override fun createPartialFile(workId: String): File = File.createTempFile(workId, ".partial").also(partial::add)
        override fun publish(partialFile: File, outputName: String): android.net.Uri =
            android.net.Uri.fromFile(File(partialFile.parentFile, outputName)).also {
                published += java.net.URI.create(it.toString())
            }
        override fun validatePublished(uri: android.net.Uri): Boolean = validate
        override fun deletePublished(uri: android.net.Uri): Boolean = published.remove(java.net.URI.create(uri.toString()))
        override fun deletePartial(file: File): Boolean = partial.remove(file) && file.delete()
    }

    private class FakeHistory : EditorExportHistory {
        val rows = mutableListOf<com.aistudio.voicenote.cvtr.data.local.ConversionHistory>()
        override suspend fun insert(item: com.aistudio.voicenote.cvtr.data.local.ConversionHistory): Long {
            rows += item
            return rows.size.toLong()
        }
        override suspend fun delete(id: Long) { rows.removeAt(id.toInt() - 1) }
        override suspend fun findByExportAttemptId(attemptId: String): com.aistudio.voicenote.cvtr.data.local.ConversionHistory? =
            rows.firstOrNull { it.editorExportAttemptId == attemptId }
    }
}
