package com.cp36.roomscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cp36.roomscanner.data.ThemeMode

val Blue = Color(0xFF0B5CAD)
val Green = Color(0xFF2EBD6B)
val Amber = Color(0xFFF2A900)
val Red = Color(0xFFE5484D)

private val Light = lightColorScheme(
    primary = Blue,
    secondary = Color(0xFF2E7D6B),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8CC1FF),
    secondary = Color(0xFF7FD3BE),
)

@Composable
fun RoomScannerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
