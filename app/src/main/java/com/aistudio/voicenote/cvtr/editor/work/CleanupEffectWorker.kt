package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
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
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class CleanupEffectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sourceUri = inputData.getString(CleanupEffectWork.SOURCE_URI)
            ?: return@withContext Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, "Missing sourceUri").build())
        val sourceFingerprint = inputData.getString(CleanupEffectWork.SOURCE_FINGERPRINT)
            ?: return@withContext Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, "Missing fingerprint").build())
        val startMs = inputData.getLong(CleanupEffectWork.SOURCE_START_MS, 0L)
        val endMs = inputData.getLong(CleanupEffectWork.SOURCE_END_MS, 0L)
        val cleanupStr = inputData.getString(CleanupEffectWork.CLEANUP_STRENGTH) ?: CleanupStrength.OFF.name
        val cleanupStrength = CleanupStrength.valueOf(cleanupStr)
        val normalized = inputData.getBoolean(CleanupEffectWork.NORMALIZED, false)

        val key = ProcessedAudioKey(sourceFingerprint, startMs, endMs, cleanupStrength, normalized)
        val cache = ProcessedAudioCache(File(applicationContext.cacheDir, "processed_audio"))
        
        // If it's already in cache, return success
        if (cache.find(key) != null) {
            return@withContext Result.success(createResultData(key))
        }

        val startFrame = startMs * EDITOR_SAMPLE_RATE / 1000L
        val endFrame = endMs * EDITOR_SAMPLE_RATE / 1000L
        val totalFrames = endFrame - startFrame
        
        if (totalFrames <= 0) {
            return@withContext Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, "Invalid range").build())
        }

        val tempFile = File(applicationContext.cacheDir, "temp_render_${key.toFilename()}.pcm")

        try {
            val reader = MediaCodecPcmSourceReaderFactory(applicationContext).open(AudioSourceRef(sourceUri))
            val noiseProcessor = if (cleanupStrength != CleanupStrength.OFF) RnNoiseProcessor() else null
            
            reader.use { r ->
                noiseProcessor?.use { np ->
                    // Two passes for normalization, or one pass if just chunking?
                    // The brief says "using RnNoiseProcessor and PeakNormalizer from Task 1"
                    // If normalized is true, we should run analyze first, or can we just process and normalize?
                    // Actually, if we do two passes, we'd have to read from decoder twice which is slow.
                    // Let's check PeakNormalizer. It takes Sequence<ShortArray> for analyze.
                    // We can write to a temporary file first, then analyze, then normalize?
                    // Or since we have to process through RnNoise, maybe process to temp file, then analyze temp file, then normalize?
                    // Let's just process to tempFile directly.
                    
                    var framesRead = 0L
                    FileOutputStream(tempFile).use { out ->
                        // write dummy wav header
                        val header = ByteArray(44)
                        out.write(header)
                        
                        while (framesRead < totalFrames) {
                            if (isStopped) {
                                return@withContext Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, "Cancelled").build())
                            }
                            val toRead = minOf(1920L, totalFrames - framesRead).toInt()
                            val chunk = r.read(startFrame + framesRead, toRead)
                            if (chunk.isEmpty()) break
                            
                            val processed = np?.process(chunk, cleanupStrength) ?: chunk
                            
                            val byteBuffer = ByteBuffer.allocate(processed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (s in processed) byteBuffer.putShort(s)
                            out.write(byteBuffer.array())
                            
                            framesRead += chunk.size
                        }
                        
                        val flushed = np?.flush(cleanupStrength)
                        if (flushed != null && flushed.isNotEmpty()) {
                            val byteBuffer = ByteBuffer.allocate(flushed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (s in flushed) byteBuffer.putShort(s)
                            out.write(byteBuffer.array())
                            framesRead += flushed.size
                        }
                    }
                    
                    // Now, if normalized is true, we need to read from tempFile, analyze, and write to a second temp file?
                    // Actually, let's look at PeakNormalizer.
                    val finalFile = if (normalized) {
                        val normTempFile = File(applicationContext.cacheDir, "temp_norm_${key.toFilename()}.pcm")
                        val stats = PeakNormalizer.analyze(sequence {
                            val raf = java.io.RandomAccessFile(tempFile, "r")
                            raf.seek(44)
                            val buffer = ByteArray(1920 * 2)
                            while (true) {
                                val read = raf.read(buffer)
                                if (read <= 0) break
                                val shorts = ShortArray(read / 2)
                                ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                yield(shorts)
                            }
                            raf.close()
                        })
                        
                        FileOutputStream(normTempFile).use { out ->
                            val header = ByteArray(44)
                            out.write(header)
                            
                            val raf = java.io.RandomAccessFile(tempFile, "r")
                            raf.seek(44)
                            val buffer = ByteArray(1920 * 2)
                            while (true) {
                                val read = raf.read(buffer)
                                if (read <= 0) break
                                val shorts = ShortArray(read / 2)
                                ByteBuffer.wrap(buffer, 0, read).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                val norm = PeakNormalizer.apply(shorts, stats)
                                val outBuffer = ByteBuffer.allocate(norm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (s in norm) outBuffer.putShort(s)
                                out.write(outBuffer.array())
                            }
                            raf.close()
                        }
                        tempFile.delete()
                        normTempFile
                    } else {
                        tempFile
                    }
                    
                    // write actual wav header
                    writeWavHeader(finalFile, framesRead)
                    
                    cache.commit(key, finalFile)
                    finalFile.delete()
                }
            }
            Result.success(createResultData(key))
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(Data.Builder().putString(CleanupEffectWork.ERROR_MESSAGE, e.message).build())
        }
    }

    private fun createResultData(key: ProcessedAudioKey): Data {
        return Data.Builder()
            .putString(CleanupEffectWork.RESULT_CACHE_KEY_FILENAME, key.toFilename())
            .putString(CleanupEffectWork.RESULT_CACHE_KEY_FINGERPRINT, key.sourceFingerprint)
            .build()
    }
    
    private fun writeWavHeader(file: File, sampleCount: Long) {
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)
        
        val byteRate = EDITOR_SAMPLE_RATE * 2
        val dataSize = sampleCount * 2
        val chunkSize = 36 + dataSize
        
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(chunkSize.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(EDITOR_SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize.toInt())
        
        raf.write(buffer.array())
        raf.close()
    }
}
