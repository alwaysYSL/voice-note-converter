package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.ClipEffects
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TimelineRendererTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `renderer mixes only clips active in requested window`() {
        val opened = mutableListOf<String>()
        val factory = PcmSourceReaderFactory { source ->
            opened += source.uri
            object : PcmSourceReader {
                override val sampleRate: Int = EDITOR_SAMPLE_RATE

                override fun read(sourceFrame: Long, frameCount: Int): ShortArray =
                    ShortArray(frameCount) { if (source.uri == "voice") 8_000 else 4_000 }

                override fun close() = Unit
            }
        }
        val renderer = DefaultTimelineRenderer(factory)
        val session = EditorSession(
            id = "session",
            tracks = listOf(
                EditorTrack(
                    id = "voice-track",
                    name = "Voice",
                    clips = listOf(clip("voice-clip", "voice", 1_000L)),
                ),
                EditorTrack(
                    id = "music-track",
                    name = "Music",
                    clips = listOf(clip("music-clip", "music", 1_000L)),
                ),
                EditorTrack(
                    id = "inactive-track",
                    name = "Inactive",
                    clips = listOf(clip("inactive-clip", "inactive", 3_000L)),
                ),
            ),
        )

        val pcm = renderer.render(session, startFrame = 48_000L, frameCount = 1_920)

        assertEquals(setOf("voice", "music"), opened.toSet())
        assertEquals(1_920, pcm.size)
        assertTrue(pcm.take(960).all { it.toInt() == 12_000 })
        assertTrue(pcm.drop(960).all { it.toInt() == 0 })
        renderer.close()

        val fractionalClip = AudioClip(
            id = "fractional-clip",
            source = AudioSourceRef("fractional", durationMs = 100L),
            sourceStartMs = 0L,
            sourceEndMs = 100L,
            timelineStartMs = 0L,
            effects = com.aistudio.voicenote.cvtr.editor.model.ClipEffects(speed = 1.3f),
        )
        val fractionalRenderer = DefaultTimelineRenderer(
            sourceFactory = factory,
            clipProcessor = ClipProcessor(ClipEffectProcessorFactory { _, _ ->
                object : ClipEffectProcessor {
                    override fun process(input: ShortArray, outputFrames: Int): ShortArray =
                        ShortArray(outputFrames) { input.getOrElse(it) { 0 } }

                    override fun close() = Unit
                }
            }),
        )
        val fractional = fractionalRenderer.render(
            EditorSession(
                id = "fractional-session",
                tracks = listOf(EditorTrack("fractional-track", "Fractional", clips = listOf(fractionalClip))),
            ),
            startFrame = 0L,
            frameCount = 4_000,
        )
        val modelFrames = fractionalClip.timelineEndMs * EDITOR_SAMPLE_RATE / 1_000L
        assertTrue(fractional.take(modelFrames.toInt()).any { it.toInt() != 0 })
        assertTrue(fractional.drop(modelFrames.toInt()).all { it.toInt() == 0 })
        fractionalRenderer.close()
    }

    @Test
    fun `cached reader caps reads rejects short data and renderer falls back`() {
        val cacheDir = tempFolder.newFolder("processed")
        val valid = tempFolder.newFile("valid.pcm")
        writeWav(valid, MAX_PCM_READ_FRAMES + 100)
        val reader = CachedPcmSourceReader(valid, expectedRangeFrames = (MAX_PCM_READ_FRAMES + 100).toLong())

        assertEquals(MAX_PCM_READ_FRAMES, reader.read(0L, MAX_PCM_READ_FRAMES + 100).size)
        assertThrows(IOException::class.java) { reader.read(MAX_PCM_READ_FRAMES + 99L, 2) }
        reader.close()

        val short = tempFolder.newFile("short.pcm")
        writeWav(short, 4)
        val shortBytes = short.readBytes()
        ByteBuffer.wrap(shortBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4, getInt(4) + 2)
            putInt(40, getInt(40) + 2)
        }
        short.writeBytes(shortBytes)
        assertThrows(IOException::class.java) { CachedPcmSourceReader(short, expectedRangeFrames = 5L) }

        val brokenKey = "broken-cache"
        File(cacheDir, "$brokenKey.pcm").writeBytes(ByteArray(44))
        val opened = mutableListOf<String>()
        val renderer = DefaultTimelineRenderer(
            sourceFactory = PcmSourceReaderFactory { source ->
                opened += source.uri
                object : PcmSourceReader {
                    override val sampleRate: Int = EDITOR_SAMPLE_RATE
                    override fun read(sourceFrame: Long, frameCount: Int): ShortArray =
                        ShortArray(frameCount) { 7 }
                    override fun close() = Unit
                }
            },
            cacheDir = cacheDir,
        )
        val clip = AudioClip(
            id = "cached",
            source = AudioSourceRef("voice", durationMs = 20L),
            sourceStartMs = 0L,
            sourceEndMs = 20L,
            timelineStartMs = 0L,
            effects = ClipEffects(processedCacheKey = brokenKey),
        )
        val pcm = renderer.render(
            EditorSession("cache-fallback", listOf(EditorTrack("track", "Track", clips = listOf(clip)))),
            0L,
            960,
        )
        assertEquals(listOf("voice"), opened)
        assertTrue(pcm.all { it.toInt() == 7 })
        renderer.close()
    }

    @Test
    fun `renderer resolves a valid processed cache before opening the source`() {
        val cacheDir = tempFolder.newFolder("processed-valid")
        val key = ProcessedAudioKey("content-fingerprint", 0L, 20L, CleanupStrength.MEDIUM, false)
        val cached = File(cacheDir, "${key.toFilename()}.pcm")
        writeWav(cached, 960, sample = 321)
        val renderer = DefaultTimelineRenderer(
            sourceFactory = PcmSourceReaderFactory { error("source must not be opened") },
            cacheDir = cacheDir,
        )
        val clip = AudioClip(
            id = "cached",
            source = AudioSourceRef("content://source", 20L),
            sourceStartMs = 0L,
            sourceEndMs = 20L,
            timelineStartMs = 0L,
            effects = ClipEffects(processedCacheKey = key.toFilename()),
        )

        val pcm = renderer.render(
            EditorSession("cached-session", listOf(EditorTrack("track", "Track", clips = listOf(clip)))),
            0L,
            960,
        )

        assertTrue(pcm.all { it.toInt() == 321 })
        renderer.close()
    }

    private fun clip(id: String, source: String, timelineStartMs: Long): AudioClip = AudioClip(
        id = id,
        source = AudioSourceRef(source, durationMs = 20L),
        sourceStartMs = 0L,
        sourceEndMs = 20L,
        timelineStartMs = timelineStartMs,
    )

    private fun writeWav(file: File, sampleCount: Int, sample: Int = 1) {
        val bytes = ByteBuffer.allocate(44 + sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray())
        bytes.putInt(36 + sampleCount * 2)
        bytes.put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray())
        bytes.putInt(16)
        bytes.putShort(1)
        bytes.putShort(1)
        bytes.putInt(EDITOR_SAMPLE_RATE)
        bytes.putInt(EDITOR_SAMPLE_RATE * 2)
        bytes.putShort(2)
        bytes.putShort(16)
        bytes.put("data".toByteArray())
        bytes.putInt(sampleCount * 2)
        repeat(sampleCount) { bytes.putShort(sample.toShort()) }
        file.writeBytes(bytes.array())
    }
}
