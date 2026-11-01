// Port of DSPi-Console-b5:DSPi Console/TestSignalsView.swift @ b5fdeac,
// `SiggenGlyph`: a miniature hand-drawn waveform per signal type.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

@Composable
internal fun SiggenGlyph(type: Int, color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val path = siggenGlyphPath(type, size.width, size.height)
            val stroke = Stroke(width = 1.4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind { drawPath(path, color, style = stroke) }
        }
    )
}

/** The glyph's path in a `w` x `h` rect. */
internal fun siggenGlyphPath(type: Int, w: Float, h: Float): Path {
    val p = Path()
    val midY = h / 2
    fun x(t: Double) = (t * w).toFloat()
    fun y(v: Double) = (midY - v * h * 0.42).toFloat()
    fun moveTo(t: Double, v: Double) = p.moveTo(x(t), y(v))
    fun lineTo(t: Double, v: Double) = p.lineTo(x(t), y(v))
    fun sampled(points: Int = 64, f: (Double) -> Double) {
        moveTo(0.0, f(0.0))
        for (i in 1..points) {
            val t = i.toDouble() / points
            lineTo(t, f(t))
        }
    }

    // Deterministic "random" for the noise glyphs.
    fun jitter(t: Double, scale: Double) = sin(t * 91.7 + 1.3) * 0.5 + sin(t * 173.3) * 0.35 + sin(t * 47.9 + 4.1) * 0.15 * scale

    when (type) {
        SIGGEN_SQUARE -> {
            val period = 0.5
            moveTo(0.0, 1.0)
            var t = 0.0
            var high = true
            while (t < 1) {
                val next = min(t + period / 2, 1.0)
                lineTo(next, if (high) 1.0 else -1.0)
                if (next < 1) lineTo(next, if (high) -1.0 else 1.0)
                high = !high
                t = next
            }
        }
        SIGGEN_WHITE -> sampled(40) { jitter(it, 1.0) * 1.5 }
        SIGGEN_PINK -> sampled(40) { sin(it * 2 * PI * 1.3) * 0.7 + jitter(it, 1.0) * 0.5 }
        SIGGEN_SWEEP_LOG -> sampled { sin(2 * PI * 1.2 * (6.0.pow(it) - 1)) }
        SIGGEN_SWEEP_LIN -> sampled { sin(2 * PI * (1 + 4 * it) * it) }
        SIGGEN_SWEEP_STEP -> {
            val steps = 4
            for (i in 0 until steps) {
                val t0 = i.toDouble() / steps
                val t1 = (i + 1).toDouble() / steps
                val v = i.toDouble() / (steps - 1) * 1.6 - 0.8
                moveTo(t0, v)
                lineTo(t1 - 0.04, v)
            }
        }
        SIGGEN_IMPULSE -> {
            moveTo(0.0, 0.0)
            lineTo(0.45, 0.0)
            lineTo(0.48, 1.0)
            lineTo(0.51, 0.0)
            lineTo(1.0, 0.0)
        }
        SIGGEN_CLICKS_ALT -> {
            moveTo(0.0, 0.0)
            lineTo(0.28, 0.0)
            lineTo(0.31, 1.0)
            lineTo(0.34, 0.0)
            lineTo(0.64, 0.0)
            lineTo(0.67, -1.0)
            lineTo(0.70, 0.0)
            lineTo(1.0, 0.0)
        }
        SIGGEN_POLARITY -> sampled { t -> if (t > 0.3 && t < 0.7) sin((t - 0.3) / 0.4 * PI) else 0.0 }
        SIGGEN_TONE_BURST -> sampled { t ->
            val window = if (t > 0.15 && t < 0.55) sin((t - 0.15) / 0.4 * PI) else 0.0
            sin(t * 2 * PI * 6) * window
        }
        SIGGEN_TONE_PAIR -> sampled { t -> sin(t * 2 * PI * 1.5) * 0.72 + sin(t * 2 * PI * 11) * 0.28 }
        SIGGEN_MULTITONE -> sampled { t -> (sin(t * 2 * PI * 1.5) + sin(t * 2 * PI * 3.7 + 1) + sin(t * 2 * PI * 7.3 + 2)) / 2.6 }
        SIGGEN_ISP -> {
            // The +1 +1 -1 -1 sample staircase with the implied over-peak arc.
            moveTo(0.05, 0.7)
            lineTo(0.45, 0.7)
            moveTo(0.55, -0.7)
            lineTo(0.95, -0.7)
            moveTo(0.05, 0.7)
            p.quadraticTo(x(0.25), y(1.15), x(0.45), y(0.7))
        }
        SIGGEN_CHANNEL_ID -> {
            for ((i, c) in listOf(0.18, 0.5, 0.82).withIndex()) {
                val width = 0.11
                val amp = 0.45 + i * 0.27
                moveTo(c - width, 0.0)
                for (s in 1..12) {
                    val t = c - width + s / 12.0 * width * 2
                    val ph = (t - (c - width)) / (width * 2)
                    lineTo(t, sin(ph * PI) * amp * sin(ph * PI * 6))
                }
            }
        }
        else -> sampled { sin(it * 2 * PI * 2) }
    }
    return p
}
