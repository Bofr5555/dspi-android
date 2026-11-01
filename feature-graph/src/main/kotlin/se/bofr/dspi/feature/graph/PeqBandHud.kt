// Port of DSPi-Console-b5:DSPi Console/PeqBandHUD.swift @ b5fdeac (AppKit):
// PeqShapeGlyph, PeqBandHUD (the chip) and PeqShapeChooser (its shape page and
// the Add Band card), in Compose. Metrics and wording are the macOS ones.
// Touch: a value is adjusted by dragging it vertically, as on macOS, and a tap
// opens it for typing (macOS: double-click) — docs/UPSTREAM-DEVIATIONS.md GRAPH.
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.core.FilterParams

private val PanelBackground = Color(0xF0262628)
private val Quiet = Color.White.copy(alpha = 0.45f)

/** Small drawn glyphs for the shape buttons (macOS `PeqShapeGlyph`, 18×12 pt). */
@Composable
fun PeqShapeGlyph(shape: PeqShape, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp, 12.dp)) {
        val k = size.width / 18f
        val p = Path()

        fun m(x: Float, y: Float) = p.moveTo(x * k, y * k)

        fun l(x: Float, y: Float) = p.lineTo(x * k, y * k)

        fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1 * k, y1 * k, x2 * k, y2 * k, x * k, y * k)
        val mid = 7f
        when (shape) {
            PeqShape.BELL -> {
                m(1f, 9f)
                c(5f, 9f, 6.5f, 2f, 9f, 2f)
                c(11.5f, 2f, 13f, 9f, 17f, 9f)
            }
            PeqShape.LOW_SHELF -> {
                m(1f, 3f)
                l(6f, 3f)
                c(9f, 3f, 9f, 9f, 12f, 9f)
                l(17f, 9f)
            }
            PeqShape.HIGH_SHELF -> {
                m(1f, 9f)
                l(6f, 9f)
                c(9f, 9f, 9f, 3f, 12f, 3f)
                l(17f, 3f)
            }
            PeqShape.LOW_CUT -> {
                m(3f, 11f)
                c(5f, 5f, 7f, 4f, 10f, 4f)
                l(17f, 4f)
            }
            PeqShape.HIGH_CUT -> {
                m(1f, 4f)
                l(8f, 4f)
                c(11f, 4f, 13f, 5f, 15f, 11f)
            }
            PeqShape.NOTCH -> {
                m(1f, 3f)
                l(6.5f, 3f)
                c(8.2f, 3f, 8.6f, 11f, 9f, 11f)
                c(9.4f, 11f, 9.8f, 3f, 11.5f, 3f)
                l(17f, 3f)
            }
            PeqShape.ALL_PASS -> {
                m(1f, mid)
                l(5f, mid)
                c(8f, 0f, 10f, 14f, 13f, mid)
                l(17f, mid)
            }
        }
        drawPath(p, color, style = Stroke(1.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Frosted panel chrome shared by the chip and the shape page (macOS `PeqFrostedPanel`). */
@Composable
private fun PeqFrostedPanel(frame: PeqRect, content: @Composable () -> Unit) {
    Box(
        Modifier
            .offset(frame.x.dp, frame.y.dp)
            .size(frame.width.dp, frame.height.dp)
            .shadow(10.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(PanelBackground)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            // The panel's own background swallows taps, which would otherwise
            // fall through to the graph and create a band behind it.
            .pointerInput(Unit) { detectTapGestures { } }
    ) { content() }
}

private data class HudRow(
    val field: PeqHudField,
    val label: String,
    val number: String,
    val unit: String,
    val adjustable: Boolean,
    val inline: Boolean = false
)

/** The rows a band shows (macOS `PeqBandHUD.show`). */
internal fun hudRows(p: FilterParams): List<Pair<PeqHudField, Triple<String, String, String>>> = hudRowsFull(p).map {
    it.field to
        Triple(it.label, it.number, it.unit)
}

private fun hudRowsFull(p: FilterParams): List<HudRow> {
    val rows = mutableListOf<HudRow>()
    val so = PeqShape.of(p.filterType)
    if (so != null) {
        val f = PeqValueText.frequencyParts(p.freq.toDouble())
        rows += HudRow(PeqHudField.FREQ, "Freq", f.first, f.second, true)
        if (FilterTypes.hasGain(p.filterType)) {
            rows +=
                HudRow(PeqHudField.GAIN, "Gain", PeqValueText.truncated(p.gain.toDouble(), sign = true), "dB", true)
        }
        rows +=
            when {
                // "Width", as the band list's column header calls it, with Q as the unit.
                FilterTypes.hasQ(p.filterType) -> HudRow(PeqHudField.Q, "Width", PeqValueText.truncated(p.q.toDouble()), "Q", true)
                so.first == PeqShape.ALL_PASS -> HudRow(PeqHudField.Q, "Order", "1st", "", false)
                else -> HudRow(PeqHudField.Q, "Slope", "6", "dB/oct", false, inline = true)
            }
    } else {
        // The Linkwitz Transform: shown here, edited in the band list.
        val f0 = PeqValueText.frequencyParts(p.freq.toDouble())
        val fp = PeqValueText.frequencyParts(p.gain.toDouble())
        rows += HudRow(PeqHudField.FREQ, "f0", f0.first, f0.second, false)
        rows += HudRow(PeqHudField.GAIN, "fp", fp.first, fp.second, false)
    }
    return rows
}

/**
 * The compact card beside a band's dot: a header with the shape's glyph in the
 * band colour and its two-letter code (tap for the shape page), bypass on the
 * right, then one row per value. The shape page replaces the values in place,
 * at exactly the same size.
 */
@Composable
fun PeqBandHud(editor: PeqGraphEditor, @Suppress("UNUSED_PARAMETER") tick: Int, onEditField: (PeqHudField) -> Unit) {
    val band = editor.hudBand ?: return
    val p = editor.hudParams ?: return
    if (!editor.hudVisible) return
    val frame = editor.hudFrame
    val color = PeqBandPalette.color(band).toColor(1f)
    val dim = p.bypass
    val muted = Color.White.copy(alpha = 0.4f)
    PeqFrostedPanel(frame) {
        if (editor.showsShapes && PeqShape.of(p.filterType) != null) {
            val chooser = remember { PeqShapeChooserState() }
            var step by remember { mutableIntStateOf(0) }
            remember(band, editor.availableTypes) {
                chooser.configure(editor.availableTypes)
                chooser.own = PeqShape.of(p.filterType)
            }
            PeqShapeChooser(
                chooser = chooser,
                step = step,
                markColor = if (dim) muted else color,
                onStep = { step++ },
                onPick = { s, o -> editor.hudPick(s, o) },
                onBack = { editor.hudShapePageBack() }
            )
            return@PeqFrostedPanel
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp).height(17.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val so = PeqShape.of(p.filterType)
                Row(
                    Modifier
                        .width(60.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(enabled = so != null) { editor.hudShapeButton() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (so != null) {
                        PeqShapeGlyph(so.first, if (dim) muted else color)
                        Text(
                            " ${so.first.code} ▾",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = if (dim) 0.5f else 0.85f),
                            maxLines = 1
                        )
                    } else {
                        Text("LT", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (editor.bypassSupported) {
                    Box(
                        Modifier
                            .size(18.dp, 17.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { editor.hudBypass() },
                        contentAlignment = Alignment.Center
                    ) {
                        PowerGlyph(if (dim) muted else color)
                    }
                }
            }
            Box(
                Modifier.padding(
                    horizontal = 8.dp
                ).padding(top = 3.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.09f))
            )
            Spacer(Modifier.height(3.dp))
            for (row in hudRowsFull(p)) HudValueRow(editor, row, dim, onEditField)
        }
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2 - 1.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        drawArc(
            color,
            -60f,
            300f,
            false,
            topLeft = Offset(c.x - r, c.y - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
        )
        drawLine(color, Offset(c.x, c.y - r - 0.5f), Offset(c.x, c.y), 1.4.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
private fun HudValueRow(editor: PeqGraphEditor, row: HudRow, dim: Boolean, onEditField: (PeqHudField) -> Unit) {
    val density = LocalDensity.current
    val quiet = Color.White.copy(alpha = if (dim) 0.28f else 0.45f)
    val adjust =
        if (row.adjustable) {
            Modifier
                .pointerInput(row.field) {
                    var dragged = 0.0
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragged = 0.0
                            editor.hudAdjust(row.field, 0.0, PeqAdjustPhase.BEGAN)
                        },
                        onDragEnd = { editor.hudAdjust(row.field, dragged, PeqAdjustPhase.ENDED) },
                        onDragCancel = { editor.hudAdjust(row.field, dragged, PeqAdjustPhase.ENDED) }
                    ) { change, dy ->
                        change.consume()
                        // Points moved upward since the press.
                        dragged += -dy / density.density
                        editor.hudAdjust(row.field, dragged, PeqAdjustPhase.CHANGED)
                    }
                }.pointerInput(row.field) { detectTapGestures(onTap = { onEditField(row.field) }) }
        } else {
            Modifier
        }
    Row(Modifier.height(15.dp).fillMaxWidth().padding(horizontal = 8.dp).then(adjust), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, fontSize = 9.5.sp, color = quiet, modifier = Modifier.weight(1f), maxLines = 1)
        if (row.inline) {
            Text("${row.number} ${row.unit}", fontSize = 11.sp, color = Color.White.copy(alpha = if (dim) 0.45f else 0.88f), maxLines = 1)
        } else {
            Text(
                row.number,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = if (dim) 0.45f else 0.88f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
                maxLines = 1
            )
            Spacer(Modifier.width(3.dp))
            Text(row.unit, fontSize = 9.5.sp, color = quiet, modifier = Modifier.width(19.dp), maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * The two-step shape choice (macOS `PeqShapeChooser`): a grid of shapes, then,
 * for a shape with two orders, its two slopes. [step] is bumped by the caller
 * to recompose after the chooser's state moved.
 */
@Composable
fun PeqShapeChooser(
    chooser: PeqShapeChooserState,
    @Suppress("UNUSED_PARAMETER") step: Int,
    markColor: Color,
    onStep: () -> Unit,
    onPick: (PeqShape, Int) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = 4.dp).height(17.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(start = 3.dp)
                    .size(15.dp, 17.dp)
                    .clickable {
                        if (chooser.back()) onBack() else onStep()
                    },
                contentAlignment = Alignment.Center
            ) { Text("‹", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f)) }
            Text(chooser.title, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(horizontal = 8.dp).padding(top = 3.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.09f)))
        val pending = chooser.pendingShape
        if (pending != null) {
            // Step two: the picked shape's two orders. Only the band's own shape shows its order.
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                for (o in 1..2) {
                    val marked = chooser.isOrderMarked(o)
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(42.dp, 20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = if (marked) 0.16f else 0.07f))
                            .clickable { chooser.pickOrder(o)?.let { onPick(it.first, it.second) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(pending.orderLabel(o), fontSize = 10.5.sp, color = if (marked) markColor else Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        } else {
            val own = chooser.own?.first
            val shapes = chooser.shapes
            val perRow = 4
            Column(Modifier.padding(top = 3.dp, start = 4.dp, end = 4.dp)) {
                for (rowShapes in shapes.chunked(perRow)) {
                    Row {
                        for (s in rowShapes) {
                            Box(
                                Modifier
                                    .size(25.dp, 24.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.White.copy(alpha = if (s == own) 0.16f else 0f))
                                    .clickable {
                                        val picked = chooser.pickShape(s)
                                        if (picked != null) onPick(picked.first, picked.second) else onStep()
                                    },
                                contentAlignment = Alignment.Center
                            ) { PeqShapeGlyph(s, if (s == own) markColor else Color.White.copy(alpha = 0.82f)) }
                        }
                    }
                }
            }
        }
    }
}

/** The Add Band card (macOS: Cmd-click; Android: long-press empty graph > Add Band…). */
@Composable
fun PeqAddBandCard(editor: PeqGraphEditor, @Suppress("UNUSED_PARAMETER") tick: Int) {
    if (!editor.cardOpen) return
    val chooser = remember { PeqShapeChooserState() }
    var step by remember { mutableIntStateOf(0) }
    remember(editor.availableTypes, editor.cardOrigin) {
        chooser.configure(editor.availableTypes)
        chooser.own = null
    }
    val o = editor.cardOrigin
    val size = PeqGraphEditor.Tuning.CARD_SIZE
    PeqFrostedPanel(PeqRect(o.x, o.y, size.width, size.height)) {
        PeqShapeChooser(
            chooser = chooser,
            step = step,
            markColor = Color.White,
            onStep = { step++ },
            onPick = { s, ord -> editor.cardPick(s, ord) },
            onBack = { editor.cardCancel() }
        )
    }
}
