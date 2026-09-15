package com.aistudio.voicenote.cvtr.editor.cache

import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class ProcessedAudioKey(
    val sourceFingerprint: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val cleanup: CleanupStrength,
    val normalized: Boolean
) {
    fun toFilename(): String {
        val raw = "$sourceFingerprint-$sourceStartMs-$sourceEndMs-${cleanup.name}-$normalized"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal class ProcessedAudioCache(private val cacheDir: File) {
    private val references = ConcurrentHashMap<String, Int>()

    init {
        cacheDir.mkdirs()
    }

    fun find(key: ProcessedAudioKey): File? {
        val file = File(cacheDir, "${key.toFilename()}.pcm")
        return if (file.exists()) file else null
    }

    fun commit(key: ProcessedAudioKey, sourceFile: File): File {
        val pcmFile = File(cacheDir, "${key.toFilename()}.pcm")
        
        // Very basic validation - must be at least wav header size
        if (sourceFile.length() < 44) {
            throw IllegalArgumentException("Invalid PCM file")
        }
        
        val partialFile = File(cacheDir, "${key.toFilename()}.partial")
        if (partialFile.exists()) {
            partialFile.delete()
        }
        sourceFile.copyTo(partialFile)
        
        // fsync / sync to disk is handled by copyTo usually, but renameTo is atomic
        if (!partialFile.renameTo(pcmFile)) {
            // fallback
            partialFile.copyTo(pcmFile, overwrite = true)
            partialFile.delete()
        }
        
        return pcmFile
    }

    fun retain(key: ProcessedAudioKey) {
        references.compute(key.toFilename()) { _, count -> (count ?: 0) + 1 }
    }

    fun release(key: ProcessedAudioKey) {
        references.compute(key.toFilename()) { _, count -> 
            val newCount = (count ?: 0) - 1
            if (newCount <= 0) null else newCount
        }
    }

    fun isRetained(key: ProcessedAudioKey): Boolean {
        return references.containsKey(key.toFilename())
    }

    fun evictToSize(maxBytes: Long) {
        val files = cacheDir.listFiles { _, name -> name.endsWith(".pcm") }?.toList() ?: emptyList()
        val sortedFiles = files.sortedBy { it.lastModified() }
        
        var currentSize = files.sumOf { it.length() }
        
        for (file in sortedFiles) {
            if (currentSize <= maxBytes) break
            
            val keyFilename = file.name.removeSuffix(".pcm")
            if (!references.containsKey(keyFilename)) {
                currentSize -= file.length()
                file.delete()
            }
        }
    }

    fun partialFiles(): List<File> {
        return cacheDir.listFiles { _, name -> name.endsWith(".partial") }?.toList() ?: emptyList()
    }

    fun clearPartials() {
        partialFiles().forEach { it.delete() }
    }
}
