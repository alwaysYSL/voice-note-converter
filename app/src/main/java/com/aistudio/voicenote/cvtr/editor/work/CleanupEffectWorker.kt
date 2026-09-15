package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
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
            renderFile = File.createTempFile(
                "cleanup-${key.toFilename()}-${id}-",
                ".partial",
                cacheDir,
            )
            renderPass(
                output = renderFile,
                sourceUri = sourceUri,
                startFrame = startFrame,
                totalFrames = totalFrames,
                cleanupStrength = cleanupStrength,
            )

            val finalFile = if (normalized) {
                normalizedFile = File.createTempFile(
                    "cleanup-normalized-${key.toFilename()}-${id}-",
                    ".partial",
                    cacheDir,
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
            renderFile?.let { runCatching { it.delete() } }
            normalizedFile?.let { runCatching { it.delete() } }
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
        val noiseProcessor = cleanupStrength
            .takeUnless { it == CleanupStrength.OFF }
            ?.let { RnNoiseProcessor() }
        var framesRead = 0L
        var framesWritten = 0L
        try {
            reader.use { source ->
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
            }
            writeWavHeader(output, framesWritten)
        } finally {
            noiseProcessor?.close()
        }
    }

    private fun normalizePass(input: File, output: File, expectedFrames: Long): File {
        val metadata = readValidatedCachedWav(input, expectedFrames)
        val stats = PeakNormalizer.analyze(readWavChunks(input, metadata.dataSize))
        var framesWritten = 0L
        FileOutputStream(output).use { stream ->
            stream.write(ByteArray(WAV_HEADER_BYTES))
            for (chunk in readWavChunks(input, metadata.dataSize)) {
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

/** Stable content-aware fingerprint without retaining an entire media file in memory. */
private object StableSourceFingerprint {
    private const val VERSION = "source-fingerprint-v2"
    private const val SAMPLE_BYTES = 64 * 1024

    fun compute(context: Context, sourceUri: String, requested: String): String {
        if (requested.startsWith("$VERSION:")) return requested
        val digest = MessageDigest.getInstance("SHA-256")
        val uri = Uri.parse(sourceUri)
        val metadata = queryMetadata(context, uri, sourceUri)
        digest.update("$VERSION|$sourceUri|$metadata".toByteArray(Charsets.UTF_8))
        runCatching {
            openStream(context, uri, sourceUri)?.use { stream ->
                val head = readAtMost(stream, SAMPLE_BYTES)
                digest.update("|head|".toByteArray(Charsets.UTF_8))
                digest.update(head)
                val size = metadata.substringBefore('|').toLongOrNull()
                if (size != null && size > SAMPLE_BYTES) {
                    // The stream is positioned after the head sample. Skip to the start of the
                    // tail, leaving one bounded sample at each end of the source.
                    skipFully(stream, (size - 2L * SAMPLE_BYTES).coerceAtLeast(0L))
                    val tail = readAtMost(stream, SAMPLE_BYTES)
                    digest.update("|tail|".toByteArray(Charsets.UTF_8))
                    digest.update(tail)
                }
            }
        }.onFailure {
            // Metadata remains a stable identity; decoder failure is reported by the render pass.
            digest.update("|unreadable|".toByteArray(Charsets.UTF_8))
        }
        return "$VERSION:" + digest.digest().toHex()
    }

    private fun queryMetadata(context: Context, uri: Uri, fallback: String): String {
        val file = if (uri.scheme.isNullOrBlank()) File(fallback) else null
        val size = file?.takeIf { it.isFile }?.length()
            ?: runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it >= 0L } else null
                    }
            }.getOrNull()
            ?: -1L
        val modified = file?.takeIf { it.isFile }?.lastModified() ?: 0L
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return "$size|$modified|$type"
    }

    private fun openStream(context: Context, uri: Uri, fallback: String): InputStream? =
        if (uri.scheme.isNullOrBlank()) File(fallback).inputStream() else
            context.contentResolver.openInputStream(uri)

    private fun readAtMost(stream: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(maxBytes)
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (stream.read() < 0) {
                return
            } else {
                remaining--
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
