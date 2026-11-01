// Port of the value formatting of DSPi-Console:DSPi Console/Components.swift
// (`ValueField.format`) and the `String(format:)` readouts of the tool views
// @ v1.1.6-beta5 (b5fdeac). Pure JVM logic, tested in ToolFormatTest.
//
// ValueField formatting, signed readouts and typed-entry parsing are
// :ui-common's ValueFormat (decision E6); only the "-0"-free `%.Nf` readout
// and the comparison stay here.
package se.bofr.dspi.feature.tools

import java.util.Locale
import kotlin.math.abs

object ToolFormat {
    /** `String(format: "%.{n}f", v)` (C rounding, '.' separator). "-0" is shown as "0". */
    fun fixed(v: Float, decimals: Int): String {
        val s = String.format(Locale.ROOT, "%.${decimals}f", v.toDouble())
        return if (s.startsWith("-") && s.drop(1).all { it == '0' || it == '.' }) s.drop(1) else s
    }

    /** Close enough to compare a slider position with a committed value. */
    fun same(a: Float, b: Float): Boolean = abs(a - b) < 1e-4f
}
