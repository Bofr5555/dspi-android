// Port of the view half of DSPi-Console-b5:DSPi Console/PeqGraphEditor.swift
// @ b5fdeac (PeqGraphEditorView / PeqGraphEditorOverlay: event plumbing,
// chip/card/labels/menus, the dB zoom strip) and of
// DSPi-Console-Windows:DSPiConsole/Controls/GraphEditing/PeqGraphEditorView.cs
// + ViewModels/MainViewModel.GraphEditing.cs (the host over the app model).
// The interaction logic lives in PeqGraphEditor; touch is mapped by
// PeqTouchGestures; mouse and hardware keys go straight to the editor.
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.Throttle
import se.bofr.dspi.core.FilterParams

/** The editor's clock on the main thread (Windows: DispatcherQueue timers). */
class CoroutinePeqScheduler(private val scope: CoroutineScope) : PeqScheduler {
    override val now: Double get() = System.nanoTime() / 1e9

    override fun schedule(seconds: Double, action: () -> Unit): PeqTimer {
        val job =
            scope.launch {
                delay((seconds * 1000).toLong())
                action()
            }
        return PeqTimer { job.cancel() }
    }
}

/**
 * The editor's host over the bridge (macOS `DSPViewModel: PeqGraphEditorHost`,
 * Windows `MainViewModel.GraphEditing`). Linked input pairs are mirrored by the
 * bridge itself (`sendBandLive`/`commitBands` write both halves).
 *
 * Live writes go out `sendOnly` ([DspiBridge.sendBandLive]) through a
 * [Throttle] (first at once, then the latest every 30 ms); the release
 * cancels it and commits once ([DspiBridge.commitBands]).
 */
class BridgePeqEditorHost(private val bridge: DspiBridge, override val selection: PeqGraphSelection, scope: CoroutineScope) :
    PeqGraphEditorHost {
    private val pending = LinkedHashMap<Pair<Int, Int>, FilterParams>()
    private val throttle =
        Throttle<Unit>(scope, 30) {
            val batch = pending.toList()
            pending.clear()
            for ((key, p) in batch) bridge.sendBandLive(key.first, key.second, p)
        }

    override fun commitGraphBands(channel: Int, changes: List<Pair<Int, FilterParams>>) {
        throttle.cancel()
        pending.clear()
        if (changes.isEmpty()) return
        bridge.commitBands(channel, changes.map { it.first }, changes.map { it.second })
    }

    override fun sendGraphBandsToDevice(channel: Int, changes: List<Pair<Int, FilterParams>>) {
        for ((band, p) in changes) pending[channel to band] = p
        throttle.submit(Unit)
    }

    override fun setGraphBandBypass(channel: Int, bands: List<Int>, bypass: Boolean) {
        for (b in bands) bridge.setBandBypass(channel, b, bypass)
    }

    override fun showLive(channel: Int, band: Int, p: FilterParams) = selection.showLive(band, p)

    override fun endLive(channel: Int) = selection.endLive()
}

private fun modsOf(k: PointerKeyboardModifiers): Int {
    var m = PeqMods.NONE
    if (k.isCtrlPressed || k.isMetaPressed) m = m or PeqMods.CTRL
    if (k.isAltPressed) m = m or PeqMods.ALT
    if (k.isShiftPressed) m = m or PeqMods.SHIFT
    return m
}

private fun PointerEvent.centreAndSpan(density: Float): Pair<PeqPoint, Double>? {
    val down = changes.filter { it.pressed }
    if (down.size < 2) return null
    val a = down[0].position
    val b = down[1].position
    val c = PeqPoint((a.x + b.x) / 2.0 / density, (a.y + b.y) / 2.0 / density)
    val dx = (a.x - b.x) / density
    val dy = (a.y - b.y) / density
    return c to sqrt((dx * dx + dy * dy).toDouble())
}

