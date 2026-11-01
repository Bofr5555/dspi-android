// Port of DSPi-Console:DSPi Console/TubeIllustration.swift @ v1.1.6-beta5
// (b5fdeac) (cf. Linux qt/qml/components/TubeIllustration.qml). The geometry
// tables and the renderer are the Swift ones; Core Animation layers become
// three Canvas passes (tube, heater glow, audio bloom) whose alpha is animated.
// SwiftUI's `.blur` filters on the contact shadow and the heater streak have no
// DrawScope equivalent below API 31 and are drawn unblurred (see
// UPSTREAM-DEVIATIONS "TOOLS").
package se.bofr.dspi.feature.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.roundToInt
import kotlin.math.sqrt
import se.bofr.dspi.ui.MacColors

/** The visibly different kinds of tube among the sixteen the firmware models. */
enum class TubeFamily {
    NOVAL_TRIODE,
    NOVAL_PENTODE,
    NOVAL_POWER,
    OCTAL_GLASS,
    OCTAL_METAL,
    OCTAL_POWER_LARGE,
    OCTAL_POWER_SMALL,
    SHOULDERED_POWER,
    BEAM_BOTTLE,
    DIRECTLY_HEATED;

    companion object {
        /** Custom has no tube of its own, so it shows the default 12AX7 shape. */
        fun of(tubeType: Int): TubeFamily = when (tubeType) {
            6, 7 -> OCTAL_GLASS
            9 -> NOVAL_PENTODE
            10 -> OCTAL_METAL
            11 -> NOVAL_POWER
            12 -> OCTAL_POWER_LARGE
            13 -> SHOULDERED_POWER
            14 -> OCTAL_POWER_SMALL
            15 -> BEAM_BOTTLE
            16 -> DIRECTLY_HEATED
            else -> NOVAL_TRIODE
        }
    }
}

/**
 * `TubeAudioPulse`: the loudest routed output's peak, square-rooted so ordinary
 * listening levels show, quantised to 8 bits. [peaks] are indexed from
 * [outputStart] (app id 2 = output 1 here).
 */
object TubeAudioPulse {
    fun opacity(peaks: List<Float>, outputStart: Int, outputCount: Int, outputMask: Int): Float {
        var peak = 0f
        for (output in 0 until outputCount.coerceIn(0, 16)) {
            if ((outputMask shr output) and 1 == 0) continue
            val v = peaks.getOrNull(outputStart + output) ?: continue
            if (!v.isFinite()) continue
            peak = maxOf(peak, v)
        }
        return (sqrt(minOf(peak, 1f)) * 255f).roundToInt() / 255f
    }
}

