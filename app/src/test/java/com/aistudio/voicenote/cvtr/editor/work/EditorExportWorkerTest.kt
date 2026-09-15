package com.aistudio.voicenote.cvtr.editor.work

import com.aistudio.voicenote.cvtr.editor.audio.TimelineRenderer
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.audio.MasterLimiter
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.google.common.util.concurrent.Futures
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
            exportAttemptId = "manifest-attempt",
            reservedOutputName = "voice_manifest-attempt.ogg",
            reservedOutputUri = "file:///music/voice_manifest-attempt.ogg.pending",
            reservedOutputFinalUri = "file:///music/voice_manifest-attempt.ogg",
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
    fun `export uses preview-equivalent 960 frame state boundaries`() = runBlocking {
        val storage = FakeStorage()
        val history = FakeHistory()
        val starts = mutableListOf<Long>()
        val counts = mutableListOf<Int>()
        val limiter = MasterLimiter()
        val runner = EditorExportRunner(
            rendererFactory = { object : TimelineRenderer {
                override fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray {
                    starts += startFrame
                    counts += frameCount
                    limiter.process(FloatArray(frameCount) { 2f })
                    return ShortArray(frameCount)
                }

                override fun invalidate(sourceIds: Set<String>) = Unit
                override fun close() = Unit
            } },
            encoderFactory = { output, _, _ -> FakeEncoder(output) },
            storage = storage,
            history = history,
            reservationStore = FakeReservationStore(storage),
            workId = "chunk-equivalence",
        )

        assertTrue(runner.run(manifest(), "mix.ogg", ExportPreset.HIGH_QUALITY_64) is EditorExportResult.Success)
        assertTrue(limiter.gainForTest < 1f)
        assertEquals((0 until 50).map { it * 960L }, starts)
        assertEquals(List(50) { 960 }, counts)
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

    @Test
    fun `retry after process death immediately after publish reuses reserved output`() = runBlocking {
        val storage = FakeStorage()
        storage.crashAfterCopyBeforeFinalize = true
        val snapshot = manifest()

        assertThrows(SimulatedProcessDeath::class.java) {
            runBlocking { runner(storage, FakeHistory()).run(snapshot, "mix.ogg", snapshot.preset) }
        }
        assertEquals(1, storage.published.size)
        assertTrue(storage.pending.isNotEmpty())

        val history = FakeHistory()
        val retry = runner(storage, history).run(snapshot, "mix.ogg", snapshot.preset)
        assertTrue(retry is EditorExportResult.Success)
        assertEquals(1, storage.published.size)
        assertTrue(storage.pending.isEmpty())
        assertEquals(1, storage.finalizedUris.size)
        assertEquals(1, history.rows.size)
        assertEquals("file:///reserved.ogg", history.rows.single().outputFilePath)
    }

    @Test
    fun `history delete failure retains published output and reports warning`() = runBlocking {
        val storage = FakeStorage()
        val history = FakeHistory(failDelete = true)

        val result = runner(storage, history).run(
            manifest(),
            "mix.ogg",
            ExportPreset.HIGH_QUALITY_64,
            isActive = { history.rows.isEmpty() },
        )

        assertTrue(result is EditorExportResult.Cancelled)
        assertEquals(1, storage.published.size)
        assertEquals(1, history.rows.size)
        assertTrue(result.cleanupWarning().orEmpty().contains("published output retained"))
        assertTrue(storage.partial.isEmpty())
    }

    @Test
    fun `pre-q retry reuses attempt-owned pending path without touching requested file`() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "editor-export-pre-q-${java.util.UUID.randomUUID()}")
            .also { it.mkdirs() }
        val storage = PreQFakeStorage(root)
        val reservation = PreQFakeReservationStore(root)
        val requested = File(root, "mix.ogg").also { it.writeText("keep-source") }
        val snapshot = manifest()
        val collision = File(root, reservation.baseCandidateName(snapshot, "mix.ogg"))
            .also { it.writeText("keep-collision") }
        storage.crashAfterCopy = true

        try {
            assertThrows(SimulatedProcessDeath::class.java) {
                runBlocking {
                    runner(storage, FakeHistory(), reservation).run(snapshot, "mix.ogg", snapshot.preset)
                }
            }
            assertTrue(storage.pendingFiles.single().exists())

            val retry = runner(storage, FakeHistory(), reservation).run(snapshot, "mix.ogg", snapshot.preset)
            assertTrue(retry is EditorExportResult.Success)
            assertEquals(1, storage.finalFiles.count { it.exists() })
            assertTrue(storage.pendingFiles.none { it.exists() })
            assertFalse(root.listFiles()?.any { it.name.endsWith(".marker") } == true)
            assertEquals("keep-source", requested.readText())
            assertEquals("keep-collision", collision.readText())

            val firstFinal = storage.finalFiles.single { it.exists() }
            val changedSnapshot = manifest().copy(exportAttemptId = "changed-attempt")
            val changed = runner(storage, FakeHistory(), reservation).run(
                changedSnapshot,
                "changed.ogg",
                changedSnapshot.preset,
            )
            assertTrue(changed is EditorExportResult.Success)
            assertTrue(firstFinal.exists())
            assertEquals(2, storage.finalFiles.count { it.exists() })
            assertEquals("keep-collision", collision.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `worker releases claimed reservation when runner history lookup fails`() = runBlocking {
        val root = File(System.getProperty("java.io.tmpdir"), "editor-export-worker-${UUID.randomUUID()}")
            .also { it.mkdirs() }
        val manifestFile = File(root, "manifest.json")
        val pending = File(root, "claimed.ogg.pending")
        val final = File(root, "claimed.ogg")
        val reservation = ClaimingReservationStore(pending, final)
        val manifest = manifest().copy(exportAttemptId = "worker-leak-test")
        manifest.writeTo(manifestFile)
        val request = EditorExportWork.request(
            manifestPath = manifestFile.absolutePath,
            outputName = "mix.ogg",
            preset = manifest.preset,
            exportAttemptId = manifest.exportAttemptId,
        )
        val history = FakeHistory(failLookupOnCall = 2)
        val dependencies = EditorExportWorker.Dependencies(
            rendererFactory = { error("renderer must not start") },
            encoderFactory = { _, _, _ -> error("encoder must not start") },
            storage = FakeStorage(),
            history = history,
            reservation = reservation,
        )

        try {
            val result = EditorExportWorker(
                ApplicationProvider.getApplicationContext<Context>(),
                workerParameters(request.workSpec.input),
                dependencies,
            ).doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            assertTrue(reservation.claimed)
            assertTrue(reservation.released)
            assertFalse(pending.exists())
            assertFalse(final.exists())
            assertFalse(manifestFile.exists())
            assertTrue(history.rows.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runner(
        storage: EditorExportStorage,
        history: FakeHistory,
        reservationStore: EditorExportReservationStore? = (storage as? FakeStorage)?.let(::FakeReservationStore),
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
        reservationStore = reservationStore,
        workId = "test-export",
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
        private val directory = File(System.getProperty("java.io.tmpdir"), "editor-export-test-${java.util.UUID.randomUUID()}")
        val partial = mutableListOf<File>()
        val published = mutableListOf<java.net.URI>()
        val reservations = mutableMapOf<String, EditorExportOutputIdentity>()
        val pending = mutableSetOf<java.net.URI>()
        val finalizedUris = mutableListOf<java.net.URI>()
        var crashAfterCopyBeforeFinalize = false
        override fun createPartialFile(workId: String): File = File(directory.apply { mkdirs() }, "$workId.partial")
            .also { if (it !in partial) partial += it }
        override fun publish(partialFile: File, outputName: String): android.net.Uri =
            android.net.Uri.fromFile(File(partialFile.parentFile, outputName)).also {
                published += java.net.URI.create(it.toString())
            }
        override fun publishReserved(
            partialFile: File,
            outputName: String,
            outputUri: String?,
        ): android.net.Uri = outputUri?.let {
            android.net.Uri.parse(it).also { value ->
                val identity = java.net.URI.create(value.toString())
                if (identity !in published) published += identity
                pending += identity
                if (crashAfterCopyBeforeFinalize) {
                    crashAfterCopyBeforeFinalize = false
                    throw SimulatedProcessDeath()
                }
                finalizeReserved(value)
            }
        } ?: publish(partialFile, outputName)
        override fun finalizeReserved(uri: android.net.Uri): Boolean {
            val identity = java.net.URI.create(uri.toString())
            finalizedUris += identity
            pending.remove(identity)
            return true
        }
        override fun validatePublished(uri: android.net.Uri): Boolean =
            validate && java.net.URI.create(uri.toString()) in published
        override fun deletePublished(uri: android.net.Uri): Boolean = published.remove(java.net.URI.create(uri.toString()))
        override fun deletePartial(file: File): Boolean = partial.remove(file) && file.delete()
    }

    private class FakeReservationStore(
        private val storage: FakeStorage,
    ) : EditorExportReservationStore {
        override fun reserve(
            manifest: EditorRenderManifest,
            requestedName: String?,
        ): EditorExportOutputIdentity = storage.reservations.getOrPut(manifest.exportAttemptId) {
            EditorExportOutputIdentity("reserved.ogg", "file:///reserved.ogg")
        }

        override fun release(identity: EditorExportOutputIdentity): Boolean =
            storage.deletePublished(android.net.Uri.parse(identity.uri))
    }

    private class PreQFakeReservationStore(
        private val root: File,
    ) : EditorExportReservationStore {
        private val identities = mutableMapOf<String, EditorExportOutputIdentity>()

        fun baseCandidateName(manifest: EditorRenderManifest, requestedName: String): String {
            val suffix = manifest.exportAttemptId.replace(Regex("[^A-Za-z0-9]"), "").take(12)
            return "${requestedName.removeSuffix(".ogg")}_$suffix.ogg"
        }

        override fun reserve(
            manifest: EditorRenderManifest,
            requestedName: String?,
        ): EditorExportOutputIdentity = identities.getOrPut(manifest.exportAttemptId) {
            val base = baseCandidateName(manifest, requestedName ?: "mix.ogg")
            var index = 0
            var finalName: String
            do {
                finalName = if (index == 0) base else {
                    "${base.removeSuffix(".ogg")}_$index.ogg"
                }
                index++
            } while (
                File(root, finalName).exists() ||
                    File(root, "$finalName.pending").exists()
            )
            val final = File(root, finalName)
            val pending = File(root, "$finalName.pending")
            EditorExportOutputIdentity(
                finalName,
                android.net.Uri.fromFile(pending).toString(),
                android.net.Uri.fromFile(final).toString(),
            ).also {
                // The fake models the post-manifest claim performed by the worker-owned
                // reservation; direct runner tests still exercise the same retry identity.
                check(pending.exists() || pending.createNewFile())
            }
        }

        override fun release(identity: EditorExportOutputIdentity): Boolean = true
    }

    private class PreQFakeStorage(
        private val root: File,
    ) : EditorExportStorage {
        val pendingFiles = mutableListOf<File>()
        val finalFiles = mutableListOf<File>()
        var crashAfterCopy = false

        override fun createPartialFile(workId: String): File = File(root, "$workId.partial")

        override fun publish(partialFile: File, outputName: String): android.net.Uri =
            error("pre-Q export must use its reserved output")

        override fun publishReserved(
            partialFile: File,
            outputName: String,
            outputUri: String?,
        ): android.net.Uri {
            val pending = File(android.net.Uri.parse(outputUri ?: error("missing pending URI")).path!!)
            val final = File(pending.path.removeSuffix(".pending"))
            if (!pending.exists()) check(pending.createNewFile())
            if (pending !in pendingFiles) pendingFiles += pending
            if (pending.length() == 0L) {
                FileInputStream(partialFile).use { input ->
                    FileOutputStream(pending).use { output -> input.copyTo(output) }
                }
            }
            if (crashAfterCopy) {
                crashAfterCopy = false
                throw SimulatedProcessDeath()
            }
            check(!final.exists()) { "would overwrite unrelated final" }
            check(pending.renameTo(final))
            finalFiles += final
            return android.net.Uri.fromFile(final)
        }

        override fun validatePublished(uri: android.net.Uri): Boolean {
            val file = File(uri.path ?: return false)
            return !file.name.endsWith(".pending") && file.isFile && file.length() > 0L
        }

        override fun deletePublished(uri: android.net.Uri): Boolean {
            val path = uri.path ?: return false
            val pending = File(if (path.endsWith(".pending")) path else "$path.pending")
            val final = File(if (path.endsWith(".pending")) path.removeSuffix(".pending") else path)
            return (!pending.exists() || pending.delete()) && (!final.exists() || final.delete())
        }

        override fun deletePartial(file: File): Boolean = !file.exists() || file.delete()
    }

    private class ClaimingReservationStore(
        private val pending: File,
        private val final: File,
    ) : EditorExportReservationStore {
        var claimed = false
        var released = false
        private val identity = EditorExportOutputIdentity(
            name = final.name,
            uri = android.net.Uri.fromFile(pending).toString(),
            finalUri = android.net.Uri.fromFile(final).toString(),
        )

        override fun reserve(
            manifest: EditorRenderManifest,
            requestedName: String?,
        ): EditorExportOutputIdentity = identity

        override fun resolve(
            manifest: EditorRenderManifest,
            requestedName: String?,
        ): EditorExportOutputIdentity = identity

        override fun claim(identity: EditorExportOutputIdentity): Boolean {
            claimed = true
            check(pending.createNewFile())
            return true
        }

        override fun release(identity: EditorExportOutputIdentity): Boolean {
            released = true
            return (!pending.exists() || pending.delete()) && (!final.exists() || final.delete())
        }
    }

    private class FakeHistory(
        private var crashBeforeInsert: Boolean = false,
        private val failDelete: Boolean = false,
        private val failLookupOnCall: Int? = null,
    ) : EditorExportHistory {
        val rows = mutableListOf<com.aistudio.voicenote.cvtr.data.local.ConversionHistory>()
        private var lookupCount = 0
        override suspend fun insert(item: com.aistudio.voicenote.cvtr.data.local.ConversionHistory): Long {
            if (crashBeforeInsert) {
                crashBeforeInsert = false
                throw SimulatedProcessDeath()
            }
            val id = rows.size.toLong() + 1L
            rows += item.copy(id = id)
            return id
        }
        override suspend fun delete(id: Long) {
            if (failDelete) error("history delete failed")
            rows.removeIf { it.id == id }
        }
        override suspend fun findByExportAttemptId(attemptId: String): com.aistudio.voicenote.cvtr.data.local.ConversionHistory? {
            lookupCount++
            if (lookupCount == failLookupOnCall) error("history lookup failed")
            return rows.firstOrNull { it.editorExportAttemptId == attemptId }
        }
    }

    private fun workerParameters(input: Data): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = executor.execute(command)
            override fun hasPendingTasks(): Boolean = false
        }
        val taskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = executor
            override fun getSerialTaskExecutor(): SerialExecutor = serial
        }
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = null
        }
        val progress = ProgressUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        val foreground = ForegroundUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        return WorkerParameters(
            UUID.randomUUID(),
            input,
            emptyList<String>(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            Dispatchers.Default,
            taskExecutor,
            workerFactory,
            progress,
            foreground,
        )
    }

    private class SimulatedProcessDeath : VirtualMachineError()

    private fun EditorExportResult.cleanupWarning(): String? = when (this) {
        is EditorExportResult.Success -> cleanupWarning
        is EditorExportResult.Failure -> cleanupWarning
        is EditorExportResult.Cancelled -> cleanupWarning
    }
}
