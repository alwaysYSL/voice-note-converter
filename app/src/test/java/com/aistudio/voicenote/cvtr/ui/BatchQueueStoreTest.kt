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
    fun `queue metadata survives store reload`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = BatchQueueStore(app)
        val itemId = "test-${UUID.randomUUID()}"
        val firstWorkId = UUID.randomUUID()
        try {
            store.put(
                BatchQueueMetadata(
                    id = itemId,
                    workId = firstWorkId,
                    sourceFileName = "source.m4a",
                    outputFileName = "source.ogg",
                    inputUri = "file:///cache/source.m4a",
                    sourceUri = "content://picker/source.m4a",
                    normalizeAudio = true,
                    trimSilence = true
                )
            )

            val restored = store.all().single { it.id == itemId }
            assertEquals(firstWorkId, restored.workId)
            assertEquals("source.m4a", restored.sourceFileName)
            assertEquals("content://picker/source.m4a", restored.sourceUri)
            assertTrue(restored.normalizeAudio)
            assertTrue(restored.trimSilence)
        } finally {
            store.remove(itemId)
        }
    }

    @Test
    fun `clear removes queue state before a new app session`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = BatchQueueStore(app)
        store.put(
            BatchQueueMetadata(
                id = "stale-${UUID.randomUUID()}",
                workId = UUID.randomUUID(),
                sourceFileName = "stale.m4a",
                outputFileName = "stale.ogg",
                inputUri = "file:///cache/stale.m4a"
            )
        )

        store.clear()

        assertTrue(store.all().isEmpty())
    }
}
