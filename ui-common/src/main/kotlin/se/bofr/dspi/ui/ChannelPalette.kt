// Port of DSPi-Console-b5:DSPi Console/ChannelPalette.swift @ b5fdeac.
package se.bofr.dspi.ui

import androidx.compose.ui.graphics.Color

/**
 * The single source of truth for every per-channel colour in the app
 * (macOS `ChannelPalette`): sidebar pills, graph curves, matrix rows,
 * dashboard cards, settings lists. Nothing else spells out a channel colour.
 *
 * App channel ids (bridge `ChannelMap`): 0-1 inputs 1-2, 2-10 outputs 1-9,
 * 11-16 inputs 3-8.
 */
object ChannelPalette {
    /** Input channels 0..7 (`MAX_MATRIX_INPUTS`); 0/1 are the stereo blue/red. */
    val inputs: List<Color> =
        listOf(
            Color(0.29f, 0.56f, 0.89f), // 0 FL - blue
            Color(0.96f, 0.45f, 0.45f), // 1 FR - red
            Color(0.45f, 0.78f, 0.55f), // 2 FC - green
            Color(0.93f, 0.70f, 0.30f), // 3 LFE - amber
            Color(0.60f, 0.55f, 0.92f), // 4 BL - violet
            Color(0.90f, 0.55f, 0.78f), // 5 BR - pink
            Color(0.40f, 0.78f, 0.82f), // 6 SL - teal
            Color(0.80f, 0.72f, 0.42f) // 7 SR - olive
        )

    /** Output channels 0..7 — the four S/PDIF or I2S stereo pairs. */
    val outputs: List<Color> =
        listOf(
            Color(0.27f, 0.76f, 0.64f), // 0 - teal
            Color(0.35f, 0.82f, 0.50f), // 1 - green
            Color(0.94f, 0.77f, 0.35f), // 2 - amber
            Color(0.95f, 0.65f, 0.30f), // 3 - orange
            Color(0.35f, 0.55f, 0.95f), // 4 - blue
            Color(0.55f, 0.70f, 0.95f), // 5 - light blue
            Color(0.85f, 0.45f, 0.55f), // 6 - rose
            Color(0.95f, 0.60f, 0.65f) // 7 - pink
        )

    /** The PDM subwoofer output: purple, a single tone (no stereo partner). */
    val pdm = Color(0.73f, 0.53f, 0.95f)

    /** `.accentColor`, the fallback outside the 8-channel model. */
    val accent = Color(0xFF007AFF)

    /** Colour of input [index] (0..7), accent outside the model. */
    fun input(index: Int): Color = inputs.getOrElse(index) { accent }

    /**
     * Colour of output [index] by matrix output index. The PDM output (index 8
     * on RP2350, 4 on RP2040) must be asked for with [isPdm] = true, as macOS
     * passes it explicitly.
     */
    fun output(index: Int, isPdm: Boolean = false): Color = if (isPdm) pdm else outputs.getOrElse(index) { accent }

    /**
     * Colour of an APP channel id on a device with [numOutputs] outputs (the
     * last output is the PDM one: 9 on RP2350, 5 on RP2040).
     */
    fun color(appCh: Int, numOutputs: Int = 9): Color = when {
        appCh in 0..1 -> input(appCh)
        appCh in 11..16 -> input(appCh - 11 + 2)
        appCh in 2..10 -> (appCh - 2).let { output(it, isPdm = numOutputs > 0 && it == numOutputs - 1) }
        else -> accent
    }

    /** Parse the bridge's `channelColor()` hex ("#RRGGBB" or "#AARRGGBB"); [fallback] if malformed. */
    fun fromHex(hex: String, fallback: Color = accent): Color {
        val s = hex.removePrefix("#")
        val v = s.toLongOrNull(16) ?: return fallback
        return when (s.length) {
            6 -> Color(0xFF000000 or v)
            8 -> Color(v)
            else -> fallback
        }
    }
}
