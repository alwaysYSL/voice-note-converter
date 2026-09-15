package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.cache.readValidatedCachedWav
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Shared chunk renderer used by preview now and export in the next task. */
internal interface TimelineRenderer : AutoCloseable {
    fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray

    /** Returns and clears a non-fatal warning raised while falling back to an original source. */
    fun consumeWarning(): String? = null

    /** Invalidates decoder/effect state after a source or timeline mutation. */
    fun invalidate(sourceIds: Set<String> = emptySet())
}

/**
 * Renders only clips intersecting the requested half-open window. Readers are retained by
 * source between adjacent chunks and are released on invalidation, source failure, or close.
 */
internal class DefaultTimelineRenderer(
    private val sourceFactory: PcmSourceReaderFactory,
    private val clipProcessor: ClipProcessor = ClipProcessor(),
    private val trackMixer: TrackMixer = TrackMixer(),
    private val cacheDir: java.io.File? = null,
) : TimelineRenderer {
    private val readers = LinkedHashMap<AudioSourceRef, PcmSourceReader>()
    private val cacheReaders = LinkedHashMap<String, PcmSourceReader>()
    private val cacheReaderSources = LinkedHashMap<String, MutableSet<String>>()
    private val renderLock = Any()
    private var sessionId: String? = null
    private var closed = false
    private var pendingWarning: String? = null

    override fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray {
        synchronized(renderLock) {
            check(!closed) { "TimelineRenderer is closed" }
            require(startFrame >= 0L) { "startFrame must be non-negative" }
            require(frameCount >= 0) { "frameCount must be non-negative" }
            if (sessionId != null && sessionId != session.id) invalidate()
            sessionId = session.id
            if (frameCount == 0) return ShortArray(0)

            val output = ShortArray(frameCount)
            val endFrame = startFrame + frameCount.toLong()
            val limiter = MasterLimiter()
            var chunkStart = startFrame
            var outputOffset = 0
            // The smallest task-2 source read can be doubled by a 2x speed clip. 960 keeps every
            // source read within PcmSourceReader's 1,920-frame bound while remaining efficient.
            while (chunkStart < endFrame) {
                val chunkFrames = minOf(RENDER_CHUNK_FRAMES.toLong(), endFrame - chunkStart).toInt()
                val chunkEnd = chunkStart + chunkFrames
                val trackChunks = buildTrackChunks(session, chunkStart, chunkEnd)
                val mixed = trackMixer.mix(trackChunks, chunkFrames)
                val pcm = limiter.process(mixed)
                pcm.copyInto(output, outputOffset)
                chunkStart = chunkEnd
                outputOffset += chunkFrames
            }
            return output
        }
    }

    private fun buildTrackChunks(
        session: EditorSession,
        chunkStart: Long,
        chunkEnd: Long,
    ): List<TrackChunk> {
        val chunks = ArrayList<TrackChunk>()
        for (track in session.tracks) {
            for (clip in track.clips) {
                val mapper = mapperFor(clip)
                val activeRange = mapper.activeOutputRange
                val modelStart = clip.timelineStartMs * EDITOR_SAMPLE_RATE / 1_000L
                val modelEnd = clip.timelineEndMs * EDITOR_SAMPLE_RATE / 1_000L
                val activeStart = if (activeRange.isEmpty()) 0L else maxOf(
                    chunkStart,
                    modelStart,
                    activeRange.first,
                )
                val activeEnd = if (activeRange.isEmpty()) 0L else minOf(
                    chunkEnd,
                    modelEnd,
                    activeRange.last + 1L,
                )
                if (activeStart >= activeEnd) {
                    if (chunkEnd >= modelEnd) {
                        clipProcessor.flush(clip, modelEnd, outputFrameCount = 0)
                    }
                    continue
                }

                val outputFrames = (activeEnd - activeStart).toInt()
                val sourceFrame = mapper.sourceFrameForTimelineFrame(activeStart)
                val sourceEndFrame = sourceFrameLimit(clip)
                val requestedSourceFrames = ceil(
                    outputFrames.toDouble() * clip.effects.normalizedSpeed.toDouble()
                ).toInt().coerceAtLeast(0)
                val readableFrames = (sourceEndFrame - sourceFrame)
                    .coerceAtLeast(0L)
                    .coerceAtMost(MAX_PCM_READ_FRAMES.toLong())
                    .toInt()
                val sourceFrames = if (requestedSourceFrames == 0 || readableFrames == 0) {
                    ShortArray(0)
                } else {
                    read(clip, sourceFrame, minOf(requestedSourceFrames, readableFrames))
                }
                val processed = clipProcessor.process(
                    clip = clip,
                    sourceFrames = sourceFrames,
                    outputStartFrame = activeStart,
                    outputFrameCount = outputFrames,
                )
                val chunkSamples = FloatArray((chunkEnd - chunkStart).toInt())
                processed.copyInto(chunkSamples, (activeStart - chunkStart).toInt())
                chunks += TrackChunk(chunkSamples, track.volume, track.muted)
                if (chunkEnd >= modelEnd) {
                    // The model boundary is authoritative. Flush only to release any state;
                    // native latency beyond the clip is intentionally trimmed from the timeline.
                    clipProcessor.flush(clip, modelEnd, outputFrameCount = 0)
                }
            }
        }
        return chunks
    }

    private fun mapperFor(clip: AudioClip): ClipTimeMapper = ClipTimeMapper(
        sourceStartFrame = clip.sourceStartMs * EDITOR_SAMPLE_RATE / 1_000L,
        timelineStartFrame = clip.timelineStartMs * EDITOR_SAMPLE_RATE / 1_000L,
        speed = clip.effects.normalizedSpeed,
        sourceEndFrame = clip.sourceEndMs * EDITOR_SAMPLE_RATE / 1_000L,
    )

    private fun sourceFrameLimit(clip: AudioClip): Long =
        clip.sourceEndMs * EDITOR_SAMPLE_RATE / 1_000L

    private fun read(clip: AudioClip, sourceFrame: Long, frameCount: Int): ShortArray {
        val cacheKey = clip.effects.processedCacheKey
        if (cacheKey != null && cacheDir != null) {
            try {
                // Construct and validate inside the fallback boundary. A cache can be evicted
                // between timeline planning and this read; that must simply reopen the source.
                val reader = cacheReaders.getOrPut(cacheKey) {
                    CachedPcmSourceReader(
                        File(cacheDir, "$cacheKey.pcm"),
                        expectedRangeFrames = cachedRangeFrames(clip),
                    )
                }
                cacheReaderSources.getOrPut(cacheKey) { LinkedHashSet() }.add(clip.source.uri)
                // cache stores from sourceStartMs
                val cacheStartFrame = clip.sourceStartMs * EDITOR_SAMPLE_RATE / 1_000L
                return reader.read(sourceFrame - cacheStartFrame, frameCount)
            } catch (_: Throwable) {
                pendingWarning = "Processed audio cache $cacheKey is unavailable; using the original source."
                cacheReaders.remove(cacheKey)?.let { runCatching { it.close() } }
                cacheReaderSources.remove(cacheKey)
            }
        }

        val reader = readers.getOrPut(clip.source) { sourceFactory.open(clip.source) }
        return try {
            reader.read(sourceFrame, frameCount)
        } catch (error: Throwable) {
            readers.remove(clip.source)?.let { runCatching { it.close() } }
            throw error
        }
    }

    override fun consumeWarning(): String? = synchronized(renderLock) {
        pendingWarning.also { pendingWarning = null }
    }

    private fun cachedRangeFrames(clip: AudioClip): Long =
        (clip.sourceEndMs - clip.sourceStartMs)
            .coerceAtLeast(0L)
            .let { durationMs -> durationMs * EDITOR_SAMPLE_RATE / 1_000L }

    override fun invalidate(sourceIds: Set<String>) {
        synchronized(renderLock) {
            if (closed) return
            clipProcessor.close()
            if (sourceIds.isEmpty()) {
                readers.values.forEach { runCatching { it.close() } }
                readers.clear()
                cacheReaders.values.forEach { runCatching { it.close() } }
                cacheReaders.clear()
                cacheReaderSources.clear()
            } else {
                val removed = readers.keys.filter { it.uri in sourceIds }
                removed.forEach { source ->
                    readers.remove(source)?.let { runCatching { it.close() } }
                }
                val staleCacheKeys = cacheReaderSources
                    .filterValues { sources -> sources.any(sourceIds::contains) }
                    .keys
                    .toList()
                staleCacheKeys.forEach { key ->
                    cacheReaders.remove(key)?.let { runCatching { it.close() } }
                    cacheReaderSources.remove(key)
                }
            }
        }
    }

    override fun close() {
        synchronized(renderLock) {
            if (closed) return
            closed = true
            clipProcessor.close()
            readers.values.forEach { runCatching { it.close() } }
            readers.clear()
            cacheReaders.values.forEach { runCatching { it.close() } }
            cacheReaders.clear()
            cacheReaderSources.clear()
        }
    }

    private companion object {
        const val RENDER_CHUNK_FRAMES = 960
    }
}

