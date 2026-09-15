package com.aistudio.voicenote.cvtr.editor.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CleanupEffectWorkerTest {
    @Test
    fun `worker fails if source uri is missing`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = CleanupEffectWork.request("", "fingerprint", 0L, 1000L, CleanupStrength.MEDIUM, true)
        
        // Remove source URI from input data to simulate failure
        val input = androidx.work.Data.Builder().build()
        
        val worker = TestListenableWorkerBuilder<CleanupEffectWorker>(context)
            .setInputData(input)
            .build()
            
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }
}
