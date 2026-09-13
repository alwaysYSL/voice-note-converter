package com.aistudio.voicenote.cvtr

import androidx.compose.ui.graphics.Color
import com.aistudio.voicenote.cvtr.ui.theme.AccentCoral
import com.aistudio.voicenote.cvtr.ui.theme.AppCanvasBackground
import com.aistudio.voicenote.cvtr.ui.theme.DeepNavyDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PastelWellnessThemeTest {

  @Test
  fun `pastel wellness palette keeps the canvas cool and the primary action warm`() {
    assertEquals(Color(0xFFF0F5F8), AppCanvasBackground)
    assertEquals(Color(0xFFB94A37), AccentCoral)
    assertEquals(Color(0xFF164A59), DeepNavyDisplay)
  }

  @Test
  fun `coral action color keeps readable contrast with white labels`() {
    assertTrue(
      "Coral CTA needs at least 4.5:1 contrast",
      contrastRatio(AccentCoral, Color.White) >= 4.5
    )
  }

  private fun contrastRatio(first: Color, second: Color): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05) / (darker + 0.05)
  }

  private fun relativeLuminance(color: Color): Double {
    fun linearize(channel: Float): Double {
      val value = channel.toDouble()
      return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * linearize(color.red) +
      0.7152 * linearize(color.green) +
      0.0722 * linearize(color.blue)
  }
}
