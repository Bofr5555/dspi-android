// Port of DSPi-Console-Windows:DSPiConsole.Core/GraphEditing/PeqGraphModel.cs
// @ v1.1.6-beta4 (itself a port of DSPi-Console-b5:DSPi Console/PeqGraphModel.swift
// @ b5fdeac). The pure logic behind on-graph PEQ editing: coordinate mapping,
// what each filter type's dot means, the shapes a click creates, band colours
// and value text. No views here, so all of it is unit-testable.
package se.bofr.dspi.feature.graph

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_LINKWITZ_TRANSFORM
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_NOTCH
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.core.DspiConstants.FILTER_XOVER_FIRST
import se.bofr.dspi.core.DspiConstants.FILTER_XOVER_LAST
import se.bofr.dspi.core.FilterParams

/**
 * Firmware filter type helpers (Windows `FilterTypeExtensions`, macOS
 * `FilterType.usesGain/usesQ`). Types are the core's `FILTER_*` ints.
 */
object FilterTypes {
    /** The PEQ-only filter types (0-13), Off included. */
    val PEQ_TYPES: List<Int> =
        listOf(
            FILTER_FLAT, FILTER_PEAKING, FILTER_LOWSHELF, FILTER_HIGHSHELF, FILTER_LOWPASS, FILTER_HIGHPASS,
            FILTER_NOTCH, FILTER_ALLPASS, FILTER_ALLPASS1, FILTER_LOWSHELF1, FILTER_HIGHSHELF1,
            FILTER_LINKWITZ_TRANSFORM, FILTER_LOWPASS1, FILTER_HIGHPASS1
        )

    fun isCrossover(type: Int) = type in FILTER_XOVER_FIRST..FILTER_XOVER_LAST

    fun isLinkwitzTransform(type: Int) = type == FILTER_LINKWITZ_TRANSFORM

    fun hasGain(type: Int) = type == FILTER_PEAKING ||
        type == FILTER_LOWSHELF ||
        type == FILTER_HIGHSHELF ||
        type == FILTER_LOWSHELF1 ||
        type == FILTER_HIGHSHELF1

    /** First-order types (all-pass, shelves, low/high pass) are defined by frequency alone. */
    fun hasQ(type: Int) = type == FILTER_PEAKING ||
        type == FILTER_LOWSHELF ||
        type == FILTER_HIGHSHELF ||
        type == FILTER_LOWPASS ||
        type == FILTER_HIGHPASS ||
        type == FILTER_NOTCH ||
        type == FILTER_ALLPASS

    /** Windows `FilterParams.DefaultQ`: neutral (Butterworth) Q. */
    const val DEFAULT_Q = 0.707f

    /** A band set to Off (Windows `new FilterParams()`). */
    fun off(): FilterParams = FilterParams(FILTER_FLAT, false, 1000f, DEFAULT_Q, 0f, DEFAULT_Q)

    /** Windows `new FilterParams(type, freq, q, gain)`. */
    fun band(type: Int, freq: Float, q: Float, gain: Float): FilterParams = FilterParams(type, false, freq, q, gain, DEFAULT_Q)
}

/**
 * Point ↔ frequency / dB mapping for the plot area: log frequency across the
 * full width, dB top to bottom. Coordinates are local to the plot rect, with y
 * growing downward (dp on Android).
 */
data class PeqGraphGeometry(
    val width: Double,
    val height: Double,
    val minFreq: Double,
    val maxFreq: Double,
    val dbTop: Double,
    val dbBottom: Double
) {
    private val logMin get() = log10(max(minFreq, 1.0))
    private val logSpan get() = max(log10(max(maxFreq, 2.0)) - logMin, 1e-6)
    val dbSpan get() = max(dbTop - dbBottom, 1e-6)

    fun x(freq: Double): Double = (log10(max(freq, 1e-3)) - logMin) / logSpan * width

    fun freq(x: Double): Double = 10.0.pow(logMin + x / max(width, 1.0) * logSpan)

    fun y(db: Double): Double = (dbTop - db) / dbSpan * height

    fun db(y: Double): Double = dbTop - y / max(height, 1.0) * dbSpan

    /** dB per point, for turning a vertical drag into a gain change. */
    val dbPerPoint get() = dbSpan / max(height, 1.0)
}

