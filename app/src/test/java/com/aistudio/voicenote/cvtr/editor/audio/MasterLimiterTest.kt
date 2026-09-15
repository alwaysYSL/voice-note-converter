package com.aistudio.voicenote.cvtr.editor.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class MasterLimiterTest {
    @Test
    fun `loud mix is peak safe and limiter state stays bounded across chunks`() {
        val limiter = MasterLimiter()
        val first = limiter.process(FloatArray(960) { 4f })
        val second = limiter.process(FloatArray(960) { 4f })

        assertTrue(first.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
        assertTrue(second.all { it.toInt() in Short.MIN_VALUE..Short.MAX_VALUE })
        assertTrue(first.maxOf { it.toInt() } > 0)
        assertTrue(second.maxOf { it.toInt() } > 0)
        assertTrue(limiter.gainForTest in 0f..1f)
    }
}
