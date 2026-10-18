package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KomaDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = SumiInk,
    primaryContainer = CyberCyanDim,
    onPrimaryContainer = PaperCream,
    secondary = MangaMagenta,
    onSecondary = Color.White,
    secondaryContainer = MangaMagentaDim,
    onSecondaryContainer = PaperCream,
    tertiary = AmberHighlight,
    onTertiary = SumiInk,
    background = SumiInk,
    onBackground = PaperCream,
    surface = PanelSlate,
    onSurface = PaperCream,
    surfaceVariant = ElevatedSlate,
    onSurfaceVariant = MutedSilver,
    error = ErrorCrimson,
    onError = Color.White
)

private val KomaLightColorScheme = lightColorScheme(
    primary = Color(0xFF007A8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F4FF),
    onPrimaryContainer = SumiInk,
    secondary = MangaMagenta,
    onSecondary = Color.White,
    background = PaperCream,
    onBackground = SumiInk,
    surface = Color.White,
    onSurface = SumiInk,
    surfaceVariant = Color(0xFFE8E6DF),
    onSurfaceVariant = Color(0xFF454B59)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KomaDarkColorScheme else KomaLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
