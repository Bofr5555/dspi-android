// Port of the value formatting of DSPi-Console:DSPi Console/Components.swift
// (`ValueField.format`) and the `String(format:)` readouts of the tool views
// @ v1.1.6-beta5 (b5fdeac). Pure JVM logic, tested in ToolFormatTest.
//
// CONSOLIDATION NOTE: ValueField belongs to :ui-common (package HOME). This is
// a local copy so TOOLS does not edit HOME's module; fold it into ui-common's
// ValueField once that lands.
package se.bofr.dspi.feature.tools

import java.util.Locale
import kotlin.math.abs

object ToolFormat {
    /**
     * `ValueField.format(v, maxDecimals:, stripTrailingZeros:)`: `%.{n}f`, then the
     * trailing zeros of the decimals trimmed; an all-zero decimal part becomes
     * ".0", or nothing when [stripTrailingZeros].
     */
    fun format(v: Float, maxDecimals: Int, stripTrailingZeros: Boolean = false): String {
        val full = fixed(v, maxDecimals)
        val dot = full.indexOf('.')
        if (dot < 0) return full
        val int = full.substring(0, dot)
        val trimmed = full.substring(dot + 1).trimEnd('0')
        if (trimmed.isEmpty()) return if (stripTrailingZeros) int else "$int.0"
        return "$int.$trimmed"
    }

    /** `String(format: "%.{n}f", v)` (C rounding, '.' separator). "-0" is shown as "0". */
    fun fixed(v: Float, decimals: Int): String {
        val s = String.format(Locale.ROOT, "%.${decimals}f", v.toDouble())
        return if (s.startsWith("-") && s.drop(1).all { it == '0' || it == '.' }) s.drop(1) else s
    }

    /** `String(format: "%+.{n}f", v)`. */
    fun signed(v: Float, decimals: Int): String = String.format(Locale.ROOT, "%+.${decimals}f", v.toDouble())

    /**
     * A typed entry: the unit is optional, a Unicode minus is accepted, and the
     * value is clamped to [range] ("Values you type are kept within the row's
     * range"). Null when nothing numeric was typed.
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

    /** Close enough to compare a slider position with a committed value. */
    fun same(a: Float, b: Float): Boolean = abs(a - b) < 1e-4f
}
