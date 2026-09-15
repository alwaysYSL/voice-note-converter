package com.aistudio.voicenote.cvtr.editor.cache

import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProcessedAudioCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var cache: ProcessedAudioCache

    private val key = ProcessedAudioKey("fingerprint1", 0L, 1000L, CleanupStrength.MEDIUM, true)
    private val referencedKey = ProcessedAudioKey("fingerprint_ref", 0L, 1000L, CleanupStrength.MEDIUM, true)
    private val unreferencedKey = ProcessedAudioKey("fingerprint_unref", 0L, 1000L, CleanupStrength.MEDIUM, true)
    private lateinit var validFixture: File

    @Before
    fun setup() {
        cacheDir = tempFolder.newFolder("cache")
        cache = ProcessedAudioCache(cacheDir)
        
        validFixture = tempFolder.newFile("valid.pcm")
        // valid PCM requires 44 bytes header + sample count
        val pcmHeader = ByteArray(44)
        // Set RIFF, WAVE, fmt, data, etc. if validation is strict, but let's assume it checks simple size or we write a dummy valid wav header
        ByteBuffer.wrap(pcmHeader).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36) // size
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(48000) // Sample rate
            putInt(48000 * 2) // Byte rate
            putShort(2) // Block align
            putShort(16) // Bits per sample
            put("data".toByteArray())
            putInt(0) // 0 samples
        }
        validFixture.writeBytes(pcmHeader)
    }

    @Test
    fun `failed render never replaces active cache`() = runTest {
        cache.commit(key, validFixture)
        
        // Simulating a failed worker by writing to a partial file and failing
        val partialFile = File(cacheDir, "${key.toFilename()}.partial")
        partialFile.writeText("corrupted data")
        partialFile.setLastModified(System.currentTimeMillis() - ProcessedAudioCache.PARTIAL_RETENTION_MS - 1L)
        
        // The active cache should still be the original
        assertEquals(File(cacheDir, "${key.toFilename()}.pcm").absolutePath, cache.find(key)?.absolutePath)
        
        // Simulating cleanup
        cache.clearPartials()
        assertTrue(cache.partialFiles().isEmpty())
        assertFalse(partialFile.exists())
    }

    @Test
    fun `active partial survives cleanup and aged orphan is removed`() {
        val active = File(cacheDir, "active.partial").apply {
            writeText("worker output")
            setLastModified(System.currentTimeMillis() - ProcessedAudioCache.PARTIAL_RETENTION_MS - 1L)
        }

        cache.registerActiveTemp(active)
        cache.clearPartials()
        assertTrue(active.exists())

        cache.unregisterActiveTemp(active)
        cache.clearPartials()
        assertFalse(active.exists())
    }

    @Test
    fun `eviction keeps referenced entries`() {
        cache.commit(referencedKey, validFixture)
        cache.commit(unreferencedKey, validFixture)
        
        cache.retain(referencedKey)
        
        cache.evictToSize(0) // Evict everything unreferenced
        
        assertNotNull(cache.find(referencedKey))
        assertNull(cache.find(unreferencedKey))
    }

    @Test
    fun `retain count is shared across cache instances`() {
        cache.commit(referencedKey, validFixture)
        val secondCache = ProcessedAudioCache(cacheDir)
        secondCache.retain(referencedKey)

        cache.evictToSize(0)

        assertNotNull(cache.find(referencedKey))
        secondCache.release(referencedKey)
        cache.evictToSize(0)
        assertNull(cache.find(referencedKey))
    }

    @Test
    fun `export lease protects cache until terminal release`() {
        cache.commit(referencedKey, validFixture)
        val filename = referencedKey.toFilename()

        cache.acquireLease("export-attempt-1", mapOf(filename to 1))
        cache.evictToSize(0)
        assertNotNull(cache.find(referencedKey))

        cache.releaseLease("export-attempt-1")
        cache.evictToSize(0)
        assertNull(cache.find(referencedKey))
    }

    @Test
    fun `startup removes aged orphan partials`() {
        val startupCacheDir = tempFolder.newFolder("startup-cache")
        val orphan = File(startupCacheDir, "orphan.partial").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - ProcessedAudioCache.PARTIAL_RETENTION_MS - 1L)
        }

        ProcessedAudioCache(startupCacheDir)

        assertFalse(orphan.exists())
    }

    @Test
    fun `corrupt output never replaces valid active cache`() {
        val active = cache.commit(key, validFixture)
        val before = active.readBytes()
        val corrupt = tempFolder.newFile("corrupt.pcm").apply {
            writeBytes(ByteArray(44))
        }

        try {
            cache.commit(key, corrupt)
            fail("corrupt output must be rejected")
        } catch (_: IllegalArgumentException) {
            // The active cache remains untouched.
        } catch (_: java.io.IOException) {
            // The active cache remains untouched.
        }

        assertArrayEquals(before, active.readBytes())
        assertEquals(active.absolutePath, cache.find(key)?.absolutePath)
        assertTrue(cache.partialFiles().isEmpty())
    }

    @Test
    fun `key includes version and every processing option`() {
        val same = key.copy()
        val changedAlgorithm = key.copy(algorithmVersion = "cleanup-rnnoise-peak-v2")
        val changedMode = key.copy(normalized = false)

        assertEquals(same.toFilename(), key.toFilename())
        assertNotEquals(key.toFilename(), changedAlgorithm.toFilename())
        assertNotEquals(key.toFilename(), changedMode.toFilename())
    }
}