/** One tube on a 120 x 200 design grid (x -60..60 about the axis, y 0 top .. 200 pin tips). */
internal data class TubeGeometry(
    val profile: List<Offset>,
    val top: Float,
    val metal: Boolean = false,
    val base: Base? = null,
    val pins: List<Float>,
    val pinTop: Float,
    val pinBottom: Float,
    val pinWidth: Float = 1.6f,
    val getter: ClosedFloatingPointRange<Float>? = null,
    val micas: List<Pair<Float, Float>> = emptyList(),
    val rods: List<Triple<Float, Float, Float>> = emptyList(),
    val plates: List<Plate> = emptyList(),
    val filament: List<Offset>? = null,
    val glows: List<Glow> = emptyList(),
    val ring: ClosedFloatingPointRange<Float>? = null
) {
    data class Plate(val rect: Rect, val fins: Float = 0f, val ribs: List<Float> = emptyList(), val mesh: Boolean = false)

    data class Glow(val at: Offset, val radius: Float)

    data class Base(val top: Float, val bottom: Float, val topHalf: Float, val bottomHalf: Float, val key: Boolean)

    val glassBottom: Float get() = profile.first().y
    val widestHalf: Float get() = profile.maxOfOrNull { it.x } ?: 30f

    companion object {
        private fun r(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

        private fun o(x: Float, y: Float) = Offset(x, y)

        fun of(family: TubeFamily): TubeGeometry = when (family) {
            TubeFamily.NOVAL_TRIODE -> TubeGeometry(
                profile = listOf(o(28f, 166f), o(28f, 72f)), top = 52f,
                pins = listOf(-20f, -10f, 0f, 10f, 20f), pinTop = 166f, pinBottom = 190f,
                getter = 52f..67f,
                micas = listOf(80f to 25f, 142f to 25f),
                rods = listOf(Triple(-25f, 76f, 164f), Triple(0f, 76f, 164f), Triple(25f, 76f, 164f)),
                plates = listOf(Plate(r(-23f, 84f, 20f, 54f), ribs = listOf(-13f)), Plate(r(3f, 84f, 20f, 54f), ribs = listOf(13f))),
                glows = listOf(Glow(o(-13f, 80f), 12f), Glow(o(13f, 80f), 12f), Glow(o(-13f, 142f), 11f), Glow(o(13f, 142f), 11f))
            )
            TubeFamily.NOVAL_PENTODE -> TubeGeometry(
                profile = listOf(o(28f, 166f), o(28f, 72f)), top = 52f,
                pins = listOf(-20f, -10f, 0f, 10f, 20f), pinTop = 166f, pinBottom = 190f,
                getter = 52f..67f,
                micas = listOf(80f to 25f, 142f to 25f),
                rods = listOf(Triple(-22f, 76f, 164f), Triple(22f, 76f, 164f)),
                plates = listOf(Plate(r(-17f, 86f, 34f, 52f), ribs = listOf(-6f, 6f))),
                glows = listOf(Glow(o(0f, 81f), 14f), Glow(o(0f, 143f), 12f))
            )
            TubeFamily.NOVAL_POWER -> TubeGeometry(
                profile = listOf(o(32f, 168f), o(32f, 58f)), top = 34f,
                pins = listOf(-22f, -11f, 0f, 11f, 22f), pinTop = 168f, pinBottom = 192f,
                getter = 34f..50f,
                micas = listOf(60f to 29f, 144f to 29f),
                rods = listOf(Triple(-29f, 56f, 166f), Triple(29f, 56f, 166f)),
                plates = listOf(Plate(r(-20f, 66f, 40f, 72f), fins = 5f, ribs = listOf(-7f, 7f))),
                glows = listOf(Glow(o(0f, 61f), 16f), Glow(o(0f, 144f), 14f))
            )
            TubeFamily.OCTAL_GLASS -> TubeGeometry(
                profile = listOf(o(30f, 148f), o(30f, 50f)), top = 26f,
                base = Base(146f, 176f, 33f, 31f, true),
                pins = listOf(-21f, -7f, 7f, 21f), pinTop = 176f, pinBottom = 196f, pinWidth = 2.6f,
                getter = 26f..41f,
                micas = listOf(58f to 27f, 128f to 27f),
                rods = listOf(Triple(-26f, 54f, 146f), Triple(0f, 54f, 146f), Triple(26f, 54f, 146f)),
                plates = listOf(Plate(r(-22f, 62f, 19f, 62f), ribs = listOf(-12.5f)), Plate(r(3f, 62f, 19f, 62f), ribs = listOf(12.5f))),
                glows = listOf(Glow(o(-12.5f, 58f), 12f), Glow(o(12.5f, 58f), 12f), Glow(o(-12.5f, 128f), 11f), Glow(o(12.5f, 128f), 11f))
            )
            TubeFamily.OCTAL_METAL -> TubeGeometry(
                profile = listOf(o(27f, 150f), o(27f, 60f)), top = 52f, metal = true,
                base = Base(148f, 176f, 30f, 28f, true),
                pins = listOf(-19f, -6.5f, 6.5f, 19f), pinTop = 176f, pinBottom = 196f, pinWidth = 2.6f,
                glows = listOf(Glow(o(0f, 149f), 9f)),
                ring = 126f..132f
            )
            TubeFamily.OCTAL_POWER_LARGE -> TubeGeometry(
                profile = listOf(o(34f, 152f), o(34f, 44f)), top = 14f,
                base = Base(150f, 180f, 37f, 35f, true),
                pins = listOf(-24f, -8f, 8f, 24f), pinTop = 180f, pinBottom = 199f, pinWidth = 2.8f,
                getter = 14f..31f,
                micas = listOf(46f to 31f, 134f to 31f),
                rods = listOf(Triple(-30f, 42f, 150f), Triple(30f, 42f, 150f)),
                plates = listOf(Plate(r(-23f, 52f, 46f, 78f), fins = 6f, ribs = listOf(0f))),
                glows = listOf(Glow(o(0f, 47f), 18f), Glow(o(0f, 135f), 16f))
            )
            TubeFamily.OCTAL_POWER_SMALL -> TubeGeometry(
                profile = listOf(o(27f, 152f), o(27f, 66f)), top = 44f,
                base = Base(150f, 178f, 31f, 29f, true),
                pins = listOf(-20f, -7f, 7f, 20f), pinTop = 178f, pinBottom = 197f, pinWidth = 2.6f,
                getter = 44f..59f,
                micas = listOf(68f to 24f, 136f to 24f),
                rods = listOf(Triple(-24f, 64f, 150f), Triple(24f, 64f, 150f)),
                plates = listOf(Plate(r(-17f, 74f, 34f, 58f), fins = 5f, ribs = listOf(0f))),
                glows = listOf(Glow(o(0f, 69f), 14f), Glow(o(0f, 137f), 12f))
            )
            TubeFamily.SHOULDERED_POWER -> TubeGeometry(
                profile = listOf(o(26f, 152f), o(30f, 140f), o(36f, 116f), o(36f, 104f), o(27f, 76f), o(26f, 60f)), top = 24f,
                base = Base(150f, 180f, 34f, 32f, true),
                pins = listOf(-22f, -7f, 7f, 22f), pinTop = 180f, pinBottom = 199f, pinWidth = 2.8f,
                getter = 24f..40f,
                micas = listOf(66f to 22f, 136f to 30f),
                rods = listOf(Triple(-24f, 62f, 150f), Triple(24f, 62f, 150f)),
                plates = listOf(Plate(r(-20f, 72f, 40f, 60f), fins = 7f, ribs = listOf(0f))),
                glows = listOf(Glow(o(0f, 68f), 16f), Glow(o(0f, 137f), 14f))
            )
            TubeFamily.BEAM_BOTTLE -> TubeGeometry(
                profile = listOf(o(30f, 156f), o(32f, 144f), o(42f, 108f), o(42f, 40f)), top = 8f,
                base = Base(154f, 184f, 36f, 34f, true),
                pins = listOf(-24f, -8f, 8f, 24f), pinTop = 184f, pinBottom = 200f, pinWidth = 2.8f,
                getter = 8f..25f,
                micas = listOf(46f to 39f, 138f to 36f),
                rods = listOf(Triple(-34f, 42f, 154f), Triple(34f, 42f, 154f)),
                plates = listOf(Plate(r(-26f, 54f, 52f, 80f), fins = 7f, ribs = listOf(-9f, 9f))),
                glows = listOf(Glow(o(0f, 49f), 20f), Glow(o(0f, 139f), 16f))
            )
            TubeFamily.DIRECTLY_HEATED -> TubeGeometry(
                profile = listOf(o(22f, 162f), o(25f, 152f), o(42f, 118f), o(43f, 98f), o(34f, 56f), o(23f, 36f)), top = 10f,
                base = Base(160f, 188f, 36f, 34f, false),
                pins = listOf(-13f, 13f), pinTop = 188f, pinBottom = 200f, pinWidth = 4.5f,
                getter = 10f..22f,
                micas = listOf(50f to 24f, 140f to 27f),
                rods = listOf(Triple(-24f, 48f, 160f), Triple(24f, 48f, 160f), Triple(-7f, 50f, 57f), Triple(7f, 50f, 57f)),
                plates = listOf(Plate(r(-19f, 74f, 38f, 62f), mesh = true)),
                filament = listOf(o(-15f, 74f), o(-7f, 57f), o(0f, 74f), o(7f, 57f), o(15f, 74f)),
                glows = listOf(Glow(o(0f, 66f), 34f))
            )
        }
    }
}

/**
 * `TubeIllustration`: the tube, its heater glow (fades in over 0.9 s while
 * [lit], out over 0.6 s), and the audio bloom that follows the loudest routed
 * output while [active].
 */
@Composable
internal fun TubeIllustration(
    family: TubeFamily,
    lit: Boolean,
    peaks: List<Float>,
    outputStart: Int,
    outputCount: Int,
    outputMask: Int,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val g = TubeGeometry.of(family)
    val heater by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 900 else 600), label = "heater")
    val target = if (active && lit && outputMask != 0) TubeAudioPulse.opacity(peaks, outputStart, outputCount, outputMask) else 0f
    val bloom by animateFloatAsState(target, tween(100), label = "bloom")
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) { TubeRenderer.place(this) { TubeRenderer.drawTube(this, g) } }
        Canvas(Modifier.fillMaxSize().alpha(heater)) { TubeRenderer.place(this) { TubeRenderer.drawGlow(this, g, bloom = false) } }
        Canvas(Modifier.fillMaxSize().alpha(bloom)) { TubeRenderer.place(this) { TubeRenderer.drawGlow(this, g, bloom = true) } }
    }
}

