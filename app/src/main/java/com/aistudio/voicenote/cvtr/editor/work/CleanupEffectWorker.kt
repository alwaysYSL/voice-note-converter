package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.audio.EDITOR_SAMPLE_RATE
import com.aistudio.voicenote.cvtr.editor.audio.MediaCodecPcmSourceReaderFactory
import com.aistudio.voicenote.cvtr.editor.audio.PeakNormalizer
import com.aistudio.voicenote.cvtr.editor.audio.RnNoiseProcessor
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import com.aistudio.voicenote.cvtr.editor.cache.readValidatedCachedWav
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal class CleanupEffectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUri = inputData.getString(CleanupEffectWork.SOURCE_URI)
            ?: return@withContext failure("Missing sourceUri")
        val requestedFingerprint = inputData.getString(CleanupEffectWork.SOURCE_FINGERPRINT)
            ?: return@withContext failure("Missing fingerprint")
        val startMs = inputData.getLong(CleanupEffectWork.SOURCE_START_MS, 0L)
        val endMs = inputData.getLong(CleanupEffectWork.SOURCE_END_MS, 0L)
        val cleanupStrength = runCatching {
            CleanupStrength.valueOf(
                inputData.getString(CleanupEffectWork.CLEANUP_STRENGTH) ?: CleanupStrength.OFF.name,
            )
        }.getOrElse { return@withContext failure("Invalid cleanup strength") }
        val normalized = inputData.getBoolean(CleanupEffectWork.NORMALIZED, false)
        val algorithmVersion = inputData.getString(CleanupEffectWork.ALGORITHM_VERSION)
            ?: ProcessedAudioCache.CACHE_ALGORITHM_VERSION

        val sourceFingerprint = StableSourceFingerprint.compute(
            applicationContext,
            sourceUri,
            requestedFingerprint,
        )
        // A verified caller fingerprint is an optimistic concurrency token, never the cache-key
        // source. Recompute from bytes and reject a changed source before rendering.
        if (requestedFingerprint.startsWith("source-content-sha256-v1:") &&
            requestedFingerprint != sourceFingerprint
        ) {
            return@withContext failure("Source changed before cleanup started")
        }
        val key = ProcessedAudioKey(
            sourceFingerprint = sourceFingerprint,
            sourceStartMs = startMs,
            sourceEndMs = endMs,
            cleanup = cleanupStrength,
            normalized = normalized,
            algorithmVersion = algorithmVersion,
        )
        val cacheDir = File(applicationContext.cacheDir, "processed_audio")
        val cache = ProcessedAudioCache(cacheDir)
        val startFrame = startMs.toFramesOrNull()
        val endFrame = endMs.toFramesOrNull()
        if (startFrame == null || endFrame == null || endFrame <= startFrame) {
            return@withContext failure("Invalid range")
        }
        val totalFrames = endFrame - startFrame
        if (cache.find(key, expectedSampleCount = totalFrames) != null) {
            return@withContext Result.success(createResultData(key))
        }
        val requiredBytes = estimateRequiredBytes(totalFrames, normalized)
        if (cacheDir.usableSpace < requiredBytes) {
            return@withContext failure("Insufficient storage for cleanup render")
        }

        var renderFile: File? = null
        var normalizedFile: File? = null
        try {
            // Every worker attempt gets its own namespaced temporary files. A retry or a second
            // clip can never write into another attempt's in-progress output.
            renderFile = cache.createActiveTemp(
                "cleanup-${key.toFilename()}-${id}-attempt$runAttemptCount-",
            )
            renderPass(
                output = renderFile,
                sourceUri = sourceUri,
                startFrame = startFrame,
                totalFrames = totalFrames,
                cleanupStrength = cleanupStrength,
            )

            val finalFile = if (normalized) {
                normalizedFile = cache.createActiveTemp(
                    "cleanup-normalized-${key.toFilename()}-${id}-attempt$runAttemptCount-",
                )
                normalizePass(renderFile, normalizedFile!!, totalFrames)
                normalizedFile!!
            } else {
                renderFile
            }

            // This check is intentionally immediately before commit. A cancellation after the
            // last decoder read must not publish a partial/obsolete cache entry.
            checkActive()
            cache.commit(key, finalFile, expectedSampleCount = totalFrames)
            Result.success(createResultData(key))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            failure(error.message ?: "Cleanup render failed")
        } finally {
            renderFile?.let {
                cache.unregisterActiveTemp(it)
                runCatching { it.delete() }
            }
            normalizedFile?.let {
                cache.unregisterActiveTemp(it)
                runCatching { it.delete() }
            }
        }
    }

    private suspend fun renderPass(
        output: File,
        sourceUri: String,
        startFrame: Long,
        totalFrames: Long,
        cleanupStrength: CleanupStrength,
    ) {
        val reader = MediaCodecPcmSourceReaderFactory(applicationContext)
            .open(AudioSourceRef(sourceUri))
        var framesRead = 0L
        var framesWritten = 0L
        reader.use { source ->
            val noiseProcessor = cleanupStrength
                .takeUnless { it == CleanupStrength.OFF }
                ?.let { RnNoiseProcessor() }
            try {
                FileOutputStream(output).use { stream ->
                    stream.write(ByteArray(WAV_HEADER_BYTES))
                    while (framesRead < totalFrames) {
                        checkActive()
                        val requested = minOf(MAX_SOURCE_READ_FRAMES.toLong(), totalFrames - framesRead).toInt()
                        val chunk = source.read(startFrame + framesRead, requested)
                        if (chunk.isEmpty()) {
                            throw IOException("Source ended after $framesRead of $totalFrames frames")
                        }
                        if (chunk.size > requested) {
                            throw IOException("Source returned more frames than requested")
                        }
                        val processed = noiseProcessor?.process(chunk, cleanupStrength) ?: chunk
                        framesRead += chunk.size
                        framesWritten += writeSamples(stream, processed, totalFrames - framesWritten)
                        setProgress(workDataOf(
                            CleanupEffectWork.PROGRESS to
                                (framesWritten.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f),
                            CleanupEffectWork.PROGRESS_FRAMES to framesWritten,
                            CleanupEffectWork.TOTAL_FRAMES to totalFrames,
                        ))
                    }

                    // Flush emits only the real buffered tail; zero padding is never published.
                    val flushed = noiseProcessor?.flush(cleanupStrength) ?: ShortArray(0)
                    framesWritten += writeSamples(stream, flushed, totalFrames - framesWritten)
                    if (framesRead != totalFrames || framesWritten != totalFrames) {
                        throw IOException(
                            "Rendered $framesWritten output frames from $framesRead input frames; expected $totalFrames",
                        )
                    }
                    stream.flush()
                    stream.fd.sync()
                }
            } finally {
                noiseProcessor?.close()
            }
        }
        writeWavHeader(output, framesWritten)
    }

    private suspend fun normalizePass(input: File, output: File, expectedFrames: Long): File {
        val metadata = readValidatedCachedWav(input, expectedFrames)
        checkActive()
        val stats = PeakNormalizer.analyze(readWavChunks(input, metadata.dataSize))
        var framesWritten = 0L
        FileOutputStream(output).use { stream ->
            stream.write(ByteArray(WAV_HEADER_BYTES))
            for (chunk in readWavChunks(input, metadata.dataSize)) {
                checkActive()
                val normalized = PeakNormalizer.apply(chunk, stats)
                framesWritten += writeSamples(stream, normalized, expectedFrames - framesWritten)
            }
            if (framesWritten != expectedFrames) {
                throw IOException("Normalization produced $framesWritten of $expectedFrames frames")
            }
            stream.flush()
            stream.fd.sync()
        }
        writeWavHeader(output, framesWritten)
        readValidatedCachedWav(output, expectedFrames)
        return output
    }

    private fun readWavChunks(file: File, dataSize: Long): Sequence<ShortArray> = sequence {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(WAV_HEADER_BYTES.toLong())
            var remaining = dataSize
            val buffer = ByteArray(MAX_SOURCE_READ_FRAMES * 2)
            while (remaining > 0L) {
                val requestedBytes = minOf(remaining, buffer.size.toLong()).toInt()
                raf.readFully(buffer, 0, requestedBytes)
                val shorts = ShortArray(requestedBytes / 2)
                ByteBuffer.wrap(buffer, 0, requestedBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(shorts)
                yield(shorts)
                remaining -= requestedBytes
            }
        }
    }

    private fun writeSamples(
        stream: FileOutputStream,
        samples: ShortArray,
        remainingFrames: Long,
    ): Long {
        if (samples.size.toLong() > remainingFrames) {
            throw IOException("Effect output exceeded expected frame count")
        }
        if (samples.isEmpty()) return 0L
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putShort(it) }
        stream.write(bytes.array())
        return samples.size.toLong()
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("Cleanup render cancelled")
    }

    private fun createResultData(key: ProcessedAudioKey): Data = Data.Builder()
        .putString(CleanupEffectWork.RESULT_CACHE_KEY_FILENAME, key.toFilename())
        .putString(CleanupEffectWork.RESULT_CACHE_KEY_FINGERPRINT, key.sourceFingerprint)
        .putLong(CleanupEffectWork.RESULT_SOURCE_START_MS, key.sourceStartMs)
        .putLong(CleanupEffectWork.RESULT_SOURCE_END_MS, key.sourceEndMs)
        .putString(CleanupEffectWork.RESULT_CLEANUP_STRENGTH, key.cleanup.name)
        .putBoolean(CleanupEffectWork.RESULT_NORMALIZED, key.normalized)
        .putString(CleanupEffectWork.RESULT_ALGORITHM_VERSION, key.algorithmVersion)
        .build()

    private fun failure(message: String): Result =
        Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, message).build())

    private fun writeWavHeader(file: File, sampleCount: Long) {
        val dataSize = sampleCount.checkedMultiply(2L)
        val riffSize = 36L.checkedAdd(dataSize)
        require(dataSize <= 0xffff_ffffL) { "WAV output is too large" }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0L)
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(riffSize.toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(EDITOR_SAMPLE_RATE)
            header.putInt(EDITOR_SAMPLE_RATE * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize.toInt())
            raf.write(header.array())
            raf.fd.sync()
        }
    }

    private fun Long.toFramesOrNull(): Long? =
        if (this < 0L || this > Long.MAX_VALUE / EDITOR_SAMPLE_RATE) null
        else this * EDITOR_SAMPLE_RATE / 1_000L

    private fun Long.checkedMultiply(value: Long): Long =
        if (this < 0L || value < 0L || this > Long.MAX_VALUE / value) {
            throw IOException("Cleanup output size overflow")
        } else this * value

    private fun Long.checkedAdd(value: Long): Long =
        if (value < 0L || this > Long.MAX_VALUE - value) {
            throw IOException("Cleanup output size overflow")
        } else this + value

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val MAX_SOURCE_READ_FRAMES = 1_920
    }
}

