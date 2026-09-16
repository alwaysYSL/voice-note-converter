package com.aistudio.voicenote.cvtr.editor.work

import com.aistudio.voicenote.cvtr.audio.OggOpusWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OggOpusValidatorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `valid ogg opus stream validates successfully with full metadata`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out)
        writer.writeHeader(sampleRate = 48000, channels = 1, preSkipSamples = 312)
        // Write a 20ms opus packet (960 samples)
        val packet = ByteArray(40) { 0x1F }
        writer.writeAudioPacket(packet, samplesInPacket = 960L, isLast = true)

        val bytes = out.toByteArray()
        val result = OggOpusValidator.validateStream(ByteArrayInputStream(bytes))
        assertTrue(result.error ?: "Expected valid", result.valid)
        assertEquals(48000, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(960L, result.totalSamples)
        assertTrue(result.pageCount >= 3) // BOS + Tags + Audio EOS
    }

    @Test
    fun `truncated ogg stream is rejected by validator`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out)
        writer.writeHeader(sampleRate = 48000, channels = 1, preSkipSamples = 312)
        val packet = ByteArray(40) { 0x1F }
        writer.writeAudioPacket(packet, samplesInPacket = 960L, isLast = false)

        // Without isLast = true / EOS page, stream is truncated
        val bytes = out.toByteArray()
        val result = OggOpusValidator.validateStream(ByteArrayInputStream(bytes))
        assertFalse(result.valid)
        assertTrue(result.error?.contains("EOS") == true)
    }

    @Test
    fun `corrupted magic bytes are rejected by validator`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out)
        writer.writeHeader()
        val packet = ByteArray(40) { 0x1F }
        writer.writeAudioPacket(packet, samplesInPacket = 960L, isLast = true)

        val bytes = out.toByteArray()
        bytes[0] = 0x00 // corrupt "OggS" magic
        val result = OggOpusValidator.validateStream(ByteArrayInputStream(bytes))
        assertFalse(result.valid)
        assertTrue(result.error?.contains("OggS") == true)
    }

    @Test
    fun `empty file or stream is rejected`() {
        val emptyFile = tempFolder.newFile("empty.ogg")
        val result = OggOpusValidator.validateFile(emptyFile)
        assertFalse(result.valid)
    }
}
