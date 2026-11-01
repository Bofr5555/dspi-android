// Port of DSPi-Console-b5:DSPi Console/PeqGraphRenderer.swift @ b5fdeac (Metal)
// by way of DSPi-Console-Windows:DSPiConsole/Controls/GraphEditing/
// PeqGraphRenderer.cs + Shaders/PeqLobes.hlsl @ v1.1.6-beta4 (Win2D): every
// band's lobe (faint, fading to nothing at 0 dB), the outlines of hovered and
// selected bands, the edited channel's combined curve with its glow, the dots
// and the marquee. Compose Canvas instead of a GPU pass: the lobe's per-pixel
// fade (pow(t, 1.5) toward 0 dB) is approximated by a vertical gradient per
// band (docs/UPSTREAM-DEVIATIONS.md GRAPH). Paths, brushes and strokes are
// cached per band and rebuilt only when that band's row or the axes change.
package se.bofr.dspi.feature.graph

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import se.bofr.dspi.core.FilterParams

/**
 * Draws a [PeqPicture] in dp coordinates (the caller scales the DrawScope by
 * the density). Each band's response row is computed only when that band or
 * the axis changes, so a drag evaluates the dragged band alone.
 */
class PeqGraphRenderer {
    private var columns = 0
    private var xs = DoubleArray(0)
    private var freqs = DoubleArray(0)
    private var tableWidth = -1.0
    private var tableMin = -1.0
    private var tableMax = -1.0
    private val rows = arrayOfNulls<DoubleArray>(PeqGraphEditor.Tuning.BAND_ROWS)
    private val rowBands = arrayOfNulls<FilterParams>(PeqGraphEditor.Tuning.BAND_ROWS)
    private var staticRow = DoubleArray(0)
    private var tableStatics: List<FilterParams>? = null

    /** Band rows evaluated so far, for tests and tuning (Windows `RowPasses`). */
    var rowPasses = 0
        private set

    /** One band's lobe and outline paths, rebuilt only when its row or the axes change. */
    private class BandShape {
        var row: DoubleArray? = null
        var zero = Float.NaN
        var top = Double.NaN
        var bottom = Double.NaN
        var height = Double.NaN
        val lobe = Path()
        val runs = Array(OUTLINE_RUNS) { Path() }
        var far = 0f
        var span = 0f
        var brush: Brush? = null
        var brushColor: PeqRgb? = null
    }