/**
 * The shapes the graph creates and offers, named as FabFilter names them. Each
 * maps onto the firmware's types; an order picks the 6 dB/oct (first order) or
 * 12 dB/oct (second order) variant where both exist.
 */
enum class PeqShape {
    BELL,
    LOW_SHELF,
    LOW_CUT,
    HIGH_SHELF,
    HIGH_CUT,
    NOTCH,
    ALL_PASS;

    val title: String
        get() =
            when (this) {
                BELL -> "Bell"
                LOW_SHELF -> "Low Shelf"
                LOW_CUT -> "Low Cut"
                HIGH_SHELF -> "High Shelf"
                HIGH_CUT -> "High Cut"
                NOTCH -> "Notch"
                ALL_PASS -> "All Pass"
            }

    /** The firmware type for this shape at [order] (1 or 2), or null when the shape has no such order. */
    fun type(order: Int): Int? = when (this) {
        BELL -> if (order == 2) FILTER_PEAKING else null
        LOW_SHELF -> if (order == 2) {
            FILTER_LOWSHELF
        } else if (order == 1) {
            FILTER_LOWSHELF1
        } else {
            null
        }
        LOW_CUT -> if (order == 2) {
            FILTER_HIGHPASS
        } else if (order == 1) {
            FILTER_HIGHPASS1
        } else {
            null
        }
        HIGH_SHELF -> if (order == 2) {
            FILTER_HIGHSHELF
        } else if (order == 1) {
            FILTER_HIGHSHELF1
        } else {
            null
        }
        HIGH_CUT -> if (order == 2) {
            FILTER_LOWPASS
        } else if (order == 1) {
            FILTER_LOWPASS1
        } else {
            null
        }
        NOTCH -> if (order == 2) FILTER_NOTCH else null
        ALL_PASS -> if (order == 2) {
            FILTER_ALLPASS
        } else if (order == 1) {
            FILTER_ALLPASS1
        } else {
            null
        }
    }

    /** Slope labels for the two orders, when a shape has both. */
    fun orderTitle(order: Int): String = if (this == ALL_PASS) {
        if (order == 1) "1st Order" else "2nd Order"
    } else {
        if (order == 1) "6 dB/oct" else "12 dB/oct"
    }

    /** The compact label for an order on the shape page. */
    fun orderLabel(order: Int): String = if (this == ALL_PASS) {
        if (order == 1) "180°" else "360°"
    } else {
        if (order == 1) "6 dB" else "12 dB"
    }

    val isCut get() = this == LOW_CUT || this == HIGH_CUT

    /** The two-letter code the rest of the app shows for this shape. */
    val code: String
        get() =
            when (this) {
                BELL -> "PK"
                LOW_SHELF -> "LS"
                LOW_CUT -> "LC"
                HIGH_SHELF -> "HS"
                HIGH_CUT -> "HC"
                NOTCH -> "NT"
                ALL_PASS -> "AP"
            }

    companion object {
        val ALL: List<PeqShape> = entries.toList()

        /** The shape and order of a firmware type; null for types the graph does not edit. */
        fun of(type: Int): Pair<PeqShape, Int>? {
            for (shape in ALL) for (order in intArrayOf(2, 1)) if (shape.type(order) == type) return shape to order
            return null
        }

        /** The order a shape creates on its own: second where available. */
        fun defaultOrder(shape: PeqShape, available: Set<Int>): Int? {
            for (order in intArrayOf(2, 1)) {
                val t = shape.type(order)
                if (t != null && t in available) return order
            }
            return null
        }

        /** The orders of a shape the firmware supports, ascending. */
        fun orders(shape: PeqShape, available: Set<Int>): List<Int> =
            listOf(1, 2).filter { o -> shape.type(o)?.let { it in available } == true }
    }
}

