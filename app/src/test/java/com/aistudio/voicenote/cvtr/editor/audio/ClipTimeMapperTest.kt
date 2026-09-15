package com.aistudio.voicenote.cvtr.editor.audio

import org.junit.Assert.assertEquals
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
        assertEquals(96_000L, mapper.sourceFrameForTimelineFrame(192_000))
    }
}
