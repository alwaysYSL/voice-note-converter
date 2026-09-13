package com.aistudio.voicenote.cvtr.work

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionCoordinatorTest {
    @Test
    fun `only one conversion engine block runs at a time`() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        List(3) {
            async {
                ConversionCoordinator.runExclusive {
                    val nowActive = active.incrementAndGet()
                    maximumActive.updateAndGet { current -> maxOf(current, nowActive) }
                    delay(10)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximumActive.get())
        assertEquals(0, active.get())
    }
}
