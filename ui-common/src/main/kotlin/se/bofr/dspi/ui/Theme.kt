package se.bofr.dspi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App theme. WAVE 0 placeholder: Material 3 defaults with a DSPi accent.
 * Package HOME ports the macOS look (Linux qt/qml/Theme.qml has the tokens).
 */
@Composable
fun DspiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val accent = Color(0xFF3B82F6)
    val colors = if (darkTheme) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
    MaterialTheme(colorScheme = colors, content = content)
}
