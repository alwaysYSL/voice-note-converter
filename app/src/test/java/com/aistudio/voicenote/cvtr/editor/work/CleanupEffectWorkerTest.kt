package com.aistudio.voicenote.cvtr.editor.work

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
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class CleanupEffectWorkerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `worker fails if source uri is missing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val input = androidx.work.Data.Builder().build()

        val worker = CleanupEffectWorker(context, workerParameters(input))
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `source fingerprint hashes the full stream and ignores caller value`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = tempFolder.newFile("source.raw")
        source.writeBytes(ByteArray(256 * 1024) { (it * 31).toByte() })

        val sourceUri = source.toURI().toString()
        val before = StableSourceFingerprint.compute(context, sourceUri, "source-fingerprint-v2:unverified")
        RandomAccessFile(source, "rw").use { file ->
            file.seek(source.length() / 2L)
            file.writeByte(0x7f)
        }
        val after = StableSourceFingerprint.compute(context, sourceUri, "source-fingerprint-v2:unverified")

        assertNotEquals(before, after)
    }

    @Test
    fun `byte-identical sources share a processed key regardless of path`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = tempFolder.newFile("source-first.raw")
        val second = tempFolder.newFile("source-second.raw")
        val bytes = ByteArray(256 * 1024) { (it * 17).toByte() }
        first.writeBytes(bytes)
        second.writeBytes(bytes)

        val firstFingerprint = StableSourceFingerprint.compute(context, first.toURI().toString(), "ignored")
        val secondFingerprint = StableSourceFingerprint.compute(context, second.toURI().toString(), "ignored")
        val firstKey = ProcessedAudioKey(
            sourceFingerprint = firstFingerprint,
            sourceStartMs = 0L,
            sourceEndMs = 1_000L,
            cleanup = CleanupStrength.MEDIUM,
            normalized = true,
        ).toFilename()
        val secondKey = ProcessedAudioKey(
            sourceFingerprint = secondFingerprint,
            sourceStartMs = 0L,
            sourceEndMs = 1_000L,
            cleanup = CleanupStrength.MEDIUM,
            normalized = true,
        ).toFilename()

        assertEquals(firstFingerprint, secondFingerprint)
        assertEquals(firstKey, secondKey)
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
            ): ListenableWorker? = null
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
            kotlinx.coroutines.Dispatchers.Default,
            taskExecutor,
            workerFactory,
            progress,
            foreground,
        )
    }
}
