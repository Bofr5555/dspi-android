// Port of DSPi-Console-b5:DSPi Console/Components.swift (ValueField.format,
// MasterVolumeTaper) and ContentView.swift (UserVolumeSection taper),
// DashboardView.swift (formatTrimmed) @ b5fdeac. Pure, JVM-tested.
package se.bofr.dspi.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Number formatting shared by every value readout. */
object ValueFormat {
    private fun fixed(v: Double, decimals: Int, signed: Boolean = false): String =
        String.format(Locale.ROOT, if (signed) "%+.${decimals}f" else "%.${decimals}f", v)

    /**
     * macOS `ValueField.format`: `%.<maxDecimals>f`, then trailing zeros of
     * the decimals trimmed; an all-zero decimal part becomes ".0", or
     * disappears with [stripTrailingZeros] ("1.000" -> "1").
     */
    fun format(v: Float, maxDecimals: Int = 1, stripTrailingZeros: Boolean = false): String {
        val full = fixed(v.toDouble(), maxDecimals)
        val dot = full.indexOf('.')
        if (dot < 0) return full
        val intPart = full.substring(0, dot)
        val trimmed = full.substring(dot + 1).trimEnd('0')
        if (trimmed.isEmpty()) return if (stripTrailingZeros) intPart else "$intPart.0"
        return "$intPart.$trimmed"
    }

    /** DashboardView `formatTrimmed`: like [format] but never strips ".0" and can force a sign. */
    fun formatTrimmed(value: Double, decimals: Int, signed: Boolean = false): String {
        val full = fixed(value, decimals, signed)
        val dot = full.indexOf('.')
        if (dot < 0) return full
        val trimmed = full.substring(dot + 1).trimEnd('0')
        return if (trimmed.isEmpty()) "${full.substring(0, dot)}.0" else "${full.substring(0, dot)}.$trimmed"
    }

    /** `String(format: "%.<d>f", v)`. */
    fun fixedPoint(v: Float, decimals: Int): String = fixed(v.toDouble(), decimals)

    /** `String(format: "%+.1f dB", v)` and friends. */
    fun signed(v: Float, decimals: Int): String = fixed(v.toDouble(), decimals, signed = true)

    /**
     * Parse a typed value as Swift's `Float(text)` does, also accepting a
     * decimal comma (Android number keyboards in many locales) and a Unicode
     * minus. null when not a number.
     */
    fun parse(text: String): Float? {
        val t = text.trim().replace(',', '.').replace('−', '-')
        if (t.isEmpty()) return null
        return t.toFloatOrNull()?.takeIf { !it.isNaN() }
    }

    /**
     * A typed entry of a ranged row (tool windows): the unit is optional
     * ("-3.5 dB"), a decimal comma and a Unicode minus are accepted, and the
     * value is kept within [range] ("Values you type are kept within the
     * row's range"). Null when nothing numeric was typed.
     */
    fun parse(text: String, range: ClosedFloatingPointRange<Float>): Float? {
        val cleaned =
            text.trim()
                .replace('−', '-')
                .replace(',', '.')
                .takeWhile { it.isDigit() || it == '-' || it == '+' || it == '.' }
        val v = cleaned.toFloatOrNull() ?: return null
        if (!v.isFinite()) return null
        return v.coerceIn(range.start, range.endInclusive)
    }
}

/**
 * The user-volume slider taper (`UserVolumeSection`): slider 0..1 to dB over
 * [-60, 0] with a square-root power taper, more travel near the top.
 */
object UserVolumeTaper {
    /** `USER_VOLUME_MIN_DB`. */
    const val MIN_DB = -60f

    fun scalarToDB(s: Double): Float {
        if (s <= 0) return MIN_DB
        val span = (-MIN_DB).toDouble()
        return (MIN_DB + span * sqrt(minOf(1.0, s))).toFloat()
    }

    fun dbToScalar(db: Float): Double {
        if (db <= MIN_DB) return 0.0
        val frac = (db - MIN_DB).toDouble() / (-MIN_DB).toDouble()
        return minOf(1.0, frac * frac)
    }

    /** `String(format: "%.1f dB", db)`. */
    fun displayString(db: Float): String = ValueFormat.fixedPoint(db, 1) + " dB"
}

/**
 * The firmware's piecewise-linear master volume taper (`MasterVolumeTaper`):
 *   0 to -10 dB: 0.1 dB steps over 100 units,
 *   -10 to -40 dB: 0.5 dB steps over 60 units,
 *   -40 to -128 dB: 1.0 dB steps over 88 units.
 * Slider 1.0 = 0 dB; 0.0 = -128 dB (mute, shown as "−∞").
 */
object MasterVolumeTaper {
    const val TOTAL_UNITS = 248f
    const val BREAK1 = 1f - 100f / TOTAL_UNITS
    const val BREAK2 = BREAK1 - 60f / TOTAL_UNITS
    const val MUTE_DB = -128f

    fun sliderToDB(pos: Float): Float {
        if (pos <= 0f) return MUTE_DB
        if (pos >= 1f) return 0f
        return when {
            pos > BREAK1 -> -(1f - pos) * TOTAL_UNITS * 0.1f
            pos > BREAK2 -> -10f - (BREAK1 - pos) * TOTAL_UNITS * 0.5f
            else -> -40f - (BREAK2 - pos) * TOTAL_UNITS * 1.0f
        }
    }

    fun dbToSlider(db: Float): Float {
        if (db <= MUTE_DB) return 0f
        if (db >= 0f) return 1f
        return when {
            db > -10f -> 1f - (-db / 0.1f) / TOTAL_UNITS
            db > -40f -> BREAK1 - ((-db - 10f) / 0.5f) / TOTAL_UNITS
            else -> BREAK2 - ((-db - 40f) / 1.0f) / TOTAL_UNITS
        }
    }

    /** Snap to the step the firmware applies in each region (0.1 / 0.5 / 1.0 dB). */
    fun displayValue(db: Float): Float {
        if (db <= MUTE_DB) return MUTE_DB
        val rounded =
            when {
                db > -10f -> roundHalfAway(db * 10f) / 10f
                db > -40f -> roundHalfAway(db * 2f) / 2f
                else -> roundHalfAway(db)
            }
        return if (rounded == 0f) 0f else rounded
    }

    /** "−∞" at -128 (mute), otherwise "%.1f" with a trailing ".0" stripped ("0", "-12", "-7.5"). */
    fun format(db: Float): String {
        if (db <= MUTE_DB) return "−∞"
        val s = ValueFormat.fixedPoint(displayValue(db), 1)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** Swift `rounded()` (schoolbook: half away from zero). */
    private fun roundHalfAway(x: Float): Float = (if (x < 0) -(abs(x).toDouble().roundToLong()) else x.toDouble().roundToLong()).toFloat()
}
