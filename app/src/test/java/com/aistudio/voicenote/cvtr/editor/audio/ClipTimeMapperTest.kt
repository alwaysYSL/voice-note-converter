package com.aistudio.voicenote.cvtr.editor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTimeMapperTest {
    @Test
    fun `half speed maps output frames to half as many source frames`() {
        val mapper = ClipTimeMapper(
            sourceStartFrame = 48_000,
            timelineStartFrame = 96_000,
            speed = 0.5f,
            sourceEndFrame = 96_000,
        )

        assertEquals(72_000L, mapper.sourceFrameForTimelineFrame(144_000))
        assertEquals(191_999L, mapper.activeOutputRange.last)
        assertEquals(95_999L, mapper.sourceFrameForTimelineFrame(192_000))
    }

    @Test
    fun `half open source boundary never maps output frames to its exclusive end`() {
        val mapper = ClipTimeMapper(
            sourceStartFrame = 0L,
            timelineStartFrame = 0L,
            speed = 0.5f,
            sourceEndFrame = 1L,
        )

        assertEquals(0L, mapper.sourceFrameForTimelineFrame(0L))
        assertEquals(0L, mapper.sourceFrameForTimelineFrame(1L))
        assertTrue(mapper.activeOutputRange.all { mapper.sourceFrameForTimelineFrame(it) in 0L until 1L })
    }
}