/** What a band's dot stands for, which decides where it sits and what a vertical drag changes. */
data class PeqNodeRole(val kind: Kind, val scale: Double = 1.0, val fixedDb: Double = 0.0) {
    enum class Kind {
        /** On the band's own curve at its frequency, [scale] dB per dB of gain; follows the pointer by gain. */
        GAIN,

        /** A second-order cut: the dot sits at 20·log10(Q), so following the pointer changes Q. */
        RESONANCE,

        /** Fixed height (notch, all-pass, first-order cut). A drag moves frequency only. */
        FIXED,

        /** Shown and selectable, but not dragged (the Linkwitz Transform). */
        LOCKED
    }

    /** The dot's height in dB for [p]. */
    fun dbFor(p: FilterParams): Double = when (kind) {
        Kind.GAIN -> p.gain * scale
        Kind.RESONANCE -> 20 * log10(max(p.q.toDouble(), 0.01))
        else -> fixedDb
    }

    /** The gain scale for a gain role, 1 otherwise. */
    val gainScale get() = if (kind == Kind.GAIN) scale else 1.0

    companion object {
        fun of(p: FilterParams): PeqNodeRole {
            val probe = p.copy(bypass = false)
            return when (p.filterType) {
                FILTER_PEAKING, FILTER_LOWSHELF, FILTER_HIGHSHELF, FILTER_LOWSHELF1, FILTER_HIGHSHELF1 -> {
                    // Measured rather than assumed, so the dot stays on the curve
                    // for whatever shelf formula the firmware uses.
                    val g = probe.copy(gain = 12f)
                    val atF0 = PeqDspMath.responseAt(g.freq, listOf(g)).toDouble()
                    PeqNodeRole(Kind.GAIN, max(abs(atF0 / 12), 0.05))
                }
                FILTER_LOWPASS, FILTER_HIGHPASS -> PeqNodeRole(Kind.RESONANCE)
                FILTER_LOWPASS1, FILTER_HIGHPASS1 ->
                    PeqNodeRole(Kind.FIXED, fixedDb = PeqDspMath.responseAt(probe.freq, listOf(probe)).toDouble())
                FILTER_LINKWITZ_TRANSFORM ->
                    PeqNodeRole(Kind.LOCKED, fixedDb = PeqDspMath.responseAt(probe.freq, listOf(probe)).toDouble())
                else -> PeqNodeRole(Kind.FIXED, fixedDb = 0.0)
            }
        }
    }
}

/**
 * Limits the graph enforces. Q and frequency match the firmware's own clamps;
 * gain is FabFilter's ±30 dB, which the firmware does not bound itself.
 */
object PeqLimits {
    const val GAIN_MIN = -30.0
    const val GAIN_MAX = 30.0
    const val Q_MIN = 0.1
    const val Q_MAX = 20.0
    const val FREQ_MIN = 10.0
    val FREQ_MAX = PeqDspMath.SAMPLE_RATE * 0.45

    fun clamp(v: Double, lo: Double, hi: Double) = min(max(v, lo), hi)

    fun clampGain(v: Double) = clamp(v, GAIN_MIN, GAIN_MAX)

    fun clampQ(v: Double) = clamp(v, Q_MIN, Q_MAX)

    fun clampFreq(v: Double) = clamp(v, FREQ_MIN, FREQ_MAX)
}

/** True for the bands the graph draws a dot for. */
fun FilterParams.isGraphBand(): Boolean = filterType != FILTER_FLAT && !FilterTypes.isCrossover(filterType)

/**
 * The band as [newType], carrying over what carries: entering a Linkwitz
 * Transform seeds a neutral target, leaving one clears its fp-in-gain.
 */
fun FilterParams.retyped(newType: Int): FilterParams {
    var q = copy(filterType = newType)
    if (FilterTypes.isLinkwitzTransform(newType) && !FilterTypes.isLinkwitzTransform(filterType)) {
        val f = max(freq, PeqLimits.FREQ_MIN.toFloat())
        val qq = PeqLimits.clampQ(this.q.toDouble()).toFloat()
        q = q.copy(freq = f, q = qq, gain = f, qp = qq)
    } else if (!FilterTypes.isLinkwitzTransform(newType) && FilterTypes.isLinkwitzTransform(filterType)) {
        q = q.copy(gain = 0f)
    }
    return q
}

