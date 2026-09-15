package com.aistudio.voicenote.cvtr.editor.cache

import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.audio.EDITOR_CHANNEL_COUNT
import com.aistudio.voicenote.cvtr.editor.audio.EDITOR_SAMPLE_RATE
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Versioned cache identity. Every field that can change rendered samples belongs in the key. */
internal data class ProcessedAudioKey(
    val sourceFingerprint: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val cleanup: CleanupStrength,
    val normalized: Boolean,
    val algorithmVersion: String = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
) {
    fun canonicalForm(): String = buildString {
        append(ProcessedAudioCache.KEY_FORMAT_VERSION).append('|')
        append(algorithmVersion).append('|')
        append(sourceFingerprint.length).append(':').append(sourceFingerprint).append('|')
        append(sourceStartMs).append('|').append(sourceEndMs).append('|')
        append(cleanup.name).append('|').append(normalized)
    }

    fun toFilename(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalForm().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Metadata for the only cache format accepted by the renderer. */
internal data class CachedWavMetadata(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataSize: Long,
) {
    val sampleCount: Long
        get() = dataSize / (channels * (bitsPerSample / 8).coerceAtLeast(1))
}

/**
 * Validates a canonical little-endian PCM16 mono WAV. Cache files are deliberately strict so a
 * truncated or differently formatted file cannot become an audible source.
 */
internal fun readValidatedCachedWav(
    file: File,
    expectedSampleCount: Long? = null,
): CachedWavMetadata {
    if (!file.isFile || file.length() < ProcessedAudioCache.WAV_HEADER_BYTES) {
        throw IOException("Cache output is missing or shorter than a WAV header")
    }
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(ProcessedAudioCache.WAV_HEADER_BYTES.toInt())
        raf.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, length: Int): String =
            String(header, offset, length, Charsets.US_ASCII)

        if (ascii(0, 4) != "RIFF" || ascii(8, 4) != "WAVE" ||
            ascii(12, 4) != "fmt " || ascii(36, 4) != "data"
        ) {
            throw IOException("Cache output is not a canonical WAV")
        }
        val riffSize = buffer.getInt(4).toLong() and 0xffff_ffffL
        val formatSize = buffer.getInt(16)
        val audioFormat = buffer.getShort(20).toInt() and 0xffff
        val channels = buffer.getShort(22).toInt() and 0xffff
        val sampleRate = buffer.getInt(24)
        val byteRate = buffer.getInt(28).toLong() and 0xffff_ffffL
        val blockAlign = buffer.getShort(32).toInt() and 0xffff
        val bitsPerSample = buffer.getShort(34).toInt() and 0xffff
        val dataSize = buffer.getInt(40).toLong() and 0xffff_ffffL

        if (riffSize != file.length() - 8L || formatSize != 16 || audioFormat != 1 ||
            channels != EDITOR_CHANNEL_COUNT || sampleRate != EDITOR_SAMPLE_RATE ||
            bitsPerSample != 16 || blockAlign != channels * 2 ||
            byteRate != sampleRate.toLong() * blockAlign ||
            dataSize != file.length() - ProcessedAudioCache.WAV_HEADER_BYTES ||
            dataSize % blockAlign != 0L
        ) {
            throw IOException("Cache WAV format or data size is invalid")
        }

        val metadata = CachedWavMetadata(sampleRate, channels, bitsPerSample, dataSize)
        if (expectedSampleCount != null && metadata.sampleCount != expectedSampleCount) {
            throw IOException(
                "Cache sample count ${metadata.sampleCount} does not match $expectedSampleCount",
            )
        }
        return metadata
    }
}

internal class ProcessedAudioCache(private val cacheDir: File) {
    private val accessClock = AtomicLong(System.currentTimeMillis())
    private val rootState: SharedRootState

    init {
        check(cacheDir.exists() || cacheDir.mkdirs()) { "Unable to create cache directory" }
        rootState = roots.computeIfAbsent(cacheDir.canonicalFile.path) { SharedRootState() }
    }

    /** Finds only a valid cache file and updates its persisted access time for LRU eviction. */
    fun find(key: ProcessedAudioKey, expectedSampleCount: Long? = null): File? {
        val file = activeFile(key)
        if (!file.isFile) return null
        return try {
            readValidatedCachedWav(file, expectedSampleCount)
            touch(file)
            file
        } catch (_: Throwable) {
            // Invalid cache data is rebuildable. Do not let it be opened by the renderer.
            runCatching { file.delete() }
            null
        }
    }

    /**
     * Copies a validated output into an isolated, fsynced staging file and atomically replaces the
     * active file. If the platform cannot provide an atomic replacement, this fails without
     * deleting or copying over the active file.
     */
    fun commit(
        key: ProcessedAudioKey,
        sourceFile: File,
        expectedSampleCount: Long? = null,
    ): File {
        val metadata = readValidatedCachedWav(sourceFile, expectedSampleCount)
        require(metadata.sampleRate == EDITOR_SAMPLE_RATE)
        val active = activeFile(key)
        val staging = createActiveTemp("${key.toFilename()}-")
        try {
            sourceFile.inputStream().use { input ->
                java.io.FileOutputStream(staging).use { output ->
                    input.copyTo(output)
                    output.flush()
                    // A commit is not durable until the bytes copied to the cache are on disk.
                    output.fd.sync()
                }
            }
            readValidatedCachedWav(staging, expectedSampleCount)
            atomicReplace(staging, active)
            touch(active)
            return active
        } catch (error: Throwable) {
            // Never remove the active file here: it may be the last valid result.
            runCatching { staging.delete() }
            throw error
        } finally {
            unregisterActiveTemp(staging)
            runCatching { staging.delete() }
        }
    }