private object TubeRenderer {
    val heaterColor = Color(1.0f, 0.52f, 0.16f)
    val coreColor = Color(1.0f, 0.78f, 0.45f)

    private fun white(a: Float) = Color.White.copy(alpha = a)

    private fun grey(v: Float, a: Float = 1f) = Color(v, v, v, a)

    /** Centres the 120 x 200 grid, preserving its aspect. */
    fun place(scope: DrawScope, block: DrawScope.() -> Unit) = with(scope) {
        val s = minOf(size.height / 200f, size.width / 120f)
        translate(size.width / 2f, (size.height - 200f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) { block() }
        }
    }

    /** Catmull-Rom through [pts], from the path's current point. */
    private fun smooth(path: Path, pts: List<Offset>) {
        for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(i - 1, 0)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[minOf(i + 2, pts.size - 1)]
            path.cubicTo(
                p1.x + (p2.x - p0.x) / 6,
                p1.y + (p2.y - p0.y) / 6,
                p2.x - (p3.x - p1.x) / 6,
                p2.y - (p3.y - p1.y) / 6,
                p2.x,
                p2.y
            )
        }
    }

    fun envelope(g: TubeGeometry): Path {
        val left = g.profile.map { Offset(-it.x, it.y) }
        val right = g.profile.reversed()
        val last = g.profile.last()
        val k = 0.5523f
        val rise = last.y - g.top
        return Path().apply {
            moveTo(left[0].x, left[0].y)
            smooth(this, left)
            cubicTo(-last.x, last.y - rise * k, -last.x * k, g.top, 0f, g.top)
            cubicTo(last.x * k, g.top, last.x, last.y - rise * k, last.x, last.y)
            smooth(this, right)
            quadraticTo(0f, g.glassBottom + 5f, left[0].x, left[0].y)
            close()
        }
    }

    private fun halfWidth(g: TubeGeometry, y: Float): Float {
        val pts = g.profile
        for (i in 0 until pts.size - 1) {
            if (y <= pts[i].y && y >= pts[i + 1].y) {
                val t = (pts[i].y - y) / maxOf(pts[i].y - pts[i + 1].y, 0.001f)
                return pts[i].x + (pts[i + 1].x - pts[i].x) * t
            }
        }
        return pts.last().x
    }

    private fun rounded(r: Rect, radius: Float) = Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) }

    fun drawTube(d: DrawScope, g: TubeGeometry) = with(d) {
        val env = envelope(g)
        val w = g.widestHalf
        // Contact shadow on the shelf (unblurred).
        drawOval(Color.Black.copy(alpha = 0.3f), Offset(-w * 0.9f, g.pinBottom - 3f), Size(w * 1.8f, 7f))
        drawPins(this, g)
        drawBase(this, g)
        if (g.metal) {
            drawCan(this, g, env)
            return@with
        }
        drawPath(env, Color.Black.copy(alpha = 0.22f))
        drawPath(
            env,
            Brush.horizontalGradient(
                0f to white(0.16f),
                0.2f to white(0.04f),
                0.65f to white(0.02f),
                0.92f to white(0.10f),
                1f to white(0.14f),
                startX = -w,
                endX = w
            )
        )
        clipPath(env) {
            g.rods.forEach { (x, from, to) -> drawLine(white(0.32f), Offset(x, from), Offset(x, to), 0.8f) }
            g.plates.forEach { drawPlate(this, it) }
            g.micas.forEach { (y, half) -> drawRoundRect(white(0.38f), Offset(-half, y - 0.9f), Size(half * 2, 1.8f), CornerRadius(0.9f)) }
            g.filament?.let { fil ->
                val p = Path().apply {
                    moveTo(fil[0].x, fil[0].y)
                    fil.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(p, white(0.45f), style = Stroke(0.9f, join = StrokeJoin.Round))
            }
            g.getter?.let { getter ->
                val span = getter.endInclusive - getter.start
                drawRect(
                    Brush.verticalGradient(
                        0f to grey(0.88f),
                        0.45f to grey(0.62f, 0.95f),
                        1f to grey(0.40f, 0f),
                        startY = getter.start,
                        endY = getter.endInclusive
                    ),
                    Offset(-60f, getter.start - 2f),
                    Size(120f, span + 2f)
                )
            }
            // Reflection down the left flank.
            val reflTop = g.profile.last().y - 2f
            val reflBottom = g.glassBottom - 10f
            val refl = Path()
            for (i in 0..12) {
                val y = reflTop + (reflBottom - reflTop) * i / 12f
                val x = -halfWidth(g, y) + 5f
                if (i == 0) refl.moveTo(x, y) else refl.lineTo(x, y)
            }
            drawPath(
                refl,
                Brush.verticalGradient(listOf(white(0.20f), white(0.02f)), startY = reflTop, endY = reflBottom),
                style = Stroke(2.5f, cap = StrokeCap.Round)
            )
        }
        drawPath(env, white(0.5f), style = Stroke(1f))
        // Exhaust tip.
        val tip = rounded(Rect(-2.6f, g.top - 6f, 2.6f, g.top + 1.5f), 2.6f)
        drawPath(tip, white(0.12f))
        drawPath(tip, white(0.5f), style = Stroke(0.9f))
    }

    private fun drawPlate(d: DrawScope, plate: TubeGeometry.Plate) = with(d) {
        val r = plate.rect
        if (plate.fins > 0) {
            for (side in listOf(r.left - plate.fins, r.right)) {
                val fin = Rect(side, r.top + 4f, side + plate.fins, r.bottom - 4f)
                drawRect(grey(0.30f), fin.topLeft, fin.size)
                drawRect(white(0.18f), fin.topLeft, fin.size, style = Stroke(0.5f))
            }
        }
        val body = rounded(r, 1.2f)
        drawPath(
            body,
            Brush.horizontalGradient(listOf(grey(0.20f), grey(0.36f), grey(0.24f), grey(0.16f)), startX = r.left, endX = r.right)
        )
        if (plate.mesh) {
            clipPath(body) {
                var x = r.left - r.height
                while (x < r.right) {
                    drawLine(white(0.13f), Offset(x, r.bottom), Offset(x + r.height, r.top), 0.45f)
                    drawLine(white(0.13f), Offset(x, r.top), Offset(x + r.height, r.bottom), 0.45f)
                    x += 3.2f
                }
            }
        }
        plate.ribs.forEach { rib ->
            drawLine(Color.Black.copy(alpha = 0.45f), Offset(rib, r.top + 2f), Offset(rib, r.bottom - 2f), 1f)
            drawLine(white(0.14f), Offset(rib + 0.9f, r.top + 2f), Offset(rib + 0.9f, r.bottom - 2f), 0.6f)
        }
        drawPath(body, white(0.22f), style = Stroke(0.6f))
    }

    private fun drawPins(d: DrawScope, g: TubeGeometry) = with(d) {
        g.pins.forEach { x ->
            val r = Rect(x - g.pinWidth / 2, g.pinTop - 2f, x + g.pinWidth / 2, g.pinBottom)
            drawPath(
                rounded(r, g.pinWidth / 2),
                Brush.horizontalGradient(listOf(grey(0.45f), grey(0.85f), grey(0.50f)), startX = r.left, endX = r.right)
            )
        }
    }

    private fun drawBase(d: DrawScope, g: TubeGeometry) = with(d) {
        val b = g.base ?: return@with
        if (b.key) drawPath(rounded(Rect(-2.8f, b.bottom - 2f, 2.8f, b.bottom + 7f), 1.5f), Color(0.16f, 0.12f, 0.10f))
        val rr = 3.5f
        val p = Path().apply {
            moveTo(-b.topHalf, b.top)
            lineTo(b.topHalf, b.top)
            lineTo(b.bottomHalf, b.bottom - rr)
            quadraticTo(b.bottomHalf, b.bottom, b.bottomHalf - rr, b.bottom)
            lineTo(-b.bottomHalf + rr, b.bottom)
            quadraticTo(-b.bottomHalf, b.bottom, -b.bottomHalf, b.bottom - rr)
            close()
        }
        drawPath(
            p,
            Brush.horizontalGradient(
                listOf(Color(0.09f, 0.07f, 0.06f), Color(0.25f, 0.19f, 0.15f), Color(0.11f, 0.08f, 0.07f)),
                startX = -b.topHalf,
                endX = b.topHalf
            )
        )
        drawPath(p, white(0.14f), style = Stroke(0.7f))
        drawLine(white(0.18f), Offset(-b.topHalf + 1f, b.top + 1.2f), Offset(b.topHalf - 1f, b.top + 1.2f), 0.6f)
    }

    private fun drawCan(d: DrawScope, g: TubeGeometry, env: Path) = with(d) {
        val w = g.widestHalf
        fun steel(from: Float, to: Float) = Brush.horizontalGradient(
            0f to grey(0.30f),
            0.28f to grey(0.66f),
            0.6f to grey(0.48f),
            1f to grey(0.26f),
            startX = from,
            endX = to
        )
        val band = g.ring?.let { rounded(Rect(-w - 3f, it.start, w + 3f, it.endInclusive), 2f) }
        band?.let { drawPath(it, steel(-w - 3f, w + 3f)) }
        drawPath(env, steel(-w, w))
        band?.let {
            drawPath(it, steel(-w - 3f, w + 3f))
            drawPath(it, Color.Black.copy(alpha = 0.35f), style = Stroke(0.6f))
        }
        drawPath(env, white(0.25f), style = Stroke(0.8f))
        val cap = rounded(Rect(-7f, g.top - 3f, 7f, g.top + 1f), 1.5f)
        drawPath(cap, grey(0.55f))
        drawPath(cap, Color.Black.copy(alpha = 0.3f), style = Stroke(0.5f))
    }

    /** The heater glow, or with [bloom] the extra flare laid over it when driven. */
    fun drawGlow(d: DrawScope, g: TubeGeometry, bloom: Boolean) = with(d) {
        val env = envelope(g)
        val color = if (g.filament != null) coreColor else heaterColor
        if (!g.metal && !bloom) drawPath(env, heaterColor.copy(alpha = 0.05f))
        g.glows.forEach { glow ->
            val r = if (bloom) glow.radius * 1.7f else glow.radius
            drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = (if (bloom) 0.35f else 0.55f) * (if (g.metal) 0.45f else 1f)),
                    0.45f to heaterColor.copy(alpha = if (bloom) 0.12f else 0.2f),
                    1f to heaterColor.copy(alpha = 0f),
                    center = glow.at,
                    radius = r
                ),
                r,
                glow.at
            )
            if (!bloom && g.filament == null && !g.metal) {
                val streak = Rect(glow.at.x - 1.1f, glow.at.y - 2.6f, glow.at.x + 1.1f, glow.at.y + 2.6f)
                drawPath(rounded(streak.inflate(1.2f), 2.3f), heaterColor.copy(alpha = 0.5f))
                drawPath(rounded(streak, 1.1f), coreColor)
            }
        }
        g.filament?.let { fil ->
            val p = Path().apply {
                moveTo(fil[0].x, fil[0].y)
                fil.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(p, heaterColor.copy(alpha = 0.5f), style = Stroke(if (bloom) 6f else 4f, join = StrokeJoin.Round))
            drawPath(p, heaterColor, style = Stroke(if (bloom) 3f else 2.2f, join = StrokeJoin.Round))
            if (!bloom) drawPath(p, Color(1f, 0.9f, 0.7f), style = Stroke(1f, join = StrokeJoin.Round))
        }
    }
}

