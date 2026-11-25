package com.vynoza.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VynozaDarkColorScheme = darkColorScheme(
  primary = VynozaPurplePrimary,
  onPrimary = VynozaTextPrimary,
  primaryContainer = VynozaVioletContainer,
  onPrimaryContainer = VynozaLavenderSoft,
  secondary = VynozaPurpleBright,
  onSecondary = VynozaObsidian,
  secondaryContainer = VynozaSurfaceHigh,
  onSecondaryContainer = VynozaTextPrimary,
  tertiary = VynozaRoseAccent,
  onTertiary = VynozaTextPrimary,
  background = VynozaObsidian,
  onBackground = VynozaTextPrimary,
  surface = VynozaSurfaceDeep,
  onSurface = VynozaTextPrimary,
  surfaceVariant = VynozaSurfaceElevated,
  onSurfaceVariant = VynozaTextSecondary,
  surfaceContainerLowest = VynozaObsidian,
  surfaceContainerLow = VynozaSurfaceDeep,
  surfaceContainer = VynozaSurfaceElevated,
  surfaceContainerHigh = VynozaSurfaceHigh,
  outline = VynozaBorderSubtle,
  outlineVariant = VynozaBorderSubtle,
  error = VynozaError,
  onError = VynozaObsidian,
  errorContainer = VynozaErrorContainer,
  onErrorContainer = VynozaError,
)

private val VynozaShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(18.dp),
  large = RoundedCornerShape(24.dp),
  extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun VynozaTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = VynozaDarkColorScheme,
    typography = VynozaTypography,
    shapes = VynozaShapes,
    content = content,
  )
}