internal fun estimateRequiredBytes(totalFrames: Long, normalized: Boolean): Long {
    val pcmBytes = totalFrames.checkedMultiplyForEstimate(2L)
    // Input render + optional normalized render + cache staging copy, plus recovery headroom.
    val copies = if (normalized) 3L else 2L
    return pcmBytes.checkedMultiplyForEstimate(copies)
        .checkedAddForEstimate(44L * copies)
        .checkedAddForEstimate(1L * 1024L * 1024L)
}

private fun Long.checkedMultiplyForEstimate(value: Long): Long =
    if (this < 0L || value < 0L || this > Long.MAX_VALUE / value) Long.MAX_VALUE else this * value

private fun Long.checkedAddForEstimate(value: Long): Long =
    if (value < 0L || this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

/** Stable content identity without retaining an entire media file in memory. */
internal object StableSourceFingerprint {
    private const val VERSION = "source-content-sha256-v1"
    private const val HASH_BUFFER_BYTES = 64 * 1024

    @Suppress("UNUSED_PARAMETER")
    fun compute(context: Context, sourceUri: String, requested: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val uri = Uri.parse(sourceUri)
        val stream = runCatching { openStream(context, uri, sourceUri) }.getOrNull()
            ?: return "$VERSION:unreadable"
        return try {
            stream.use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
            // The identity deliberately contains only the complete source bytes. URI, path,
            // display name, provider metadata, and caller-supplied tokens are not identity data.
            "$VERSION:" + digest.digest().toHex()
        } catch (_: Throwable) {
            // An unreadable source cannot produce a verified content identity. Keep this marker
            // path-free so it can never alias a valid cache entry from another source.
            "$VERSION:unreadable"
        }
    }

    private fun openStream(context: Context, uri: Uri, fallback: String): InputStream? {
        // Draft sources are persisted as plain paths. Check the path first so a Windows drive
        // letter is not misread by Uri.parse as a URI scheme during JVM validation.
        File(fallback).takeIf { it.isFile }?.let { return it.inputStream() }
        return if (uri.scheme.isNullOrBlank()) null else context.contentResolver.openInputStream(uri)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
