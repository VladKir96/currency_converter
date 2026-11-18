package com.pocketfx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketfx.domain.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EFE9),
    onPrimaryContainer = Color(0xFF12463D),
    secondary = Color(0xFF48635C),
    secondaryContainer = Color(0xFFE3EDE8),
    onSecondaryContainer = Color(0xFF213B34),
    background = Color(0xFFF2F6F4),
    onBackground = Color(0xFF142421),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF142421),
    surfaceVariant = Color(0xFFE6EEEA),
    onSurfaceVariant = Color(0xFF4C605A),
    surfaceContainerLow = Color(0xFFF8FAF9),
    surfaceContainer = Color(0xFFEFF4F1),
    surfaceContainerHigh = Color(0xFFE8EFEB),
    outline = Color(0xFF697C76),
    outlineVariant = Color(0xFFDBE5E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DE0CE),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF164A41),
    onPrimaryContainer = Color(0xFFB0EFDE),
    secondary = Color(0xFFB4CDC3),
    secondaryContainer = Color(0xFF263C35),
    onSecondaryContainer = Color(0xFFD2EAE0),
    background = Color(0xFF0E1715),
    onBackground = Color(0xFFE3EEEA),
    surface = Color(0xFF182320),
    onSurface = Color(0xFFE3EEEA),
    surfaceVariant = Color(0xFF283A33),
    onSurfaceVariant = Color(0xFFB2C5BE),
    surfaceContainerLow = Color(0xFF15211D),
    surfaceContainer = Color(0xFF1D2925),
    surfaceContainerHigh = Color(0xFF26352F),
    outline = Color(0xFF899C94),
    outlineVariant = Color(0xFF34453E),
)

@Composable
internal fun useDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
internal fun PocketTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