/** What the chip shows; published only when it changes, so a hover elsewhere never recomposes it. */
data class PeqHudState(
    val band: Int,
    val frame: PeqRect,
    val params: FilterParams,
    val showsShapes: Boolean,
    val bypassSupported: Boolean,
    val availableTypes: Set<Int>
)

/** The hover readouts (frequency at the bottom, level at the left edge). */
data class PeqLabelState(val axis: String?, val axisX: Double, val gain: String?, val gainY: Double)

internal fun PeqGraphEditor.hudState(): PeqHudState? {
    val b = hudBand ?: return null
    val p = hudParams ?: return null
    if (!hudVisible || !editing) return null
    return PeqHudState(b, hudFrame, p, showsShapes, bypassSupported, availableTypes)
}

internal fun PeqGraphEditor.labelState(): PeqLabelState? =
    if (!editing || (axisLabel == null && gainLabel == null)) null else PeqLabelState(axisLabel, axisLabelX, gainLabel, gainLabelY)

/** The adjustable field after (or before) [field] for a band, wrapping (macOS `nextField`). */
internal fun nextHudField(field: PeqHudField, p: FilterParams, backwards: Boolean): PeqHudField? {
    val order = hudRows(p).filter { it.adjustable }.map { it.field }
    if (order.isEmpty()) return null
    val i = order.indexOf(field)
    if (i < 0) return order.first()
    return order[((i + (if (backwards) -1 else 1)) % order.size + order.size) % order.size]
}

/** The text a field opens with: the bare number, as macOS `beginEditing` seeds it. */
internal fun hudEditSeed(field: PeqHudField, p: FilterParams): String = when (field) {
    PeqHudField.FREQ -> PeqValueText.shortFrequency(p.freq.toDouble())
    PeqHudField.GAIN -> String.format(Locale.ROOT, "%.2f", p.gain)
    PeqHudField.Q -> String.format(Locale.ROOT, "%.3f", p.q)
}

