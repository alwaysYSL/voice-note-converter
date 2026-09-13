package com.aistudio.voicenote.cvtr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversionFailureTest {
    @Test
    fun `pipeline failure preserves stable code stage and safe message`() {
        val details = ConversionPipelineException(
            errorCode = ConversionErrorCode.OPUS_ENCODER_UNAVAILABLE,
            stage = ConversionStage.CREATE_ENCODER,
            safeMessage = "Encoder Opus tidak tersedia."
        ).toConversionFailureDetails(ConversionStage.DECODE)

        assertEquals(ConversionErrorCode.OPUS_ENCODER_UNAVAILABLE, details.code)
        assertEquals(ConversionStage.CREATE_ENCODER, details.stage)
        assertEquals("Encoder Opus tidak tersedia.", details.message)
        assertFalse(details.message.contains("java.", ignoreCase = true))
    }

    @Test
    fun `raw codec exception becomes actionable encoder failure`() {
        val details = IllegalStateException("Opus encoder configure failed")
            .toConversionFailureDetails(ConversionStage.CREATE_ENCODER)

        assertEquals(ConversionErrorCode.OPUS_CONFIGURE_FAILED, details.code)
        assertEquals(ConversionStage.CREATE_ENCODER, details.stage)
        assertEquals("Encoder Opus gagal dimulai. Restart aplikasi lalu coba lagi.", details.message)
    }

    @Test
    fun `video without audio is classified as unreadable input`() {
        val details = NoAudioTrackException().toConversionFailureDetails(ConversionStage.DECODE)

        assertEquals(ConversionErrorCode.INPUT_UNREADABLE, details.code)
        assertEquals(ConversionStage.OPEN_EXTRACTOR, details.stage)
        assertEquals(
            "File tidak memiliki track audio. Pilih file audio atau video dengan suara.",
            details.message
        )
    }
}
