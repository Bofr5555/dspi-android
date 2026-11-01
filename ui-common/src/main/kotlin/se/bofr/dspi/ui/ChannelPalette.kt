package se.bofr.dspi.ui

import androidx.compose.ui.graphics.Color

/**
 * Per-channel colours (macOS ChannelPalette.swift), indexed by APP channel id
 * (0-1 inputs 1-2, 2-10 outputs 1-9, 11-16 inputs 3-8).
 * WAVE 0 placeholder hues; package HOME copies the macOS values.
 */
object ChannelPalette {
    private val colors =
        listOf(
            0xFF60A5FA, 0xFFF87171, // inputs 1-2
            0xFF34D399, 0xFFFBBF24, 0xFFA78BFA, 0xFFF472B6, 0xFF22D3EE, 0xFFFB923C, 0xFF84CC16, 0xFFE879F9, 0xFF94A3B8, // outputs 1-9
            0xFF93C5FD, 0xFFFCA5A5, 0xFF6EE7B7, 0xFFFDE68A, 0xFFC4B5FD, 0xFFF9A8D4 // inputs 3-8
        ).map { Color(it) }

    fun color(appCh: Int): Color = colors.getOrElse(appCh) { Color.Gray }
}