/**
 * A band part-way to [target], for animating a change made elsewhere:
 * frequency and Q move in the log domain. A change of type lands at once.
 */
fun FilterParams.interpolated(target: FilterParams, t: Double): FilterParams {
    if (filterType != target.filterType || bypass != target.bypass || t >= 1) return target
    val f = exp(ln(freq.toDouble()) + (ln(target.freq.toDouble()) - ln(freq.toDouble())) * t).toFloat()
    val q0 = max(q.toDouble(), 0.01)
    val q1 = max(target.q.toDouble(), 0.01)
    val qq = exp(ln(q0) + (ln(q1) - ln(q0)) * t).toFloat()
    return target.copy(freq = f, q = qq, gain = gain + (target.gain - gain) * t.toFloat())
}

/** Exact field-for-field equality (Windows `SameAs`); the data class's equals is exactly that. */
fun FilterParams.sameAs(b: FilterParams): Boolean = this == b

/**
 * Which band a double-click on the graph makes: always a bell at the pointer's
 * frequency and level. Dragging the curve itself makes a shelf near either end
 * and a bell elsewhere.
 */
object PeqCreation {
    const val SHELF_ZONE = 0.12

    fun band(x: Double, y: Double, g: PeqGraphGeometry, available: Set<Int>, fromCurve: Boolean): FilterParams {
        val fx = x / max(g.width, 1.0)
        val freq = PeqLimits.clampFreq(g.freq(x)).toFloat()
        val p = FilterTypes.band(FILTER_PEAKING, freq, 1f, 0f)
        if (fromCurve) {
            if (fx < SHELF_ZONE && FILTER_LOWSHELF in available) return p.copy(filterType = FILTER_LOWSHELF, q = 0.707f)
            if (fx > 1 - SHELF_ZONE && FILTER_HIGHSHELF in available) return p.copy(filterType = FILTER_HIGHSHELF, q = 0.707f)
            return p
        }
        return p.copy(gain = PeqLimits.clampGain(g.db(y)).toFloat())
    }
}

/** An sRGB colour 0..1 (Windows `(float R, float G, float B)` tuples). */
data class PeqRgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val WHITE = PeqRgb(1f, 1f, 1f)
    }
}

/** One hue per band, so a band keeps its colour in the list below the graph. */
object PeqBandPalette {
    private val RGB =
        listOf(
            PeqRgb(0.93f, 0.47f, 0.45f), // coral
            PeqRgb(0.95f, 0.64f, 0.36f), // orange
            PeqRgb(0.92f, 0.79f, 0.40f), // amber
            PeqRgb(0.55f, 0.80f, 0.52f), // sage
            PeqRgb(0.36f, 0.77f, 0.68f), // teal
            PeqRgb(0.44f, 0.68f, 0.94f), // sky
            PeqRgb(0.58f, 0.60f, 0.94f), // periwinkle
            PeqRgb(0.73f, 0.57f, 0.92f), // lavender
            PeqRgb(0.89f, 0.54f, 0.72f), // rose
            PeqRgb(0.80f, 0.62f, 0.50f) // clay
        )

    fun color(band: Int): PeqRgb = RGB[((band % RGB.size) + RGB.size) % RGB.size]

    /** 0xAARRGGBB. */
    fun argb(band: Int): Long {
        val c = color(band)
        return (0xFFL shl 24) or ((c.r * 255).roundToInt().toLong() shl 16) or
            ((c.g * 255).roundToInt().toLong() shl 8) or (c.b * 255).roundToInt().toLong()
    }
}

/**
 * Display and entry of band values, including FabFilter's shortcuts: "2k" for
 * 2000 Hz and note names such as "A4" or "C#2+13" (cents).
 */
object PeqValueText {
    private fun fmt(pattern: String, v: Double) = String.format(Locale.ROOT, pattern, v)