/** The editing overlay over the response graph: lobes, the edited curve, dots, chip, card, readouts, menus. */
@Composable
fun PeqGraphEditorView(
    bridge: DspiBridge,
    config: PeqEditorConfig,
    selection: PeqGraphSelection,
    background: Color,
    onZoom: (Double) -> Unit,
    onZoomRange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val editor =
        remember(bridge, selection) {
            PeqGraphEditor(BridgePeqEditorHost(bridge, selection, scope), CoroutinePeqScheduler(scope))
        }
    DisposableEffect(editor) { onDispose { editor.dispose() } }
    val renderer = remember { PeqGraphRenderer() }
    // Read only in the Canvas draw lambda: a pointer move or an animation frame redraws, never recomposes.
    val drawTick = remember { mutableIntStateOf(0) }
    // Published only when they change (structural equality).
    val hud = remember { mutableStateOf<PeqHudState?>(null) }
    val labels = remember { mutableStateOf<PeqLabelState?>(null) }
    val card = remember { mutableStateOf<PeqPoint?>(null) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    var menu by remember { mutableStateOf<Pair<List<PeqMenuItem>, PeqPoint>?>(null) }
    var editField by remember { mutableStateOf<PeqHudField?>(null) }
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    val gestures = remember(editor) { PeqTouchGestures(editor) }
    val currentZoom by rememberUpdatedState(onZoom)
    val currentZoomRange by rememberUpdatedState(onZoomRange)
    val currentDensity by rememberUpdatedState(density)
    val currentViewConfig by rememberUpdatedState(viewConfig)

    fun publish() {
        drawTick.intValue++
        hud.value = editor.hudState()
        labels.value = editor.labelState()
        card.value = if (editor.editing && editor.cardOpen) editor.cardOrigin else null
    }

    SideEffect {
        gestures.config =
            PeqTouchConfig(
                touchSlop = viewConfig.touchSlop / density.toDouble(),
                doubleTapTimeoutMs = viewConfig.doubleTapTimeoutMillis,
                doubleTapSlop = 32.0,
                longPressTimeoutMs = viewConfig.longPressTimeoutMillis
            )
        gestures.onMenu = { items, at -> menu = items to at }
        gestures.onZoom = { currentZoom(it) }
        editor.onChanged = {
            publish()
            wake.trySend(Unit)
        }
        // The system beep: nothing to create, or text that didn't parse.
        editor.onBeep = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        editor.onBeginHudEditRequested = { f ->
            // macOS `beginEditing`: a field that cannot be typed passes to the next one.
            val p = editor.hudParams
            editField = if (p == null || hudRows(p).any { it.field == f && it.adjustable }) f else nextHudField(f, p, backwards = false)
        }
        editor.onEndHudEditRequested = { editField = null }
    }
    LaunchedEffect(editor, config) { editor.apply(config) }
    // One long-lived loop: hover and selection animations run frames just for
    // their few hundred milliseconds, then it sleeps until the editor changes.
    LaunchedEffect(editor) {
        for (signal in wake) {
            while (editor.animating) {
                withFrameNanos { }
                editor.frame(System.nanoTime() / 1e9)
                publish()
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        LaunchedEffect(w, h) { editor.resize(w, h) }

        // The edited curve's glow, blurred as the other curves' (macOS 0.3 / lw·4 /
        // blur 6 and 0.6 / lw·2 / blur 3), on API 31+; below it the renderer
        // draws the unblurred fallback.
        if (blurAvailable) {
            for (layer in 0..1) {
                val radius = if (layer == 0) 6f else 3f
                val alpha = if (layer == 0) 0.3f else 0.6f
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        renderEffect = BlurEffect(radius.dp.toPx(), radius.dp.toPx(), TileMode.Decal)
                    }
                ) {
                    drawTick.intValue
                    if (!editor.editing) return@Canvas
                    val pic = editor.picture
                    if (!pic.glow || pic.geometry.width <= 4) return@Canvas
                    scale(density, density, pivot = Offset.Zero) {
                        drawPath(
                            renderer.combinedPath(pic),
                            pic.curveColor.toColor(1f),
                            alpha = alpha,
                            style = renderer.glowStroke(pic.lineWidth, narrow = layer == 1)
                        )
                    }
                }
            }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                // Its own layer: an editor redraw never re-records the grid and curves under it.
                .graphicsLayer()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e ->
                    var m = PeqMods.NONE
                    if (e.isCtrlPressed || e.isMetaPressed) m = m or PeqMods.CTRL
                    if (e.isAltPressed) m = m or PeqMods.ALT
                    if (e.isShiftPressed) m = m or PeqMods.SHIFT
                    editor.modifiersChanged(m)
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val key =
                        when (e.key) {
                            Key.Delete, Key.Backspace -> PeqKey.DELETE
                            Key.Escape -> PeqKey.ESCAPE
                            Key.Tab -> PeqKey.TAB
                            Key.DirectionLeft -> PeqKey.LEFT
                            Key.DirectionRight -> PeqKey.RIGHT
                            Key.DirectionUp -> PeqKey.UP
                            Key.DirectionDown -> PeqKey.DOWN
                            Key.A -> PeqKey.A
                            else -> PeqKey.OTHER
                        }
                    editor.keyDown(key, m)
                }.pointerInput(editor, gestures) {
                    var lastClickAt: PeqPoint? = null
                    var lastClickMs = 0L
                    var longPress: Job? = null
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val change = ev.changes.firstOrNull() ?: continue
                                val dens = currentDensity.toDouble()
                                val p = PeqPoint(change.position.x / dens, change.position.y / dens)
                                val mods = modsOf(ev.keyboardModifiers)
                                if (change.type == PointerType.Mouse) {
                                    // A mouse gets the macOS targets (10 / 6 points).
                                    editor.hitRadius = PeqGraphEditor.Tuning.NODE_HIT_RADIUS
                                    editor.curveHitDistance = PeqGraphEditor.Tuning.CURVE_HIT_DISTANCE
                                    when (ev.type) {
                                        PointerEventType.Enter, PointerEventType.Move -> {
                                            if (ev.buttons.isPrimaryPressed) editor.pointerDragged(p, mods) else editor.pointerMoved(p)
                                            editor.modifiersChanged(mods)
                                        }
                                        PointerEventType.Exit -> editor.pointerExited()
                                        PointerEventType.Press -> {
                                            if (editor.editing) focus.requestFocus()
                                            if (ev.buttons.isSecondaryPressed) {
                                                editor.rightPressed(p)?.let { menu = it to p }
                                            } else {
                                                val last = lastClickAt
                                                val count =
                                                    if (last != null &&
                                                        change.uptimeMillis - lastClickMs <= currentViewConfig.doubleTapTimeoutMillis &&
                                                        last.distanceTo(p) < 4
                                                    ) {
                                                        2
                                                    } else {
                                                        1
                                                    }
                                                lastClickAt = if (count == 2) null else p
                                                lastClickMs = change.uptimeMillis
                                                editor.pointerPressed(p, mods, count)
                                            }
                                            if (editor.editing) change.consume()
                                        }
                                        PointerEventType.Release -> {
                                            editor.pointerReleased(p)
                                            if (editor.editing) change.consume()
                                        }
                                        PointerEventType.Scroll -> {
                                            // A mouse notch is about 8 points (macOS non-precise deltas × 8).
                                            // Android reports no scroll phase, so a wheel gesture never "begins"
                                            // (macOS: mouse wheels have none either).
                                            val raw = -change.scrollDelta.y * 8.0
                                            if (editor.wheel(p, raw, mods)) {
                                                change.consume()
                                            } else if (p.x <= ZOOM_ZONE) {
                                                // The dB zoom strip at the left edge: range − Δ·3 dB (macOS `zoom(with:)`).
                                                currentZoomRange(change.scrollDelta.y * 3.0)
                                                change.consume()
                                            }
                                        }
                                    }
                                    continue
                                }
                                // Touch (and stylus contact).
                                val pressed = ev.changes.count { it.pressed }
                                when (ev.type) {
                                    PointerEventType.Press -> {
                                        if (pressed >= 2) {
                                            longPress?.cancel()
                                            ev.centreAndSpan(currentDensity)?.let { (c, s) -> gestures.pinchStart(c, s) }
                                            ev.changes.forEach { it.consume() }
                                        } else if (editor.editing) {
                                            longPress?.cancel()
                                            val id = gestures.down(p, change.uptimeMillis, mods)
                                            val timeout = currentViewConfig.longPressTimeoutMillis
                                            longPress =
                                                scope.launch {
                                                    delay(timeout)
                                                    gestures.longPress(id)
                                                }
                                            change.consume()
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        if (pressed >= 2) {
                                            ev.centreAndSpan(currentDensity)?.let { (c, s) -> gestures.pinch(c, s) }
                                            ev.changes.forEach { it.consume() }
                                        } else if (gestures.active) {
                                            gestures.move(p, mods)
                                            change.consume()
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        if (pressed == 1) gestures.pinchEnd()
                                        if (pressed == 0) {
                                            longPress?.cancel()
                                            longPress = null
                                            if (gestures.active) change.consume()
                                            gestures.up(p, change.uptimeMillis)
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    } finally {
                        // The pointer was taken away (the system or a parent cancelled the gesture).
                        longPress?.cancel()
                        gestures.cancel()
                    }
                }
        ) {
            drawTick.intValue
            if (!editor.editing) return@Canvas
            val pic = editor.picture
            scale(density, density, pivot = Offset.Zero) { renderer.draw(this, pic, background, glow = pic.glow && !blurAvailable) }
        }

        PeqReadouts(labels, w, h)
        hud.value?.let { state -> PeqBandHud(editor, state) { editField = it } }
        card.value?.let { origin -> PeqAddBandCard(editor, origin) }

        menu?.let { (items, at) ->
            Box(Modifier.offset(at.x.dp, at.y.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                    PeqMenuItems(items) { menu = null }
                }
            }
        }
    }

    editField?.let { field ->
        PeqHudTextDialog(editor, field, onField = { editField = it }) { editField = null }
    }
}

/** macOS `PeqGraphEditor.Tuning.zoomZone`: the dB labels strip at the left edge. */
private const val ZOOM_ZONE = 40.0

@Composable
private fun PeqReadouts(labels: State<PeqLabelState?>, w: Double, h: Double) {
    val l = labels.value ?: return
    l.axis?.let { text ->
        val x = min(max(l.axisX - 40, 2.0), max(w - 82, 2.0))
        ReadoutLabel(text, Modifier.offset(x.dp, (h - 18).dp))
    }
    l.gain?.let { text ->
        ReadoutLabel(text, Modifier.offset(2.dp, (l.gainY - 8).coerceIn(0.0, max(h - 16, 0.0)).dp))
    }
}

@Composable
private fun ReadoutLabel(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 10.sp,
        color = Color.White.copy(alpha = 0.85f),
        modifier =
        modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** The band and graph menus (macOS NSMenu; Windows MenuFlyout), submenus inlined. */
@Composable
private fun PeqMenuItems(items: List<PeqMenuItem>, close: () -> Unit) {
    for (item in items) {
        when {
            item.isSeparator -> HorizontalDivider()
            item.isHeader -> DropdownMenuItem(text = { Text(item.title, color = Color.Gray) }, onClick = {}, enabled = false)
            item.submenu != null -> {
                DropdownMenuItem(text = { Text(item.title, color = Color.Gray) }, onClick = {}, enabled = false)
                for (sub in item.submenu) {
                    DropdownMenuItem(
                        text = { Text((if (sub.checked) "✓ " else "    ") + sub.title) },
                        enabled = sub.enabled,
                        onClick = {
                            close()
                            sub.invoke?.invoke()
                        }
                    )
                }
            }
            else ->
                DropdownMenuItem(
                    text = { Text((if (item.checked) "✓ " else "") + item.title) },
                    enabled = item.enabled,
                    onClick = {
                        close()
                        item.invoke?.invoke()
                    }
                )
        }
    }
}

/**
 * Typing a chip value (macOS: the chip's field in place; here a dialog, the
 * phone's keyboard needs the room). Return / IME Done applies, Tab and
 * Shift-Tab apply and move to the next or previous field, Escape cancels;
 * unreadable text keeps the field open and beeps.
 */
@Composable
private fun PeqHudTextDialog(editor: PeqGraphEditor, field: PeqHudField, onField: (PeqHudField) -> Unit, close: () -> Unit) {
    val p = editor.hudParams
    var text by remember(field) { mutableStateOf(p?.let { hudEditSeed(field, it) } ?: "") }
    var failed by remember(field) { mutableStateOf(false) }
    DisposableEffect(field) {
        editor.setHudEditing(true)
        onDispose { editor.setHudEditing(false) }
    }
    val title =
        when (field) {
            PeqHudField.FREQ -> "Freq"
            PeqHudField.GAIN -> "Gain"
            PeqHudField.Q -> "Width"
        }

    fun apply(): Boolean {
        val ok = editor.hudText(field, text)
        failed = !ok
        return ok
    }

    fun applyAndMove(backwards: Boolean) {
        if (!apply()) return
        val now = editor.hudParams
        val next = now?.let { nextHudField(field, it, backwards) }
        if (next == null || next == field) close() else onField(next)
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        failed = false
                    },
                    singleLine = true,
                    isError = failed,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (apply()) close() }),
                    modifier =
                    Modifier.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Tab) {
                            applyAndMove(backwards = e.isShiftPressed)
                            true
                        } else {
                            false
                        }
                    }
                )
                if (failed) Text("Couldn't read that value.", color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { if (apply()) close() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
    )
}