    fun retain(key: ProcessedAudioKey) {
        synchronized(rootState.lock) {
            rootState.references[key.toFilename()] =
                (rootState.references[key.toFilename()] ?: 0) + 1
        }
    }

    fun release(key: ProcessedAudioKey) {
        synchronized(rootState.lock) {
            val filename = key.toFilename()
            val newCount = (rootState.references[filename] ?: 0) - 1
            if (newCount <= 0) rootState.references.remove(filename)
            else rootState.references[filename] = newCount
        }
    }

    fun isRetained(key: ProcessedAudioKey): Boolean = synchronized(rootState.lock) {
        rootState.references.containsKey(key.toFilename())
    }

    fun evictToSize(maxBytes: Long) {
        val files = cacheDir.listFiles { _, name -> name.endsWith(".pcm") }?.toList() ?: emptyList()
        val sortedFiles = files.sortedBy { it.lastModified() }
        var currentSize = files.sumOf { it.length() }
        for (file in sortedFiles) {
            if (currentSize <= maxBytes) break
            val retained = synchronized(rootState.lock) {
                rootState.references.containsKey(file.name.removeSuffix(".pcm"))
            }
            if (!retained) {
                currentSize -= file.length()
                runCatching { file.delete() }
            }
        }
    }

    fun partialFiles(): List<File> =
        cacheDir.listFiles { _, name -> name.endsWith(".partial") }?.toList() ?: emptyList()

    /** Creates and registers a worker-owned partial while holding the same root lock as cleanup. */
    fun createActiveTemp(prefix: String): File = synchronized(rootState.lock) {
        File.createTempFile(prefix, ".partial", cacheDir).also {
            rootState.activeTemps += it.canonicalFile.path
        }
    }

    fun registerActiveTemp(file: File) {
        synchronized(rootState.lock) {
            rootState.activeTemps += file.canonicalFile.path
        }
    }

    fun unregisterActiveTemp(file: File) {
        synchronized(rootState.lock) {
            rootState.activeTemps -= file.canonicalFile.path
        }
    }

    fun clearPartials(
        maxAgeMs: Long = PARTIAL_RETENTION_MS,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        require(maxAgeMs >= 0L) { "maxAgeMs must be non-negative" }
        val cutoff = nowMs - maxAgeMs
        partialFiles().forEach { file ->
            val active = synchronized(rootState.lock) {
                rootState.activeTemps.contains(file.canonicalFile.path)
            }
            if (!active && file.lastModified() <= cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    private fun activeFile(key: ProcessedAudioKey): File =
        File(cacheDir, "${key.toFilename()}.pcm")

    private fun touch(file: File) {
        val timestamp = maxOf(System.currentTimeMillis(), accessClock.incrementAndGet())
        accessClock.updateAndGet { maxOf(it, timestamp) }
        // Access metadata is best effort. A read-only cache must remain usable even if its
        // filesystem refuses timestamp updates.
        file.setLastModified(timestamp)
    }

    private fun atomicReplace(source: File, target: File) {
        // android.system.Os.rename is an atomic same-filesystem replacement on API 21+. Resolve
        // it reflectively so the class verifier does not add a newer java.nio requirement to the
        // API-24 app. There is intentionally no copy/delete fallback.
        try {
            val osClass = Class.forName("android.system.Os")
            val rename = osClass.getMethod("rename", String::class.java, String::class.java)
            rename.invoke(null, source.absolutePath, target.absolutePath)
        } catch (error: ClassNotFoundException) {
            // JVM-only tests do not expose android.system.Os. A same-directory File.renameTo is
            // still one filesystem rename and is accepted only when the OS reports success.
            if (!source.renameTo(target)) {
                throw IOException("Atomic cache replacement is unavailable", error)
            }
        } catch (error: java.lang.reflect.InvocationTargetException) {
            // Robolectric may expose Os without implementing its native rename. Keep the same
            // no-copy/no-delete rule and accept only a successful same-directory rename.
            if (!source.renameTo(target)) {
                throw IOException("Atomic cache replacement failed", error.cause ?: error)
            }
        } catch (error: ReflectiveOperationException) {
            if (!source.renameTo(target)) {
                throw IOException("Atomic cache replacement is unavailable", error)
            }
        }
    }

    companion object {
        const val KEY_FORMAT_VERSION = "processed-audio-key-v2"
        const val CACHE_ALGORITHM_VERSION = "cleanup-rnnoise-peak-v1"
        const val WAV_HEADER_BYTES = 44L
        const val PARTIAL_RETENTION_MS = 24L * 60L * 60L * 1_000L

        private val roots = ConcurrentHashMap<String, SharedRootState>()
    }

    private class SharedRootState {
        val lock = Any()
        val references = mutableMapOf<String, Int>()
        val activeTemps = mutableSetOf<String>()
    }
}
