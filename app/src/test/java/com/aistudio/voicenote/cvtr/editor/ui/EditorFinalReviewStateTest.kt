package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftLoad
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import com.aistudio.voicenote.cvtr.editor.work.CleanupEffectWork
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorFinalReviewStateTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `export preset is a durable dirty edit and reopens at 64 kbps`() = runBlocking {
        var durable: EditorDraftLoad? = null
        val vm = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Converted(Uri.parse("content://source/audio"), null, "voice"),
            persistDraft = { session, _ ->
                val saved = session.copy(id = "draft-64", draftId = "draft-64", dirty = false)
                durable = EditorDraftLoad(saved, emptyList())
                durable!!
            },
            loadDraft = { durable },
            sourceAnalyzer = { AudioSourceInfo("voice", 1_000L) },
            waveformLoader = { emptyList() },
        )
        vm.awaitReady()

        vm.dispatch(EditorIntent.SetExportPreset(ExportPreset.HIGH_QUALITY_64))
        assertEquals(ExportPreset.HIGH_QUALITY_64, vm.uiState.value.session.exportPreset)
        assertTrue(vm.uiState.value.session.dirty)
        vm.dispatch(EditorIntent.SaveDraft())
        awaitDraft(vm, EditorDraftSaveStatus.SUCCEEDED)

        val reopened = EditorViewModel(
            application = app,
            launchSource = EditorLaunchSource.Draft("draft-64"),
            loadDraft = { durable },
            sourceAnalyzer = { AudioSourceInfo("voice", 1_000L) },
            waveformLoader = { emptyList() },
        )
        reopened.awaitReady()
        assertEquals(ExportPreset.HIGH_QUALITY_64, reopened.uiState.value.session.exportPreset)
        assertEquals(ExportPreset.HIGH_QUALITY_64, reopened.uiState.value.export.preset)
        assertFalse(reopened.uiState.value.session.dirty)
    }

    @Test
    fun `successful save publishes private source so external deletion does not break editor`() = runBlocking {
        val external = File.createTempFile("editor-external", ".wav")
        val privateSource = File(app.filesDir, "editor-final/private.wav").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        try {
            val vm = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Converted(Uri.fromFile(external), null, "voice"),
                persistDraft = { session, _ ->
                    val normalized = session.copy(
                        id = "draft-private",
                        draftId = "draft-private",
                        dirty = false,
                        tracks = session.tracks.map { track ->
                            track.copy(clips = track.clips.map { clip ->
                                clip.copy(source = clip.source.copy(uri = privateSource.absolutePath))
                            })
                        },
                    )
                    EditorDraftLoad(normalized, emptyList())
                },
                sourceAnalyzer = { AudioSourceInfo("voice", 1_000L) },
                waveformLoader = { emptyList() },
            )
            vm.awaitReady()
            vm.dispatch(EditorIntent.SaveDraft())
            awaitDraft(vm, EditorDraftSaveStatus.SUCCEEDED)
            external.delete()

            val current = vm.uiState.value.session.tracks.single().clips.single().source.uri
            assertEquals(privateSource.absolutePath, current)
            assertTrue(File(current).exists())
        } finally {
            external.delete()
            privateSource.delete()
        }
    }

    @Test
    fun `missing cleanup cache keeps config and supports explicit reapply`() = runBlocking {
        val source = File.createTempFile("editor-source", ".wav")
        writeCanonicalWav(source, 48_000)
        val scheduler = CleanupSchedulerFake()
        val durable = EditorDraftLoad(
            session = session(
                Uri.fromFile(source).toString(),
                durationMs = 1_000L,
                effects = ClipEffects(
                    processedCacheKey = "missing-cache",
                    cleanupStrength = CleanupStrength.MEDIUM.name,
                    cleanupNormalized = true,
                    cleanupAlgorithmVersion = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
                ),
            ),
            missingPrivateSources = emptyList(),
        )
        try {
            val vm = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Draft("draft-effects"),
                loadDraft = { durable },
                sourceAnalyzer = { AudioSourceInfo("source", 1_000L) },
                waveformLoader = { emptyList() },
                cleanupScheduler = scheduler,
            )
            vm.awaitReady()
            assertEquals(CleanupStrength.MEDIUM.name, vm.uiState.value.session.tracks.single().clips.single().effects.cleanupStrength)
            assertTrue(vm.uiState.value.effectRecoveryClipIds.contains("clip-1"))

            vm.dispatch(EditorIntent.StartExport)
            awaitCondition { vm.uiState.value.export.status == EditorExportStatus.FAILED }
            assertEquals("Efek perlu diterapkan ulang", vm.uiState.value.export.error)

            vm.dispatch(EditorIntent.ReapplyCleanup("clip-1"))
            val request = scheduler.awaitRequest()
            val input = request.workRequest.workSpec.input
            val key = ProcessedAudioKey(
                sourceFingerprint = input.getString(CleanupEffectWork.SOURCE_FINGERPRINT)!!,
                sourceStartMs = input.getLong(CleanupEffectWork.SOURCE_START_MS, 0L),
                sourceEndMs = input.getLong(CleanupEffectWork.SOURCE_END_MS, 0L),
                cleanup = CleanupStrength.valueOf(input.getString(CleanupEffectWork.CLEANUP_STRENGTH)!!),
                normalized = input.getBoolean(CleanupEffectWork.NORMALIZED, false),
                algorithmVersion = input.getString(CleanupEffectWork.ALGORITHM_VERSION)!!,
            )
            val cacheFile = File(app.cacheDir, "processed_audio/${key.toFilename()}.pcm")
            cacheFile.parentFile?.mkdirs()
            writeCanonicalWav(cacheFile, 48)
            scheduler.emitSuccess(
                request.id,
                Data.Builder()
                    .putString(CleanupEffectWork.RESULT_CACHE_KEY_FILENAME, key.toFilename())
                    .putString(CleanupEffectWork.RESULT_CACHE_KEY_FINGERPRINT, key.sourceFingerprint)
                    .putLong(CleanupEffectWork.RESULT_SOURCE_START_MS, key.sourceStartMs)
                    .putLong(CleanupEffectWork.RESULT_SOURCE_END_MS, key.sourceEndMs)
                    .putString(CleanupEffectWork.RESULT_CLEANUP_STRENGTH, key.cleanup.name)
                    .putBoolean(CleanupEffectWork.RESULT_NORMALIZED, key.normalized)
                    .putString(CleanupEffectWork.RESULT_ALGORITHM_VERSION, key.algorithmVersion)
                    .build(),
            )
            awaitCondition {
                vm.uiState.value.cleanup.status == EditorCleanupStatus.SUCCEEDED ||
                    vm.uiState.value.cleanup.status == EditorCleanupStatus.FAILED
            }
            assertEquals(null, vm.uiState.value.cleanup.error)
            assertTrue(vm.uiState.value.effectRecoveryClipIds.isEmpty())
            assertNotNull(vm.uiState.value.session.tracks.single().clips.single().effects.processedCacheKey)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `cleanup before save keeps cache key across private promotion and reopen`() = runBlocking {
        val external = File.createTempFile("editor-cleanup-external", ".wav")
        val privateSource = File(app.filesDir, "editor-final-cleanup/private.wav").apply {
            parentFile?.mkdirs()
        }
        writeCanonicalWav(external, 48_000)
        external.copyTo(privateSource, overwrite = true)
        val scheduler = CleanupSchedulerFake()
        var durable: EditorDraftLoad? = null
        try {
            val vm = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Converted(Uri.fromFile(external), null, "voice"),
                persistDraft = { session, _ ->
                    val promoted = session.copy(
                        id = "draft-cleanup-promotion",
                        draftId = "draft-cleanup-promotion",
                        dirty = false,
                        tracks = session.tracks.map { track ->
                            track.copy(clips = track.clips.map { clip ->
                                clip.copy(source = clip.source.copy(uri = privateSource.absolutePath))
                            })
                        },
                    )
                    EditorDraftLoad(promoted, emptyList()).also { durable = it }
                },
                loadDraft = { durable },
                sourceAnalyzer = { AudioSourceInfo("voice", 1_000L) },
                waveformLoader = { emptyList() },
                cleanupScheduler = scheduler,
            )
            vm.awaitReady()
            vm.dispatch(EditorIntent.StartCleanup("clip-1", normalize = true, preset = CleanupStrength.MEDIUM))
            val request = scheduler.awaitRequest()
            val input = request.workRequest.workSpec.input
            val key = ProcessedAudioKey(
                sourceFingerprint = input.getString(CleanupEffectWork.SOURCE_FINGERPRINT)!!,
                sourceStartMs = input.getLong(CleanupEffectWork.SOURCE_START_MS, 0L),
                sourceEndMs = input.getLong(CleanupEffectWork.SOURCE_END_MS, 0L),
                cleanup = CleanupStrength.valueOf(input.getString(CleanupEffectWork.CLEANUP_STRENGTH)!!),
                normalized = input.getBoolean(CleanupEffectWork.NORMALIZED, false),
                algorithmVersion = input.getString(CleanupEffectWork.ALGORITHM_VERSION)!!,
            )
            val cacheFile = File(app.cacheDir, "processed_audio/${key.toFilename()}.pcm")
            cacheFile.parentFile?.mkdirs()
            writeCanonicalWav(cacheFile, 48_000)
            scheduler.emitSuccess(
                request.id,
                Data.Builder()
                    .putString(CleanupEffectWork.RESULT_CACHE_KEY_FILENAME, key.toFilename())
                    .putString(CleanupEffectWork.RESULT_CACHE_KEY_FINGERPRINT, key.sourceFingerprint)
                    .putLong(CleanupEffectWork.RESULT_SOURCE_START_MS, key.sourceStartMs)
                    .putLong(CleanupEffectWork.RESULT_SOURCE_END_MS, key.sourceEndMs)
                    .putString(CleanupEffectWork.RESULT_CLEANUP_STRENGTH, key.cleanup.name)
                    .putBoolean(CleanupEffectWork.RESULT_NORMALIZED, key.normalized)
                    .putString(CleanupEffectWork.RESULT_ALGORITHM_VERSION, key.algorithmVersion)
                    .build(),
            )
            awaitCondition { vm.uiState.value.cleanup.status == EditorCleanupStatus.SUCCEEDED }

            val keyBeforeSave = vm.uiState.value.session.tracks.single().clips.single()
                .effects.processedCacheKey
            assertNotNull(keyBeforeSave)
            vm.dispatch(EditorIntent.SaveDraft())
            awaitDraft(vm, EditorDraftSaveStatus.SUCCEEDED)
            assertEquals(keyBeforeSave, vm.uiState.value.session.tracks.single().clips.single().effects.processedCacheKey)

            val reopened = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Draft("draft-cleanup-promotion"),
                loadDraft = { durable },
                sourceAnalyzer = { AudioSourceInfo("voice", 1_000L) },
                waveformLoader = { emptyList() },
            )
            reopened.awaitReady()
            assertEquals(
                keyBeforeSave,
                reopened.uiState.value.session.tracks.single().clips.single().effects.processedCacheKey,
            )
            assertTrue(reopened.uiState.value.effectRecoveryClipIds.isEmpty())
        } finally {
            external.delete()
            privateSource.delete()
        }
    }

    @Test
    fun `draft cache inspection is synchronously pending and blocks export and save`() = runBlocking {
        val source = File.createTempFile("editor-pending-source", ".wav")
        writeCanonicalWav(source, 48_000)
        val fingerprint = com.aistudio.voicenote.cvtr.editor.work.StableSourceFingerprint.compute(
            app,
            source.toURI().toString(),
            "ignored",
        )
        val durable = EditorDraftLoad(
            session = session(
                Uri.fromFile(source).toString(),
                durationMs = 1_000L,
                effects = ClipEffects(
                    processedCacheKey = ProcessedAudioKey(
                        sourceFingerprint = fingerprint,
                        sourceStartMs = 0L,
                        sourceEndMs = 1_000L,
                        cleanup = CleanupStrength.MEDIUM,
                        normalized = true,
                    ).toFilename(),
                    cleanupStrength = CleanupStrength.MEDIUM.name,
                    cleanupNormalized = true,
                    cleanupAlgorithmVersion = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
                ),
            ),
            missingPrivateSources = emptyList(),
        )
        val pendingCache = File(
            app.cacheDir,
            "processed_audio/${durable.session.tracks.single().clips.single().effects.processedCacheKey}.pcm",
        )
        pendingCache.delete()
        val sourceProbe = CompletableDeferred<AudioSourceInfo>()
        var saveAttempts = 0
        try {
            val vm = EditorViewModel(
                application = app,
                launchSource = EditorLaunchSource.Draft("draft-pending-inspection"),
                loadDraft = { durable },
                persistDraft = { _, _ ->
                    saveAttempts++
                    durable
                },
                sourceAnalyzer = { sourceProbe.await() },
                waveformLoader = { emptyList() },
            )
            awaitCondition { vm.uiState.value.cleanupInspectionPending }

            vm.dispatch(EditorIntent.StartExport)
            vm.dispatch(EditorIntent.SaveDraft())
            assertEquals(EditorExportStatus.FAILED, vm.uiState.value.export.status)
            assertEquals("Cleanup cache validation in progress", vm.uiState.value.export.error)
            assertEquals(EditorDraftSaveStatus.FAILED, vm.uiState.value.draft.status)
            assertEquals(0, saveAttempts)

            sourceProbe.complete(AudioSourceInfo("source", 1_000L))
            vm.awaitReady()
            assertFalse(vm.uiState.value.cleanupInspectionPending)
            assertTrue(vm.uiState.value.effectRecoveryClipIds.contains("clip-1"))
        } finally {
            source.delete()
            pendingCache.delete()
        }
    }

    private suspend fun awaitDraft(vm: EditorViewModel, status: EditorDraftSaveStatus) {
        awaitCondition { vm.uiState.value.draft.status == status }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(5_000L) {
            while (!predicate()) delay(10L)
        }
    }

    private fun session(
        sourceUri: String,
        durationMs: Long,
        effects: ClipEffects = ClipEffects(),
    ) = EditorSession(
        id = "draft-effects",
        draftId = "draft-effects",
        tracks = listOf(
            EditorTrack(
                id = "track-1",
                name = "Voice",
                clips = listOf(
                    AudioClip(
                        id = "clip-1",
                        source = AudioSourceRef(sourceUri, durationMs),
                        sourceStartMs = 0L,
                        sourceEndMs = durationMs,
                        timelineStartMs = 0L,
                        effects = effects,
                    ),
                ),
            ),
        ),
        selectedClipId = "clip-1",
    )

    private fun writeCanonicalWav(file: File, samples: Int) {
        val dataBytes = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(48_000)
        header.putInt(48_000 * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        FileOutputStream(file).use { output ->
            output.write(header.array())
            output.write(ByteArray(dataBytes))
        }
    }

    private class CleanupSchedulerFake : EditorCleanupScheduler {
        data class Request(val id: UUID, val workRequest: OneTimeWorkRequest)

        private val requests = MutableSharedFlow<Request>(replay = 8)
        private val observations = mutableMapOf<UUID, MutableSharedFlow<WorkInfo?>>()

        override fun enqueueUnique(uniqueName: String, request: OneTimeWorkRequest, replaceExisting: Boolean): UUID {
            observations[request.id] = MutableSharedFlow(replay = 1)
            requests.tryEmit(Request(request.id, request))
            return request.id
        }

        override fun cancel(id: UUID) = Unit

        override fun observe(id: UUID): Flow<WorkInfo?> = observations[id] ?: emptyFlow()

        suspend fun awaitRequest(): Request = withTimeout(5_000L) { requests.replayCache.lastOrNull() ?: requests.first() }

        suspend fun emitSuccess(id: UUID, output: Data) {
            observations[id]?.emit(WorkInfo(id, WorkInfo.State.SUCCEEDED, emptySet(), output))
        }
    }
}
