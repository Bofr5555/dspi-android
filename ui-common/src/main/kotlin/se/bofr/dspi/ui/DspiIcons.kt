// Port of DSPi-Console-Linux:qt/qml/components/Icon.qml @ 4683a2c: the
// stroke icons the Linux Console draws in place of the macOS SF Symbols
// (24×24 viewBox, stroke 1.7, round caps and joins). SF Symbols cannot ship
// on Android; these are the reference client's own replacements.
package se.bofr.dspi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke icons for the whole app; tint them with `Icon(tint = …)`. */
object DspiIcons {
    private class Shapes {
        val strokes = mutableListOf<String>()
        val fills = mutableListOf<String>()

        fun path(d: String) = strokes.add(d)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = strokes.add("M$x1 ${y1}L$x2 $y2")

        fun polyline(vararg pts: Float) = strokes.add(polylineData(pts))

        fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) = (if (filled) fills else strokes).add(circleData(cx, cy, r))

        fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f, filled: Boolean = false) =
            (if (filled) fills else strokes).add(rectData(x, y, w, h, rx))
    }

    private fun polylineData(pts: FloatArray): String = buildString {
        for (i in pts.indices step 2) append(if (i == 0) "M" else "L").append(pts[i]).append(' ').append(pts[i + 1])
    }

    private fun circleData(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun rectData(x: Float, y: Float, w: Float, h: Float, rx: Float): String = if (rx <= 0f) {
        "M$x ${y}h${w}v${h}h${-w}z"
    } else {
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}" +
            "a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"
    }

    private fun icon(name: String, block: Shapes.() -> Unit): ImageVector {
        val s = Shapes().apply(block)
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in s.strokes) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        for (d in s.fills) b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        return b.build()
    }

    val sliders: ImageVector by lazy {
        icon("sliders") {
            line(5f, 3f, 5f, 21f)
            line(12f, 3f, 12f, 21f)
            line(19f, 3f, 19f, 21f)
            line(2.5f, 15f, 7.5f, 15f)
            line(9.5f, 8f, 14.5f, 8f)
            line(16.5f, 13f, 21.5f, 13f)
        }
    }

    val headphones: ImageVector by lazy {
        icon("headphones") {
            path("M4 15v-3a8 8 0 0 1 16 0v3")
            rect(2.5f, 14f, 4.5f, 7f, 1.5f)
            rect(17f, 14f, 4.5f, 7f, 1.5f)
        }
    }

    val loudness: ImageVector by lazy {
        icon("loudness") {
            path("M3 9.5h3.5L11 5.5v13l-4.5-4H3z")
            path("M15 9.5c.9.7 1.4 1.6 1.4 2.5s-.5 1.8-1.4 2.5")
            path("M18 7c1.7 1.3 2.6 3 2.6 5s-.9 3.7-2.6 5")
        }
    }

    val waveform: ImageVector by lazy {
        icon("waveform") { polyline(2f, 12f, 6f, 12f, 8.5f, 6f, 11f, 18f, 13.5f, 4f, 16f, 20f, 18f, 12f, 22f, 12f) }
    }

    val bassClef: ImageVector by lazy {
        icon("bassclef") {
            path("M5 9.5a5.5 5.5 0 0 1 11 0c0 5.5-5 9.5-11 11.5")
            circle(6.5f, 9.5f, 1.6f, filled = true)
            circle(19.5f, 7.5f, 0.9f, filled = true)
            circle(19.5f, 12.5f, 0.9f, filled = true)
        }
    }

    val info: ImageVector by lazy {
        icon("info") {
            circle(12f, 12f, 9.5f)
            line(12f, 11f, 12f, 17f)
            circle(12f, 7.5f, 0.6f, filled = true)
        }
    }

    val gear: ImageVector by lazy {
        icon("gear") {
            circle(12f, 12f, 3.2f)
            path(
                "M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1" +
                    "a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1" +
                    "a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1" +
                    "a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3" +
                    "a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9" +
                    "a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1" +
                    "a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z"
            )
        }
    }

    val xmark: ImageVector by lazy {
        icon("xmark") {
            line(5f, 5f, 19f, 19f)
            line(19f, 5f, 5f, 19f)
        }
    }

    val link: ImageVector by lazy {
        icon("link") {
            path("M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1.2 1.2")
            path("M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2")
        }
    }

    /** `link.badge.plus`: the link with a small plus (unlinked pair). */
    val linkPlus: ImageVector by lazy {
        icon("link-plus") {
            path("M10 14a4.5 4.5 0 0 0 6.4 0l1.2-1.2")
            path("M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1.2-1.2")
            path("M13 4.6a4.5 4.5 0 0 1 1.5-.6")
            line(19f, 2.5f, 19f, 8.5f)
            line(16f, 5.5f, 22f, 5.5f)
        }
    }

    val speaker: ImageVector by lazy {
        icon("speaker") {
            path("M3 9.5h3.5L11 5.5v13l-4.5-4H3z")
            path("M15 9.5c.9.7 1.4 1.6 1.4 2.5s-.5 1.8-1.4 2.5")
            path("M18 7c1.7 1.3 2.6 3 2.6 5s-.9 3.7-2.6 5")
        }
    }

    val speakerMute: ImageVector by lazy {
        icon("speaker-mute") {
            path("M3 9.5h3.5L11 5.5v13l-4.5-4H3z")
            line(15f, 9f, 21f, 15f)
            line(21f, 9f, 15f, 15f)
        }
    }

    /** The output limiter (`gauge`). */
    val gauge: ImageVector by lazy {
        icon("gauge") {
            path("M3.5 17a9 9 0 1 1 17 0")
            line(12f, 14f, 16.5f, 8.5f)
            circle(12f, 14f, 1.2f, filled = true)
        }
    }

    val warning: ImageVector by lazy {
        icon("warning") {
            path("M12 3.5l9 16H3z")
            line(12f, 10f, 12f, 14f)
            circle(12f, 17f, 0.6f, filled = true)
        }
    }

    val floppy: ImageVector by lazy {
        icon("floppy") {
            path("M4.5 3.5h12l4 4v12a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1z")
            path("M7.5 3.5v4.5h8v-4.5")
            path("M7 20.5v-6a.5.5 0 0 1 .5-.5h9a.5.5 0 0 1 .5.5v6")
        }
    }

    val chevronDown: ImageVector by lazy { icon("chev-down") { polyline(6f, 9f, 12f, 15f, 18f, 9f) } }

    val chevronLeft: ImageVector by lazy { icon("chev-left") { polyline(15f, 5f, 8f, 12f, 15f, 19f) } }

    /** `chevron.up.chevron.down`, the macOS pop-up button marker. */
    val chevronUpDown: ImageVector by lazy {
        icon("chev-updown") {
            polyline(8f, 9.5f, 12f, 5.5f, 16f, 9.5f)
            polyline(8f, 14.5f, 12f, 18.5f, 16f, 14.5f)
        }
    }

    val more: ImageVector by lazy {
        icon("more") {
            circle(5f, 12f, 1.3f, filled = true)
            circle(12f, 12f, 1.3f, filled = true)
            circle(19f, 12f, 1.3f, filled = true)
        }
    }

    val pencil: ImageVector by lazy {
        icon("pencil") {
            path("M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5z")
            line(13.5f, 7f, 16.5f, 10f)
        }
    }

    val copy: ImageVector by lazy {
        icon("copy") {
            rect(8.5f, 8.5f, 11.5f, 11.5f, 2f)
            path("M15.5 8.5V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7.5a2 2 0 0 0 2 2h2.5")
        }
    }

    val paste: ImageVector by lazy {
        icon("paste") {
            rect(5f, 5f, 14f, 16f, 2f)
            rect(9f, 3f, 6f, 4f, 1f)
            line(9f, 12f, 15f, 12f)
            line(9f, 16f, 13f, 16f)
        }
    }

    val identify: ImageVector by lazy {
        icon("identify") {
            circle(12f, 12f, 2.2f)
            path("M7.8 7.8a6 6 0 0 0 0 8.4")
            path("M16.2 7.8a6 6 0 0 1 0 8.4")
            path("M5 5a10 10 0 0 0 0 14")
            path("M19 5a10 10 0 0 1 0 14")
        }
    }

    val chart: ImageVector by lazy {
        icon("chart") {
            polyline(3f, 17f, 8f, 11f, 12f, 14f, 20f, 6f)
            line(3f, 21f, 21f, 21f)
        }
    }

    val spectrum: ImageVector by lazy {
        icon("spectrum") {
            line(5f, 20f, 5f, 13f)
            line(9.67f, 20f, 9.67f, 7f)
            line(14.33f, 20f, 14.33f, 10.5f)
            line(19f, 20f, 19f, 4.5f)
        }
    }

    val wrench: ImageVector by lazy {
        icon("wrench") { path("M14.5 4.5a4.5 4.5 0 0 0-4.3 5.8L4 16.5 7.5 20l6.2-6.2a4.5 4.5 0 0 0 5.8-4.3l-2.6 2.6-2.8-.4-.4-2.8z") }
    }

    val chip: ImageVector by lazy {
        icon("chip") {
            rect(6f, 6f, 12f, 12f, 2f)
            line(9f, 2.5f, 9f, 6f)
            line(15f, 2.5f, 15f, 6f)
            line(9f, 18f, 9f, 21.5f)
            line(15f, 18f, 15f, 21.5f)
            line(2.5f, 9f, 6f, 9f)
            line(2.5f, 15f, 6f, 15f)
            line(18f, 9f, 21.5f, 9f)
            line(18f, 15f, 21.5f, 15f)
        }
    }

    val external: ImageVector by lazy {
        icon("external") {
            path("M14 4h6v6")
            line(20f, 4f, 11f, 13f)
            path("M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
        }
    }

    val check: ImageVector by lazy { icon("check") { polyline(5f, 12.5f, 10f, 17.5f, 19f, 7f) } }
}
