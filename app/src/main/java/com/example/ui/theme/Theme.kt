package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme =
  lightColorScheme(
    primary = AccentCoral,
    onPrimary = Color.White,
    primaryContainer = AccentCoralSoft,
    onPrimaryContainer = DeepNavyDisplay,
    secondary = AccentRoyalBlue,
    onSecondary = Color.White,
    secondaryContainer = PastelPeriwinkleCardBg,
    onSecondaryContainer = AccentRoyalBlue,
    tertiary = AccentAqua,
    onTertiary = DeepNavyDisplay,
    tertiaryContainer = PastelMintCardBg,
    onTertiaryContainer = PastelMintText,
    background = AppCanvasBackground,
    surface = CardSurfaceWhite,
    surfaceVariant = MutedInputBackground,
    onSurface = DeepNavyDisplay,
    onSurfaceVariant = SubtitleSlate,
    outline = SubtleBorder,
    outlineVariant = PastelPeachBorder,
    error = Color(0xFFE54D4D)
  )

private val SoftUiShapes = Shapes(
  extraSmall = RoundedCornerShape(12.dp),
  small = RoundedCornerShape(16.dp),
  medium = RoundedCornerShape(22.dp),
  large = RoundedCornerShape(28.dp),
  extraLarge = RoundedCornerShape(34.dp)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  // Aplikasi dikunci ke Light Mode sesuai arahan pengguna
  val colorScheme = LightColorScheme

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        val windowInsetsController = WindowCompat.getInsetsController(window, view)
        windowInsetsController.isAppearanceLightStatusBars = true
        windowInsetsController.isAppearanceLightNavigationBars = true
      }
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = SoftUiShapes,
    content = content
  )
}
