package com.aistudio.voicenote.cvtr.work

import android.net.Uri
import androidx.work.workDataOf
import com.aistudio.voicenote.cvtr.audio.AudioProcessingOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversionWorkTest {
    @Test
    fun `batch request carries a stable batch tag`() {
        val batchItemId = "batch-item"
        val request = ConversionWork.request(
            inputUri = Uri.parse("file:///tmp/input.m4a"),
            requestedOutputName = "meeting.ogg",
            trimStartMs = 120L,
            trimEndMs = 4_200L,
            pitchSemitones = -2f,
            options = AudioProcessingOptions(normalizeAudio = true, trimSilence = true),
            batchItemId = batchItemId,
            sourceFileName = "meeting.m4a"
        )

        assertTrue(ConversionWork.BATCH_TAG in request.tags)
        assertTrue(ConversionWork.batchTag(batchItemId) in request.tags)
    }

    @Test
    fun `worker options decode normalization and silence trim flags`() {
        val options = ConversionWork.options(
            workDataOf(
                ConversionWork.NORMALIZE_AUDIO to true,
                ConversionWork.TRIM_SILENCE to true
            )
        )

        assertEquals(
            AudioProcessingOptions(normalizeAudio = true, trimSilence = true),
            options
        )
    }
}
