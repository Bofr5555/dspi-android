// Port of DSPi-Console-Linux:qt/qml/components/MacColors.qml @ 4683a2c (the
// dark-mode macOS system colours the native Console draws with); the light
// set is AppKit's light appearance.
package se.bofr.dspi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The macOS Console's semantic colours (SwiftUI `.primary`, `.secondary`,
 * `.accentColor`, `NSColor.controlBackgroundColor`, …). Read them through
 * [DspiTheme.colors] inside [DspiTheme]; every package uses these instead of
 * literals so the app reads as one Console.
 */
@Immutable
data class DspiColors(
    /** `.primary` / `labelColor`. */
    val label: Color,
    /** `.secondary` / `secondaryLabelColor`. */
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    /** `.accentColor` (controlAccentColor, blue). */
    val accent: Color,
    /** `windowBackgroundColor`. */
    val windowBackground: Color,
    /** The detail column: windowBackground at 0.8 over the window material. */
    val mainContent: Color,
    /** The sidebar material. */
    val sidebar: Color,
    /** `controlBackgroundColor`; cards are this at 0.6 ([card]). */
    val controlBackground: Color,
    val separator: Color,
    /** `Color.gray`. */
    val gray: Color,
    val red: Color,
    val orange: Color,
    val green: Color,
    val blue: Color,
    /** Master-volume slider fill (`.tint(.red)`). */
    val redSliderFill: Color,
    /** Row striping (`Color.white.opacity(0.03)` on the dark Console). */
    val alternatingRow: Color,
    val isDark: Boolean
) {
    /** A card's fill: `controlBackgroundColor.opacity(0.6)`. */
    val card: Color get() = controlBackground.copy(alpha = controlBackground.alpha * 0.6f)

    /** A card's outline: `Color.gray.opacity(0.2)`. */
    val cardStroke: Color get() = gray.copy(alpha = 0.2f)

    /** Pill / segmented-control outlines: `Color.gray.opacity(0.3)`. */
    val pillStroke: Color get() = gray.copy(alpha = 0.3f)

    companion object {
        val Dark =
            DspiColors(
                label = Color(1f, 1f, 1f, 0.847f),
                secondaryLabel = Color(1f, 1f, 1f, 0.549f),
                tertiaryLabel = Color(1f, 1f, 1f, 0.247f),
                accent = Color(0xFF007AFF),
                windowBackground = Color(0xFF323232),
                mainContent = Color(0xFF303030),
                sidebar = Color(0xFF282828),
                controlBackground = Color(0xFF1E1E1E),
                separator = Color(1f, 1f, 1f, 0.098f),
                gray = Color(0xFF98989D),
                red = Color(0xFFFF453A),
                orange = Color(0xFFFF9F0A),
                green = Color(0xFF32D74B),
                blue = Color(0xFF0A84FF),
                redSliderFill = Color(0xFFC83F29),
                alternatingRow = Color(1f, 1f, 1f, 0.03f),
                isDark = true
            )

        val Light =
            DspiColors(
                label = Color(0f, 0f, 0f, 0.847f),
                secondaryLabel = Color(0f, 0f, 0f, 0.498f),
                tertiaryLabel = Color(0f, 0f, 0f, 0.259f),
                accent = Color(0xFF007AFF),
                windowBackground = Color(0xFFECECEC),
                mainContent = Color(0xFFF0F0F0),
                sidebar = Color(0xFFE3E3E3),
                controlBackground = Color(0xFFFFFFFF),
                separator = Color(0f, 0f, 0f, 0.098f),
                gray = Color(0xFF8E8E93),
                red = Color(0xFFFF3B30),
                orange = Color(0xFFFF9500),
                green = Color(0xFF28CD41),
                blue = Color(0xFF007AFF),
                redSliderFill = Color(0xFFD9442E),
                alternatingRow = Color(0f, 0f, 0f, 0.03f),
                isDark = false
            )
    }
}

private val LocalDspiColors = staticCompositionLocalOf { DspiColors.Dark }

/** Text styles named after the SwiftUI fonts the Console uses (scaled up a little for touch). */
object DspiType {
    /** `.body`. */
    val body = TextStyle(fontSize = 15.sp)

    /** `.body.monospacedDigit()` — value fields and readouts. */
    val bodyMono = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace)

    /** `.caption` + `.bold` — column headers, GAIN / DELAY labels. */
    val captionBold = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)

    /** `.caption` + `.medium` — pill buttons. */
    val captionMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)

    /** `.caption`. */
    val caption = TextStyle(fontSize = 12.sp)

    /** `.caption2` — sidebar labels ("Preset", "Source"). */
    val caption2 = TextStyle(fontSize = 11.sp)

    /** `.headline`. */
    val headline = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

    /** Tool-window section labels (`sectionLabel`): 10 pt bold, secondary. */
    val sectionLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)

    /** 9 pt monospaced (dashboard delay readouts, MuteableLabel). */
    val mono9 = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace)

    /** 10 pt monospaced (dashboard rows). */
    val mono10 = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

/**
 * App theme: the macOS Console's palette over Material 3, so Material
 * components (dialogs, menus, switches) pick up the same accent and surfaces.
 */
@Composable
fun DspiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DspiColors.Dark else DspiColors.Light
    val scheme =
        if (darkTheme) {
            darkColorScheme(
                primary = c.accent,
                onPrimary = Color.White,
                secondary = c.accent,
                background = c.mainContent,
                onBackground = c.label,
                surface = c.windowBackground,
                onSurface = c.label,
                surfaceVariant = c.controlBackground,
                onSurfaceVariant = c.secondaryLabel,
                surfaceContainer = c.windowBackground,
                surfaceContainerHigh = Color(0xFF3A3A3A),
                surfaceContainerHighest = Color(0xFF444444),
                outline = c.gray.copy(alpha = 0.35f),
                outlineVariant = c.separator,
                error = c.red
            )
        } else {
            lightColorScheme(
                primary = c.accent,
                onPrimary = Color.White,
                secondary = c.accent,
                background = c.mainContent,
                onBackground = c.label,
                surface = c.windowBackground,
                onSurface = c.label,
                surfaceVariant = c.controlBackground,
                onSurfaceVariant = c.secondaryLabel,
                outline = c.gray.copy(alpha = 0.35f),
                outlineVariant = c.separator,
                error = c.red
            )
        }
    CompositionLocalProvider(LocalDspiColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Accessors for the current theme tokens: `DspiTheme.colors.secondaryLabel`. */
object DspiTheme {
    val colors: DspiColors
        @Composable @ReadOnlyComposable
        get() = LocalDspiColors.current
}
