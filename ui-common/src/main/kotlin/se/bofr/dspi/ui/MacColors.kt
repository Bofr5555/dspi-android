// The macOS system colours (dark appearance) the ported views name directly
// (`.green`, `.orange`, `.red`, …), one copy for every feature (decision E6;
// replaces TOOLS' ToolColors, ANALYSIS' MacColors, MATRIX's Matrix* and
// SETTINGS' WarningOrange/OkGreen). Same values as DspiColors.Dark.
package se.bofr.dspi.ui

import androidx.compose.ui.graphics.Color

/** NSColor system colours, dark appearance. */
object MacColors {
    val red = Color(0xFFFF453A)
    val orange = Color(0xFFFF9F0A)
    val yellow = Color(0xFFFFD60A)
    val green = Color(0xFF32D74B)
    val blue = Color(0xFF0A84FF)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val gray = Color(0xFF8E8E93)
}
