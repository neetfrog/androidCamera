package com.procamera.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ProCameraColorScheme = darkColorScheme(
    primary          = OrangePrimary,
    onPrimary        = Color.Black,
    primaryContainer = OrangeDark,
    onPrimaryContainer = OrangeLight,

    secondary        = AmberAccent,
    onSecondary      = Color.Black,

    background       = Surface900,
    onBackground     = TextPrimary,

    surface          = Surface800,
    onSurface        = TextPrimary,
    surfaceVariant   = Surface700,
    onSurfaceVariant = TextSecondary,

    outline          = Surface500,
    error            = RecordRed,
    onError          = Color.White
)

@Composable
fun ProCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ProCameraColorScheme,
        typography  = ProCameraTypography,
        content     = content
    )
}
