package com.aistudio.voicenote.cvtr.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingPitchShifterTest {
    @Test
    fun `multichannel sizing uses frames and returns interleaved samples`() {
        val engine = RecordingEngine()
        val shifter = StreamingPitchShifter(
            sampleRate = 48_000,
            channels = 2,
            tempoRatio = 2f,
            engineFactory = PitchShifterEngineFactory { _, _ -> engine },
        )

        val output = shifter.process(ShortArray(8))

        assertEquals(4, engine.lastInputFrames)
        assertEquals(2, engine.lastOutputFrames)
        assertEquals(4, output.size)
        shifter.close()
    }

    private class RecordingEngine : PitchShifterEngine {
        var lastInputFrames = 0
        var lastOutputFrames = 0

        override fun setTranspose(semitones: Float, tonalityLimit: Float) = Unit

        override fun process(input: ShortArray, inputFrames: Int): ShortArray {
            lastInputFrames = inputFrames
            lastOutputFrames = inputFrames
            return ShortArray(inputFrames * 2)
        }

        override fun processWithOutputFrames(
            input: ShortArray,
            inputFrames: Int,
            outputFrames: Int,
        ): ShortArray {
            lastInputFrames = inputFrames
            lastOutputFrames = outputFrames
            return ShortArray(outputFrames * 2)
        }

        override fun flush(): ShortArray = ShortArray(0)

        override fun close() = Unit
    }
}
