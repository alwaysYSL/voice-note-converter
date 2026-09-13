package com.aistudio.voicenote.cvtr.telegram

import com.aistudio.voicenote.cvtr.audio.AudioConversionResult
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCompatibilityTest {
    @Test
    fun `valid opus container reports Telegram target summary`() {
        val file = tempOpusFile()
        try {
            val result = TelegramCompatibilityValidator.validate(
                AudioConversionResult(
                    outputFile = file,
                    durationSeconds = 4,
                    waveform = listOf(1, 2),
                    originalFileName = "source.m4a",
                    originalSize = 100L
                )
            )

            assertTrue(result.isShareable)
            assertTrue(result.summary.contains("OGG Opus"))
            assertTrue(result.warning == null)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing opus marker is not shareable`() {
        val file = File.createTempFile("telegram-compatibility", ".ogg").apply {
            writeBytes(ByteArray(100))
        }
        try {
            val result = TelegramCompatibilityValidator.validate(
                AudioConversionResult(
                    outputFile = file,
                    durationSeconds = 1,
                    waveform = emptyList(),
                    originalFileName = "source.wav",
                    originalSize = 100L
                )
            )

            assertFalse(result.isShareable)
            assertTrue(result.warning.orEmpty().contains("OGG Opus"))
        } finally {
            file.delete()
        }
    }

    private fun tempOpusFile(): File = File.createTempFile("telegram-compatibility", ".ogg").apply {
        val bytes = ByteArray(100)
        "OggS".encodeToByteArray().copyInto(bytes, destinationOffset = 0)
        "OpusHead".encodeToByteArray().copyInto(bytes, destinationOffset = 32)
        writeBytes(bytes)
    }
}
