package com.aistudio.voicenote.cvtr.editor.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupProcessorsTest {

    @Test
    fun `normalizer uses one fixed gain for every chunk`() {
        val stats = PeakNormalizer.analyze(sequenceOf(shortArrayOf(1_000), shortArrayOf(10_000)))
        val first = PeakNormalizer.apply(shortArrayOf(1_000), stats)
        val second = PeakNormalizer.apply(shortArrayOf(10_000), stats)
        assertEquals(10, second[0] / first[0])
    }

    @Test
    fun `neutral cleanup strength bypasses samples exactly`() {
        val input = speechFixture()
        val processor = RnNoiseProcessor()
        processor.use {
            assertArrayEquals(input, it.process(input, CleanupStrength.OFF))
            assertArrayEquals(ShortArray(0), it.flush(CleanupStrength.OFF))
        }
    }

    @Test
    fun `streaming chunks matches single chunk`() {
        val processor1 = RnNoiseProcessor()
        org.junit.Assume.assumeTrue(processor1.isReady)
        
        val input = speechFixture() // length 1000
        val processor2 = RnNoiseProcessor()
        
        val singleOut = processor1.use { it.processAll(input, CleanupStrength.MEDIUM) }
        
        val streamOut = processor2.use {
            val chunk1 = it.process(input.sliceArray(0 until 400), CleanupStrength.MEDIUM)
            val chunk2 = it.process(input.sliceArray(400 until 1000), CleanupStrength.MEDIUM)
            val tail = it.flush(CleanupStrength.MEDIUM)
            
            val combined = ShortArray(chunk1.size + chunk2.size + tail.size)
            System.arraycopy(chunk1, 0, combined, 0, chunk1.size)
            System.arraycopy(chunk2, 0, combined, chunk1.size, chunk2.size)
            System.arraycopy(tail, 0, combined, chunk1.size + chunk2.size, tail.size)
            combined
        }
        
        assertArrayEquals(singleOut, streamOut)
    }

    private fun speechFixture(): ShortArray {
        return ShortArray(1000) { it.toShort() }
    }
}
