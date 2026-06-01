package com.reader.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary       = Ink,
    onPrimary     = Paper,
    secondary     = InkSoft,
    onSecondary   = Paper,
    tertiary      = Accent,
    background    = Paper,
    onBackground  = Ink,
    surface       = Paper,
    onSurface     = Ink,
    surfaceVariant = Hairline,
    onSurfaceVariant = InkSoft,
    outline       = Hairline,
    error         = Accent
)

private val DarkColors = darkColorScheme(
    primary       = InkDark,
    onPrimary     = PaperDark,
    secondary     = InkSoftDark,
    onSecondary   = PaperDark,
    tertiary      = Accent,
    background    = PaperDark,
    onBackground  = InkDark,
    surface       = PaperDark,
    onSurface     = InkDark,
    surfaceVariant = HairlineDark,
    onSurfaceVariant = InkSoftDark,
    outline       = HairlineDark,
    error         = Accent
)

@Composable
fun ReaderTheme(
    useDark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography  = ReaderTypography,
        content     = content
    )
}
