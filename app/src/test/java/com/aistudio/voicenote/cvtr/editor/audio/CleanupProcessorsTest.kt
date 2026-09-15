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
        }
    }

    private fun speechFixture(): ShortArray {
        return ShortArray(1000) { it.toShort() }
    }
}