internal class CachedPcmSourceReader(
    private val file: File,
    expectedRangeFrames: Long? = null,
) : PcmSourceReader {
    override val sampleRate: Int = EDITOR_SAMPLE_RATE
    private var raf: java.io.RandomAccessFile? = null
    private var closed = false
    private val metadata = readValidatedCachedWav(file, expectedRangeFrames)

    override fun read(sourceFrame: Long, frameCount: Int): ShortArray {
        if (closed) throw IllegalStateException("closed")
        if (sourceFrame < 0L || frameCount < 0) throw IllegalArgumentException("Invalid cache read")
        val requestedEnd = sourceFrame.checkedAdd(frameCount.toLong())
        if (requestedEnd > metadata.sampleCount) {
            throw IOException("Cached PCM ended before the requested range")
        }
        if (frameCount == 0) return ShortArray(0)
        if (raf == null) {
            raf = java.io.RandomAccessFile(file, "r")
        }
        val raf = this.raf!!
        val offset = 44L + sourceFrame.checkedMultiply(2L)

        raf.seek(offset)
        val toRead = minOf(frameCount, MAX_PCM_READ_FRAMES)

        val output = ShortArray(toRead)
        val bytes = ByteArray(toRead * 2)
        var framesRead = 0
        while (framesRead < toRead) {
            val requestedBytes = minOf(bytes.size, (toRead - framesRead) * 2)
            var bytesRead = 0
            while (bytesRead < requestedBytes) {
                val count = raf.read(bytes, bytesRead, requestedBytes - bytesRead)
                if (count < 0) {
                    throw IOException("Cached PCM ended before its validated data size")
                }
                if (count == 0) continue
                bytesRead += count
            }
            if (bytesRead % 2 != 0) throw IOException("Cached PCM returned a partial sample")
            val chunkFrames = bytesRead / 2
            ByteBuffer.wrap(bytes, 0, bytesRead)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(output, framesRead, chunkFrames)
            framesRead += chunkFrames
        }
        return output
    }

    private fun Long.checkedAdd(value: Long): Long =
        if (value < 0L || this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private fun Long.checkedMultiply(value: Long): Long =
        if (value < 0L || this > Long.MAX_VALUE / value) {
            throw IOException("Cached PCM offset overflow")
        } else this * value

    override fun close() {
        if (closed) return
        closed = true
        raf?.close()
        raf = null
    }
}
