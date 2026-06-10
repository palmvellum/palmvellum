package dev.tatliving.palmvellum.organizers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val PalmColorScheme = lightColorScheme(
    primary = PalmTitleBar,
    onPrimary = PalmOnDark,
    secondary = PalmTitleBar,
    onSecondary = PalmOnDark,
    background = PalmBg,
    onBackground = PalmInk,
    surface = PalmSurfaceLo,
    onSurface = PalmInk,
    surfaceVariant = PalmSurfaceHi,
    onSurfaceVariant = PalmInkMute,
    outline = PalmLine,
    error = PalmRed,
)

@Composable
fun PalmVellumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PalmColorScheme,
        typography = PalmTypography,
        content = content,
    )
}
