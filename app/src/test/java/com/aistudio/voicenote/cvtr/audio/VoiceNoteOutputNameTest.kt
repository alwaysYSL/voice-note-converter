package com.aistudio.voicenote.cvtr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNoteOutputNameTest {
    @Test
    fun `suggested name keeps source stem and ogg extension`() {
        assertEquals("meeting 01.ogg", VoiceNoteStorage.suggestedOutputFileName("meeting 01.m4a"))
    }

    @Test
    fun `requested name cannot escape storage folder or keep another extension`() {
        val sanitized = VoiceNoteStorage.sanitizeOutputFileName("../private\\note.mp3")

        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertEquals("_private_note.ogg", sanitized)
    }
}