    private val shapes = Array(PeqGraphEditor.Tuning.BAND_ROWS) { BandShape() }
    private var combinedBuf = DoubleArray(0)
    private val combinedPath = Path()
    private val outlineStroke = Stroke(1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val marqueeStroke = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f)))
    private var curveStrokes = emptyArray<Stroke>()
    private var curveStrokeWidth = Float.NaN

    /** The combined curve (dB, offset included) per column of the last table (a reused buffer). */
    fun combined(pic: PeqPicture): DoubleArray {
        ensureTable(pic)
        if (combinedBuf.size != columns) combinedBuf = DoubleArray(columns)
        val out = combinedBuf
        out.fill(pic.offsetDb.toDouble())
        if (pic.flat) return out
        for (i in 0 until columns) out[i] += staticRow[i]
        for (b in pic.bands.indices) {
            val band = pic.bands[b] ?: continue
            if (band.bypass) continue
            val row = rows[b] ?: continue
            for (i in 0 until columns) out[i] += row[i]
        }
        return out
    }

    /** The edited channel's curve as a path (reused), for the view's blurred glow layers. */
    fun combinedPath(pic: PeqPicture): Path {
        val row = combined(pic)
        val g = pic.geometry
        combinedPath.rewind()
        if (columns == 0) return combinedPath
        combinedPath.moveTo(xs[0].toFloat(), curveY(row[0], g))
        for (i in 1 until columns) combinedPath.lineTo(xs[i].toFloat(), curveY(row[i], g))
        return combinedPath
    }

    private fun strokesFor(lw: Float): Array<Stroke> {
        if (lw != curveStrokeWidth) {
            curveStrokeWidth = lw
            curveStrokes =
                arrayOf(
                    Stroke(lw * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    Stroke(lw * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
        }
        return curveStrokes
    }

    /** The glow strokes (wide, narrow) for [lineWidth], shared with the view's blurred layers. */
    fun glowStroke(lineWidth: Float, narrow: Boolean): Stroke = strokesFor(lineWidth)[if (narrow) 1 else 0]

    /**
     * Draws the picture. [glow] draws the curve's unblurred glow fallback (the
     * same alphas as the other curves without a blur); with RenderEffect the
     * view draws the blurred glow from [combinedPath] instead.
     */
    fun draw(scope: DrawScope, pic: PeqPicture, background: Color, glow: Boolean = pic.glow) {
        val g = pic.geometry
        if (g.width <= 4 || g.height <= 4) return
        ensureTable(pic)
        // Bands are drawn on axes shifted by the level offset, which the
        // combined curve includes, so dots, fills and outlines stay on it.
        val bg = g.copy(dbTop = g.dbTop - pic.offsetDb, dbBottom = g.dbBottom - pic.offsetDb)
        val zero = bg.y(0.0).toFloat()
        with(scope) {
            clipRect(0f, 0f, g.width.toFloat(), g.height.toFloat()) {
                for (style in pic.bandStyles) shapeFor(style.row, bg, zero)
                drawLobes(this, pic, bg, zero)
                drawOutlines(this, pic)
                val combined = combinedPath(pic)
                val strokes = strokesFor(pic.lineWidth)
                val colour = pic.curveColor.toColor(1f)
                if (glow) {
                    drawPath(combined, colour, alpha = GLOW_FALLBACK_WIDE, style = strokes[0])
                    drawPath(combined, colour, alpha = GLOW_FALLBACK_NARROW, style = strokes[1])
                }
                drawPath(combined, colour, style = strokes[2])
            }
            for (n in pic.nodes) {
                val c = Offset(n.x.toFloat(), n.y.toFloat())
                drawCircle(n.color.toColor(1f), n.radius.toFloat(), c, alpha = n.opacity.coerceIn(0f, 1f))
                if (n.select > 0.001f) {
                    // A selected dot grows a centre in the graph's background colour.
                    val pip = 2.2f * n.select
                    val a = min(n.select * 2, 1f) * n.opacity
                    drawCircle(background, pip, c, alpha = a.coerceIn(0f, 1f))
                }
            }
            pic.marquee?.let { m ->
                val tl = Offset(m.x.toFloat(), m.y.toFloat())
                val size = Size(m.width.toFloat(), m.height.toFloat())
                drawRect(Color.White, tl, size, alpha = 15f / 255)
                drawRect(Color.White, tl, size, alpha = 115f / 255, style = marqueeStroke)
            }
        }
    }

    /** Rebuilds band [b]'s lobe and outline runs if its row or the axes moved. */
    private fun shapeFor(b: Int, g: PeqGraphGeometry, zero: Float): BandShape? {
        val row = rows.getOrNull(b) ?: return null
        val s = shapes[b]
        if (s.row === row && s.zero == zero && s.top == g.dbTop && s.bottom == g.dbBottom && s.height == g.height) return s
        s.row = row
        s.zero = zero
        s.top = g.dbTop
        s.bottom = g.dbBottom
        s.height = g.height
        s.brush = null
        // The lobe's far edge: how far the band's curve gets from 0 dB.
        var far = zero
        var span = 0f
        s.lobe.rewind()
        s.lobe.moveTo(xs[0].toFloat(), zero)
        for (i in 0 until columns) {
            val y = curveY(row[i], g)
            if (abs(y - zero) > span) {
                span = abs(y - zero)
                far = y
            }
            s.lobe.lineTo(xs[i].toFloat(), y)
        }
        s.lobe.lineTo(xs[columns - 1].toFloat(), zero)
        s.lobe.close()
        s.far = far
        s.span = span
        // A band's outline dissolves as it nears 0 dB, where the band does
        // nothing, so no band ever draws a baseline across the graph: the
        // stroke is split into runs whose alpha follows the distance to 0 dB.
        for (r in 1..OUTLINE_RUNS) {
            val lo = (r - 1).toFloat() / OUTLINE_RUNS * 10f
            val hi = r.toFloat() / OUTLINE_RUNS * 10f
            val path = s.runs[r - 1]
            path.rewind()
            var open = false
            for (i in 0 until columns) {
                val y = curveY(row[i], g)
                val d = abs(y - zero)
                val inRun = if (r == OUTLINE_RUNS) d >= lo else d >= lo && d < hi
                if (inRun) {
                    if (!open) path.moveTo(xs[i].toFloat(), y) else path.lineTo(xs[i].toFloat(), y)
                    open = true
                } else {
                    open = false
                }
            }
        }
        return s
    }

    private fun drawLobes(scope: DrawScope, pic: PeqPicture, g: PeqGraphGeometry, zero: Float) {
        for (style in pic.bandStyles) {
            if (style.fillOpacity <= 0.001f) continue
            val s = shapes.getOrNull(style.row)?.takeIf { it.row != null && it.row === rows.getOrNull(style.row) } ?: continue
            val top = max(g.y(style.reachHigh) - 2, 0.0).toFloat()
            val bottom = min(g.y(style.reachLow) + 2, g.height).toFloat()
            if (bottom - top <= 0.5f) continue
            // A band that barely departs from flat fades out too (smoothstep(2, 10, span)).
            val presence = smoothstep(2f, 10f, s.span)
            if (presence <= 0f) continue
            if (s.brush == null || s.brushColor != style.color) {
                s.brushColor = style.color
                s.brush =
                    Brush.verticalGradient(
                        0f to style.color.toColor(0f),
                        0.35f to style.color.toColor(0.2f),
                        1f to style.color.toColor(1f),
                        startY = zero,
                        endY = s.far
                    )
            }
            val alpha = (style.fillOpacity * presence).coerceIn(0f, 1f)
            scope.clipRect(0f, top, g.width.toFloat(), bottom) { drawPath(s.lobe, s.brush!!, alpha = alpha) }
        }
    }

    private fun drawOutlines(scope: DrawScope, pic: PeqPicture) {
        for (style in pic.bandStyles) {
            if (style.lineOpacity <= 0.001f) continue
            val s = shapes.getOrNull(style.row)?.takeIf { it.row != null && it.row === rows.getOrNull(style.row) } ?: continue
            val colour = style.color.toColor(1f)
            for (r in 1..OUTLINE_RUNS) {
                val lo = (r - 1).toFloat() / OUTLINE_RUNS * 10f
                val hi = r.toFloat() / OUTLINE_RUNS * 10f
                val alpha = style.lineOpacity * if (r == OUTLINE_RUNS) 1f else smoothstep(2f, 10f, (lo + hi) / 2)
                if (alpha <= 0.001f) continue
                scope.drawPath(s.runs[r - 1], colour, alpha = alpha.coerceIn(0f, 1f), style = outlineStroke)
            }
        }
    }

    private fun ensureTable(pic: PeqPicture) {
        val g = pic.geometry
        val cols = (ceil(g.width / 2).toInt() + 1).coerceIn(2, 4096)
        if (cols != columns || g.width != tableWidth || g.minFreq != tableMin || g.maxFreq != tableMax) {
            columns = cols
            tableWidth = g.width
            tableMin = g.minFreq
            tableMax = g.maxFreq
            xs = DoubleArray(cols) { g.width * it / (cols - 1) }
            freqs = DoubleArray(cols) { g.freq(xs[it]) }
            rows.fill(null)
            rowBands.fill(null)
            tableStatics = null
        }
        for (b in rows.indices) {
            val band = pic.bands.getOrNull(b)
            if (band == null) {
                rows[b] = null
                rowBands[b] = null
                continue
            }
            val prev = rowBands[b]
            if (prev != null && rows[b] != null && sameShape(prev, band)) continue
            val probe = band.copy(bypass = false)
            rows[b] = DoubleArray(columns) { PeqDspMath.responseAt(freqs[it].toFloat(), listOf(probe)).toDouble() }
            rowBands[b] = band
            rowPasses++
        }
        if (tableStatics != pic.statics) {
            tableStatics = pic.statics
            staticRow =
                DoubleArray(columns) {
                    if (pic.statics.isEmpty()) 0.0 else PeqDspMath.responseAt(freqs[it].toFloat(), pic.statics).toDouble()
                }
        }
    }

    private fun sameShape(a: FilterParams, b: FilterParams) =
        a.filterType == b.filterType && a.freq == b.freq && a.q == b.q && a.gain == b.gain && a.qp == b.qp

    companion object {
        fun curveY(db: Double, g: PeqGraphGeometry): Float {
            val d = if (db.isFinite()) db else -400.0
            return g.y(d).coerceIn(-2 * g.height, 3 * g.height).toFloat()
        }

        private const val OUTLINE_RUNS = 8

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}

fun PeqRgb.toColor(alpha: Float): Color = Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
