package dev.firetvonwatch.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

val FireOrange = Color(0xFFFF9900)
val StatusConnected = Color(0xFF4CD964)
val StatusBusy = Color(0xFFFFC233)
val StatusError = Color(0xFFFF5A5F)
val StatusIdle = Color(0xFF5F6368)

private val colors = ColorScheme(
    primary = FireOrange,
    primaryDim = Color(0xFFE08600),
    primaryContainer = Color(0xFF5A3300),
    onPrimary = Color(0xFF1F1200),
    onPrimaryContainer = Color(0xFFFFDDB3),
    background = Color.Black,
    onBackground = Color.White,
)

@Composable
fun FireTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
