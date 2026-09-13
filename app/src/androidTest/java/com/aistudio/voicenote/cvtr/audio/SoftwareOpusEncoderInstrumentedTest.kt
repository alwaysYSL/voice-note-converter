package com.aistudio.voicenote.cvtr.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoftwareOpusEncoderInstrumentedTest {
    @Test
    fun bundledEncoderProducesDecodableOggOpusWithoutMediaCodecEncoder() {
        val output = ByteArrayOutputStream()
        val writer = OggOpusWriter(output)
        val encoder = SoftwareOpusEncoder(writer) { }
        val samples = ShortArray(48_000) { index ->
            (sin(2.0 * PI * 440.0 * index / 48_000.0) * 8_000.0).toInt().toShort()
        }

        encoder.write(samples)
        encoder.finish()
        writer.close(finalGranulePosition = samples.size.toLong() + encoder.preSkipSamples)

        val bytes = output.toByteArray()
        assertTrue(bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray()))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("OpusHead"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "software-opus-smoke.ogg")
        file.writeBytes(bytes)
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals(
                    MediaFormat.MIMETYPE_AUDIO_OPUS,
                    extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)
                )
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }
}