/**
 * TubeModellerView.swift `TubeIcon`: a vacuum tube on a 24-point grid whose
 * filament glows (cross-fading over 0.9 s / 0.6 s) while the effect is on.
 */
@Composable
internal fun TubeIcon(lit: Boolean, color: Color, modifier: Modifier = Modifier) {
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(if (lit) 900 else 600), label = "filament")
    Canvas(modifier) {
        val s = minOf(size.width, size.height) / 24f
        val ox = size.width / 2 - 12 * s
        val oy = size.height / 2 - 12 * s
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        val lw = minOf(size.width, size.height) * 1.9f / 27f
        val env = Path().apply {
            moveTo(p(5.5f, 17f).x, p(5.5f, 17f).y)
            lineTo(p(5.5f, 8.5f).x, p(5.5f, 8.5f).y)
            arcTo(Rect(p(12f, 8.5f), 6.5f * s), 180f, 180f, false)
            lineTo(p(18.5f, 17f).x, p(18.5f, 17f).y)
            moveTo(p(12f, 2f).x, p(12f, 2f).y)
            lineTo(p(12f, 0.8f).x, p(12f, 0.8f).y)
        }
        drawPath(env, color, style = Stroke(lw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawRoundRect(color, p(4.5f, 16.8f), Size(15 * s, 3.6f * s), CornerRadius(1.2f * s))
        listOf(8.5f, 12f, 15.5f).forEach { x -> drawLine(color, p(x, 20.4f), p(x, 23.2f), lw, StrokeCap.Round) }
        drawLine(color, p(10.5f, 16.8f), p(10.5f, 11f), lw * 0.8f, StrokeCap.Round)
        drawLine(color, p(13.5f, 16.8f), p(13.5f, 11f), lw * 0.8f, StrokeCap.Round)
        val fil = Path().apply {
            moveTo(p(10.5f, 11f).x, p(10.5f, 11f).y)
            quadraticTo(p(12f, 6.5f).x, p(12f, 6.5f).y, p(13.5f, 11f).x, p(13.5f, 11f).y)
        }
        drawPath(fil, color.copy(alpha = color.alpha * (1 - glow)), style = Stroke(lw * 0.8f, cap = StrokeCap.Round))
        drawPath(fil, MacColors.orange.copy(alpha = 0.35f * glow), style = Stroke(lw * 2.4f, cap = StrokeCap.Round))
        drawPath(fil, MacColors.orange.copy(alpha = glow), style = Stroke(lw, cap = StrokeCap.Round))
    }
}
