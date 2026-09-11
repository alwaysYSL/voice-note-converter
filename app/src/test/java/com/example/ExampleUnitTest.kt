package com.example

import com.example.audio.opusPacketDurationSamples
import com.example.audio.trimFrameRange
import com.example.audio.StreamingPcmProcessor
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun `opus packet duration follows TOC frame configuration`() {
    assertEquals(120L, opusPacketDurationSamples(byteArrayOf(0x80.toByte())))
    assertEquals(960L, opusPacketDurationSamples(byteArrayOf(0x98.toByte())))
    assertEquals(1_920L, opusPacketDurationSamples(byteArrayOf(0x99.toByte())))
    assertEquals(2_880L, opusPacketDurationSamples(byteArrayOf(0x18.toByte())))
  }

  @Test
  fun `trim frame range slices exact boundaries inside a PCM buffer`() {
    assertEquals(
      96 until 336,
      trimFrameRange(
        bufferStartUs = 0L,
        frameCount = 480,
        sampleRate = 48_000,
        trimStartUs = 2_000L,
        trimEndUs = 7_000L
      )
    )
    assertTrue(
      trimFrameRange(
        bufferStartUs = 10_000L,
        frameCount = 480,
        sampleRate = 48_000,
        trimStartUs = 2_000L,
        trimEndUs = 7_000L
      ).isEmpty()
    )
  }

  @Test
  fun `streaming resampler preserves phase across chunk boundaries`() {
    val input = ShortArray(1_001) { index -> (index * 13).toShort() }
    val oneChunk = StreamingPcmProcessor(channels = 1, sourceSampleRate = 44_100)
      .process(input)
    val splitProcessor = StreamingPcmProcessor(channels = 1, sourceSampleRate = 44_100)
    val split = splitProcessor.process(input.copyOfRange(0, 333)) +
      splitProcessor.process(input.copyOfRange(333, 777)) +
      splitProcessor.process(input.copyOfRange(777, input.size))

    assertArrayEquals(oneChunk, split)
  }

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun `pcm chunk processor downmixes stereo without retaining the full stream`() {
    val actual = invokePcmChunkProcessor(
      shortArrayOf(1000, 3000, -1000, 1000),
      channels = 2,
      sampleRate = 48_000
    )

    assertArrayEquals(shortArrayOf(2000, 0), actual)
  }

  @Test
  fun `pcm chunk processor resamples each chunk to 48 kHz`() {
    val actual = invokePcmChunkProcessor(
      shortArrayOf(1000, 2000),
      channels = 1,
      sampleRate = 24_000
    )

    assertArrayEquals(shortArrayOf(1000, 1000, 2000, 2000), actual)
  }

  private fun invokePcmChunkProcessor(
    samples: ShortArray,
    channels: Int,
    sampleRate: Int
  ): ShortArray? = runCatching {
    val method = Class.forName("com.example.audio.VoiceNoteConverter")
      .getDeclaredMethod(
        "processPcmChunk",
        ShortArray::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType
      )
      .apply { isAccessible = true }
    method.invoke(null, samples, channels, sampleRate) as ShortArray
  }.getOrNull()
}
