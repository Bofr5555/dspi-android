// SF Symbols the views draw themselves (no icon library in the build):
// "power", "speaker.wave.2.fill" / "speaker.slash.fill" (moved from
// :feature-matrix MatrixPoint.kt, decision E6).
package se.bofr.dspi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** SF Symbol "power". */
@Composable
fun PowerGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.11f
        val inset = stroke / 2 + w * 0.08f
        drawArc(
            color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(w - 2 * inset, w - 2 * inset),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawLine(color, Offset(w / 2, w * 0.06f), Offset(w / 2, w * 0.48f), stroke, StrokeCap.Round)
    }
}

/** SF Symbols "speaker.wave.2.fill" / "speaker.slash.fill". */
@Composable
fun SpeakerGlyph(muted: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val body =
            Path().apply {
                moveTo(w * 0.08f, w * 0.36f)
                lineTo(w * 0.26f, w * 0.36f)
                lineTo(w * 0.50f, w * 0.14f)
                lineTo(w * 0.50f, w * 0.86f)
                lineTo(w * 0.26f, w * 0.64f)
                lineTo(w * 0.08f, w * 0.64f)
                close()
            }
        drawPath(body, color, style = Fill)
        val stroke = w * 0.08f
        if (muted) {
            drawLine(color, Offset(w * 0.06f, w * 0.06f), Offset(w * 0.94f, w * 0.94f), stroke, StrokeCap.Round)
        } else {
            for (r in listOf(0.18f, 0.32f)) {
                drawArc(
                    color,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * 0.5f - w * r, w * 0.5f - w * r),
                    size = Size(2 * w * r, 2 * w * r),
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
        }
    }
}
