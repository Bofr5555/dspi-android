// Drawing helpers shared by the tool views' display-only graphs (the Swift
// views each repeat `xPos`/`yPos` and `.position(x:y:)`-placed Text labels).
package se.bofr.dspi.feature.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.log10

/** Pure graph mapping, JVM-tested. */
object ToolGraphMath {
    /** Logarithmic x for [freq] between [minFreq] and [maxFreq] (clamped), 0..[w]. */
    fun logX(freq: Double, minFreq: Double, maxFreq: Double, w: Float): Float {
        val f = freq.coerceIn(minFreq, maxFreq)
        return ((log10(f) - log10(minFreq)) / (log10(maxFreq) - log10(minFreq))).toFloat() * w
    }

    /** Linear y for [v] in [lo]..[hi] mapped to [h]..0. */
    fun linY(v: Double, lo: Double, hi: Double, h: Float): Float = (h - (v - lo) / (hi - lo) * h).toFloat()

    /**
     * The loudness/crossfeed graphs' dynamic domain: the data range, at least
     * 10 dB, plus 20% padding above and below.
     */
    fun dynamicDomain(values: List<Double>): Pair<Double, Double> {
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 0.0
        val range = maxOf(max - min, 10.0)
        val pad = range * 0.2
        return Pair(min - pad, max + pad)
    }

    /** Horizontal grid steps for a domain: 10 dB above a 40 dB range, else 5. */
    fun gridSteps(lo: Double, hi: Double): List<Double> {
        val step = if (hi - lo > 40) 10.0 else 5.0
        val out = mutableListOf<Double>()
        var v = ceil(lo / step) * step
        while (v <= hi) {
            out += v
            v += step
        }
        return out
    }

    /** "100", "1k", "10k". */
    fun freqLabel(f: Int): String = when (f) {
        1000 -> "1k"
        10000 -> "10k"
        else -> "$f"
    }
}

/** Draws [text] centred on [center] (SwiftUI `.position`). */
internal fun DrawScope.labelAt(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    color: Color,
    size: TextUnit = 10.sp,
    bold: Boolean = false,
    mono: Boolean = true,
    background: Color? = null
) {
    val layout =
        measurer.measure(
            text,
            TextStyle(
                fontSize = size,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                color = color
            )
        )
    val topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
    if (background != null) {
        drawRect(
            background,
            topLeft - Offset(2f, 0f),
            androidx.compose.ui.geometry.Size(layout.size.width + 4f, layout.size.height.toFloat())
        )
    }
    drawText(layout, topLeft = topLeft)
}
