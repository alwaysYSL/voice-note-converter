package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchQueueStoreTest {
    @Test
    fun `queue metadata survives store reload and work id replacement`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = BatchQueueStore(app)
        val itemId = "test-${UUID.randomUUID()}"
        val firstWorkId = UUID.randomUUID()
        val secondWorkId = UUID.randomUUID()
        try {
            store.put(
                BatchQueueMetadata(
                    id = itemId,
                    workId = firstWorkId,
                    sourceFileName = "source.m4a",
                    outputFileName = "source.ogg",
                    inputUri = "file:///cache/source.m4a",
                    normalizeAudio = true,
                    trimSilence = true
                )
            )
            store.updateWorkId(itemId, secondWorkId)

            val restored = store.all().single { it.id == itemId }
            assertEquals(secondWorkId, restored.workId)
            assertEquals("source.m4a", restored.sourceFileName)
            assertTrue(restored.normalizeAudio)
            assertTrue(restored.trimSilence)
        } finally {
            store.remove(itemId)
        }
    }
}
