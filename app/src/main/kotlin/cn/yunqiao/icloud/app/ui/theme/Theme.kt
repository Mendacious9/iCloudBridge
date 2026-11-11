package cn.yunqiao.icloud.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE6FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF4C6178),
    surface = Color(0xFFF7F8FB),
    surfaceVariant = Color(0xFFE6EAF2),
    background = Color(0xFFF7F8FB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C3FF),
    onPrimary = Color(0xFF12315F),
    primaryContainer = Color(0xFF2A4C86),
    onPrimaryContainer = Color(0xFFDDE6FF),
    secondary = Color(0xFFB4C8DE),
    surface = Color(0xFF14161A),
    surfaceVariant = Color(0xFF262A31),
    background = Color(0xFF14161A),
)

@Composable
fun ICloudBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
