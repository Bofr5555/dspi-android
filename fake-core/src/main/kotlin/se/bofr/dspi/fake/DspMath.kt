// Port of DSPi-Console-Linux:core/src/dsp_math.rs @ 4683a2c (and the
// crossover_meta decoder of core/src/types.rs). Lives in :fake-core only, so
// demo-mode graphs look like the real core's; the app computes curves through
// the core (DspiCoreApi.computeMagnitudeCurve).
package se.bofr.dspi.fake

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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

object DspMath {
    const val SAMPLE_RATE = 48000.0
    const val MAGNITUDE_POINTS = 201

    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        companion object {
            val UNITY = Biquad(1.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    enum class XoverFamily { LINKWITZ_RILEY, BUTTERWORTH, BESSEL }

    data class XoverMeta(val family: XoverFamily, val order: Int, val highPass: Boolean)

    fun isPeq(t: Int) = t < FILTER_XOVER_FIRST

    fun isCrossover(t: Int) = t in FILTER_XOVER_FIRST..FILTER_XOVER_LAST

    fun crossoverMeta(t: Int): XoverMeta? {
        if (!isCrossover(t)) return null
        val rel = t - FILTER_XOVER_FIRST
        val hp = rel and 1 == 1
        val pair = rel / 2
        return when (pair) {
            in 0..3 -> XoverMeta(XoverFamily.LINKWITZ_RILEY, (pair + 1) * 2, hp)
            in 4..11 -> XoverMeta(XoverFamily.BUTTERWORTH, pair - 3, hp)
            else -> XoverMeta(XoverFamily.BESSEL, (pair - 11) * 2, hp)
        }
    }

    fun coefficients(p: FilterParams, fs: Double = SAMPLE_RATE): Biquad {
        if (p.filterType == FILTER_FLAT || !isPeq(p.filterType)) return Biquad.UNITY
        if (p.filterType == FILTER_LINKWITZ_TRANSFORM) {
            val f0 = p.freq.toDouble()
            val fp = p.gain.toDouble()
            if (fp <= 0.0 || f0 <= 0.0) return Biquad.UNITY
            val q0 = max(p.q.toDouble(), 0.1)
            val qp = max(p.qp.toDouble(), 0.1)
            val g0 = tan(PI * f0 / fs)
            val gp = tan(PI * fp / fs)
            val nb0 = 1.0 + g0 / q0 + g0 * g0
            val nb1 = 2.0 * (g0 * g0 - 1.0)
            val nb2 = 1.0 - g0 / q0 + g0 * g0
            val na0 = 1.0 + gp / qp + gp * gp
            val na1 = 2.0 * (gp * gp - 1.0)
            val na2 = 1.0 - gp / qp + gp * gp
            return Biquad(nb0 / na0, nb1 / na0, nb2 / na0, na1 / na0, na2 / na0)
        }
        val omega = 2.0 * PI * p.freq / fs
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / (2.0 * p.q)
        val a = 10.0.pow(p.gain / 40.0)
        val c: DoubleArray =
            when (p.filterType) {
                FILTER_LOWPASS -> doubleArrayOf((1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + alpha, -2 * cs, 1 - alpha)
                FILTER_HIGHPASS -> doubleArrayOf((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + alpha, -2 * cs, 1 - alpha)
                FILTER_PEAKING -> doubleArrayOf(1 + alpha * a, -2 * cs, 1 - alpha * a, 1 + alpha / a, -2 * cs, 1 - alpha / a)
                FILTER_LOWSHELF -> {
                    val sa = sqrt(a)
                    doubleArrayOf(
                        a * ((a + 1) - (a - 1) * cs + 2 * sa * alpha),
                        2 * a * ((a - 1) - (a + 1) * cs),
                        a * ((a + 1) - (a - 1) * cs - 2 * sa * alpha),
                        (a + 1) + (a - 1) * cs + 2 * sa * alpha,
                        -2 * ((a - 1) + (a + 1) * cs),
                        (a + 1) + (a - 1) * cs - 2 * sa * alpha
                    )
                }
                FILTER_HIGHSHELF -> {
                    val sa = sqrt(a)
                    doubleArrayOf(
                        a * ((a + 1) + (a - 1) * cs + 2 * sa * alpha),
                        -2 * a * ((a - 1) + (a + 1) * cs),
                        a * ((a + 1) + (a - 1) * cs - 2 * sa * alpha),
                        (a + 1) - (a - 1) * cs + 2 * sa * alpha,
                        2 * ((a - 1) - (a + 1) * cs),
                        (a + 1) - (a - 1) * cs - 2 * sa * alpha
                    )
                }
                FILTER_NOTCH -> doubleArrayOf(1.0, -2 * cs, 1.0, 1 + alpha, -2 * cs, 1 - alpha)
                FILTER_ALLPASS -> doubleArrayOf(1 - alpha, -2 * cs, 1 + alpha, 1 + alpha, -2 * cs, 1 - alpha)
                FILTER_ALLPASS1 -> {
                    val t = tan(omega / 2)
                    val ap = (t - 1) / (t + 1)
                    doubleArrayOf(ap, 1.0, 0.0, 1.0, ap, 0.0)
                }
                FILTER_LOWSHELF1 -> doubleArrayOf(a * sn + 1 + cs, a * sn - 1 - cs, 0.0, sn / a + 1 + cs, sn / a - 1 - cs, 0.0)
                FILTER_HIGHSHELF1 -> doubleArrayOf(sn + a + a * cs, sn - a - a * cs, 0.0, sn + 1 / a + cs / a, sn - 1 / a - cs / a, 0.0)
                FILTER_LOWPASS1 -> doubleArrayOf(sn, sn, 0.0, sn + 1 + cs, sn - 1 - cs, 0.0)
                FILTER_HIGHPASS1 -> doubleArrayOf(1 + cs, -1 - cs, 0.0, sn + 1 + cs, sn - 1 - cs, 0.0)
                else -> return Biquad.UNITY
            }
        val a0 = c[3]
        return Biquad(c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0)
    }

    fun sectionsFor(p: FilterParams, fs: Double = SAMPLE_RATE): List<Biquad> = when {
        p.filterType == FILTER_FLAT -> emptyList()
        isCrossover(p.filterType) -> crossoverSections(p.filterType, p.freq.toDouble(), fs)
        else -> listOf(coefficients(p, fs))
    }

    fun crossoverSections(t: Int, fcIn: Double, fs: Double = SAMPLE_RATE): List<Biquad> {
        val s = ArrayList<Biquad>(4)
        val meta = crossoverMeta(t) ?: return s
        val fc = fcIn.coerceIn(10.0, fs * 0.45)
        val omegaA = 2.0 * fs * tan(PI * fc / fs)
        when (meta.family) {
            XoverFamily.BUTTERWORTH -> designButterworth(s, meta.order, meta.highPass, omegaA, fs)
            XoverFamily.LINKWITZ_RILEY ->
                if (meta.order == 2) {
                    s += section2nd(1.0, 0.0, omegaA, meta.highPass, fs)
                } else {
                    designButterworth(s, meta.order / 2, meta.highPass, omegaA, fs)
                    s.addAll(s.toList())
                }
            XoverFamily.BESSEL -> for ((sigma, omega) in besselPoles(meta.order)) s += section2nd(sigma, omega, omegaA, meta.highPass, fs)
        }
        return s
    }

    private fun designButterworth(s: MutableList<Biquad>, order: Int, hp: Boolean, omegaA: Double, fs: Double) {
        if (order and 1 == 1) s += section1st(1.0, omegaA, hp, fs)
        for (p in 0 until order / 2) {
            val theta = if (order and 1 == 1) PI * (p + 1) / order else PI * (2 * p + 1) / (2.0 * order)
            s += section2nd(cos(theta), sin(theta), omegaA, hp, fs)
        }
    }

    private fun besselPoles(order: Int): List<Pair<Double, Double>> = when (order) {
        2 -> listOf(1.10160 to 0.63601)
        4 -> listOf(1.37007 to 0.41025, 0.99521 to 1.25711)
        6 -> listOf(1.57149 to 0.32090, 1.38186 to 0.97147, 0.93066 to 1.66186)
        8 -> listOf(1.75741 to 0.27287, 1.63694 to 0.82280, 1.37384 to 1.38836, 0.89287 to 1.99833)
        else -> emptyList()
    }

    private fun section2nd(sigmaIn: Double, omegaIn: Double, omegaA: Double, hp: Boolean, fs: Double): Biquad {
        var sigmaN = sigmaIn
        var omegaN = omegaIn
        if (hp) {
            val r2 = sigmaN * sigmaN + omegaN * omegaN
            if (r2 > 0) {
                sigmaN /= r2
                omegaN /= r2
            }
        }
        val sigma = sigmaN * omegaA
        val omega = omegaN * omegaA
        val k = 2.0 * fs
        val a = 2.0 * sigma
        val b = sigma * sigma + omega * omega
        val a0 = k * k + a * k + b
        val a1 = 2.0 * (b - k * k)
        val a2 = k * k - a * k + b
        val inv = 1.0 / a0
        return if (hp) {
            val kk = k * k * inv
            Biquad(kk, -2 * kk, kk, a1 * inv, a2 * inv)
        } else {
            val bb = b * inv
            Biquad(bb, 2 * bb, bb, a1 * inv, a2 * inv)
        }
    }

    private fun section1st(sigmaIn: Double, omegaA: Double, hp: Boolean, fs: Double): Biquad {
        val sigmaN = if (hp && sigmaIn > 0) 1.0 / sigmaIn else sigmaIn
        val sigma = sigmaN * omegaA
        val k = 2.0 * fs
        val inv = 1.0 / (k + sigma)
        return if (hp) {
            Biquad(
                k * inv,
                -k * inv,
                0.0,
                (sigma - k) * inv,
                0.0
            )
        } else {
            Biquad(sigma * inv, sigma * inv, 0.0, (sigma - k) * inv, 0.0)
        }
    }

    private fun magSquared(c: Biquad, w: Double): Double {
        val s = sin(w / 2)
        val phi = s * s
        val sb = c.b0 + c.b1 + c.b2
        val sa = 1.0 + c.a1 + c.a2
        val num = sb * sb - 4.0 * (c.b0 * c.b1 + c.b1 * c.b2 + 4.0 * c.b0 * c.b2) * phi + 16.0 * c.b0 * c.b2 * phi * phi
        val den = sa * sa - 4.0 * (c.a1 + c.a1 * c.a2 + 4.0 * c.a2) * phi + 16.0 * c.a2 * phi * phi
        return if (den <= 0.0) 1.0 else max(num, 0.0) / den
    }

    private fun phase(c: Biquad, w: Double): Double {
        val nr = c.b0 + c.b1 * cos(w) + c.b2 * cos(2 * w)
        val ni = -(c.b1 * sin(w) + c.b2 * sin(2 * w))
        val dr = 1.0 + c.a1 * cos(w) + c.a2 * cos(2 * w)
        val di = -(c.a1 * sin(w) + c.a2 * sin(2 * w))
        return atan2(ni, nr) - atan2(di, dr)
    }

    private fun active(f: FilterParams) = f.filterType != FILTER_FLAT && !f.bypass

    fun responseAt(freq: Float, filters: List<FilterParams>): Float {
        val w = 2.0 * PI * freq / SAMPLE_RATE
        var mag = 1.0
        for (f in filters.filter(::active)) for (c in sectionsFor(f)) mag *= magSquared(c, w)
        return (10.0 * log10(max(mag, 1e-30))).toFloat()
    }

    fun phaseAt(freq: Float, filters: List<FilterParams>): Float {
        val w = 2.0 * PI * freq / SAMPLE_RATE
        var ph = 0.0
        for (f in filters.filter(::active)) for (c in sectionsFor(f)) ph += phase(c, w)
        var deg = (ph * 180.0 / PI) % 360.0
        if (deg > 180.0) deg -= 360.0
        if (deg < -180.0) deg += 360.0
        return deg.toFloat()
    }

    fun curveFreq(i: Int): Double {
        val logMin = log10(10.0)
        val logMax = log10(20000.0)
        return 10.0.pow(logMin + i.toDouble() / (MAGNITUDE_POINTS - 1) * (logMax - logMin))
    }

    fun magnitudeCurve(filters: List<FilterParams>): List<Double> {
        val sections = filters.filter(::active).flatMap { sectionsFor(it) }
        return List(MAGNITUDE_POINTS) { i ->
            val w = 2.0 * PI * curveFreq(i) / SAMPLE_RATE
            var mag = 1.0
            for (c in sections) mag *= magSquared(c, w)
            10.0 * log10(max(mag, 1e-30))
        }
    }

    fun phaseCurve(filters: List<FilterParams>, unwrap: Boolean): List<Double> {
        val out = DoubleArray(MAGNITUDE_POINTS) { phaseAt(curveFreq(it).toFloat(), filters).toDouble() }
        if (unwrap) {
            for (i in 1 until MAGNITUDE_POINTS) {
                var d = out[i] - out[i - 1]
                while (d > 180.0) d -= 360.0
                while (d <= -180.0) d += 360.0
                out[i] = out[i - 1] + d
            }
        }
        return out.toList()
    }

    /** 0.1 dB, −0 → 0 (`quantize_gain`). */
    fun quantizeGain(db: Float): Float {
        val v = roundHalfAway(db * 10f) / 10f
        return if (v == 0f) 0f else v
    }

    /** 0.0001 ms, −0 → 0 (`quantize_delay`). */
    fun quantizeDelay(ms: Float): Float {
        val v = roundHalfAway(ms * 10000f) / 10000f
        return if (v == 0f) 0f else v
    }

    /** Rust `f32::round`: halves away from zero (Kotlin's `round` is half-even). */
    fun roundHalfAway(x: Float): Float = if (x < 0f) -floor(-x + 0.5f) else floor(x + 0.5f)
}