    fun frequency(hz: Double): String = when {
        hz >= 10_000 -> fmt("%.2f", hz / 1000) + " kHz"
        hz >= 1000 -> fmt("%.3f", hz / 1000) + " kHz"
        hz >= 100 -> fmt("%.1f", hz) + " Hz"
        else -> fmt("%.2f", hz) + " Hz"
    }

    fun shortFrequency(hz: Double): String =
        if (hz >= 1000) fmt(if (hz >= 10_000) "%.1f" else "%.2f", hz / 1000) + "k" else fmt(if (hz >= 100) "%.0f" else "%.1f", hz)

    fun gain(db: Double): String {
        val v = if (abs(db) < 0.005) 0.0 else db
        return (if (v >= 0) "+" else "") + fmt("%.2f", v) + " dB"
    }

    fun q(q: Double): String = fmt("%.3f", q)

    /**
     * Two decimal places, truncated rather than rounded, so the chip never shows
     * a value the band does not quite have. The small epsilon keeps float noise
     * (1.1 stored as 1.0999999) from dropping a digit.
     */
    fun truncated(v: Double, sign: Boolean = false): String {
        val t = floor(abs(v) * 100 + 1e-5) / 100
        val text = fmt("%.2f", t)
        if (t == 0.0) return if (sign) "+$text" else text
        return if (v < 0) {
            "-$text"
        } else if (sign) {
            "+$text"
        } else {
            text
        }
    }

    /** A frequency as (number, unit) for the chip. */
    fun frequencyParts(hz: Double): Pair<String, String> = if (hz >= 1000) truncated(hz / 1000) to "kHz" else truncated(hz) to "Hz"

    private fun parseDouble(s: String): Double? {
        // C# NumberStyles.Float: optional sign, digits, point, exponent; no other text.
        if (!Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").matches(s)) return null
        val v = s.toDoubleOrNull() ?: return null
        return if (v.isFinite()) v else null
    }

    fun parseFrequency(text: String): Double? {
        var s = text.trim().lowercase(Locale.ROOT)
        parseNote(s)?.let { return it }
        s = s.replace(" ", "")
        var scale = 1.0
        if (s.endsWith("khz")) {
            scale = 1000.0
            s = s.dropLast(3)
        } else if (s.endsWith("hz")) {
            s = s.dropLast(2)
        }
        if (s.endsWith("k")) {
            scale = 1000.0
            s = s.dropLast(1)
        }
        val v = parseDouble(s) ?: return null
        return v * scale
    }

    fun parseNumber(text: String, unit: String): Double? {
        var s = text.trim().lowercase(Locale.ROOT).replace(" ", "")
        if (unit.isNotEmpty() && s.endsWith(unit)) s = s.dropLast(unit.length)
        if (s.startsWith("q")) s = s.substring(1)
        if (s.startsWith("+")) s = s.substring(1)
        return parseDouble(s)
    }

    /** "A4" = 440 Hz; "C#2+13" is C sharp 2 raised 13 cents. C4 = middle C. */
    fun parseNote(s: String): Double? {
        if (s.isEmpty()) return null
        var semitone =
            when (s[0]) {
                'c' -> 0
                'd' -> 2
                'e' -> 4
                'f' -> 5
                'g' -> 7
                'a' -> 9
                'b' -> 11
                else -> return null
            }
        var i = 1
        if (i < s.length && s[i] == '#') {
            semitone += 1
            i++
        } else if (i < s.length && s[i] == 'b' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-')) {
            semitone -= 1
            i++
        }
        val octaveText = StringBuilder()
        if (i < s.length && s[i] == '-') {
            octaveText.append('-')
            i++
        }
        while (i < s.length && s[i].isDigit()) {
            octaveText.append(s[i])
            i++
        }
        val octave = octaveText.toString().toIntOrNull() ?: return null
        var cents = 0.0
        if (i < s.length) cents = parseDouble(s.substring(i)) ?: return null
        val midi = (octave + 1) * 12 + semitone + cents / 100
        return 440 * 2.0.pow((midi - 69) / 12)
    }
}
