package com.example.audio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePitchShifterSourceTest {
    @Test
    fun `native pitch shifter sources are included in the app`() {
        val sourceRoot = File("src/main/cpp")
        assertTrue(File(sourceRoot, "CMakeLists.txt").isFile)
        assertTrue(File(sourceRoot, "pitch_shifter_jni.cpp").isFile)
        assertTrue(File(sourceRoot, "signalsmith-stretch/signalsmith-stretch.h").isFile)
        assertTrue(File(sourceRoot, "signalsmith-stretch/signalsmith-linear/stft.h").isFile)
    }
}
