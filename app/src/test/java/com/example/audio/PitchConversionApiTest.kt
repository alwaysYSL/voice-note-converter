package com.example.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class PitchConversionApiTest {
    @Test
    fun `conversion api accepts pitch semitones`() {
        val acceptsPitch = VoiceNoteConverter::class.java.declaredMethods
            .filter { it.name == "convertToTelegramVoiceNote" }
            .any { method -> method.parameterTypes.contains(Float::class.javaPrimitiveType) }

        assertTrue("conversion API should expose pitchSemitones", acceptsPitch)
    }
}
