package com.aistudio.voicenote.cvtr.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ConversionCoordinator {
    private val engineMutex = Mutex()

    suspend fun <T> runExclusive(block: suspend () -> T): T =
        engineMutex.withLock { block() }
}
