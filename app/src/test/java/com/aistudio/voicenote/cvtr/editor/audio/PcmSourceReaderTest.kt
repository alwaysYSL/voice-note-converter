package com.aistudio.voicenote.cvtr.editor.audio

import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSourceReaderTest {
    @Test
    fun `reader seeks exactly and truncates at the source end`() {
        val fake = FakePcmSourceReader(ShortArray(100) { it.toShort() })
        val factory = PcmSourceReaderFactory { fake }

        factory.open(AudioSourceRef(uri = "fake://source", durationMs = 2L)).use { reader ->
            assertArrayEquals(shortArrayOf(95, 96, 97, 98, 99), reader.read(95, 8))
            assertTrue(reader.read(100, 8).isEmpty())
        }

        assertTrue(fake.closed)
    }

    private class FakePcmSourceReader(private val samples: ShortArray) : PcmSourceReader {
        override val sampleRate: Int = 48_000
        var closed: Boolean = false
            private set

        override fun read(sourceFrame: Long, frameCount: Int): ShortArray {
            require(!closed) { "reader is closed" }
            require(sourceFrame >= 0L) { "sourceFrame must be non-negative" }
            require(frameCount >= 0) { "frameCount must be non-negative" }
            val start = sourceFrame.coerceAtMost(samples.size.toLong()).toInt()
            val end = (sourceFrame + frameCount.toLong())
                .coerceAtMost(samples.size.toLong())
                .toInt()
            return if (start >= end) ShortArray(0) else samples.copyOfRange(start, end)
        }

        override fun close() {
            closed = true
        }
    }
}
