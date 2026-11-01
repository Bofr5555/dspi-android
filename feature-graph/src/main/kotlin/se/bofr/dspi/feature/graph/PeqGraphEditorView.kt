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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
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

/** The editing overlay over the response graph: lobes, the edited curve, dots, chip, card, readouts, menus. */
@Composable
fun PeqGraphEditorView(
    bridge: DspiBridge,
    config: PeqEditorConfig,
    selection: PeqGraphSelection,
    background: Color,
    onZoom: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val editor =
        remember(bridge, selection) {
            PeqGraphEditor(BridgePeqEditorHost(bridge, selection, scope), CoroutinePeqScheduler(scope))
        }
    DisposableEffect(editor) { onDispose { editor.dispose() } }
    val renderer = remember { PeqGraphRenderer() }
    var tick by remember { mutableIntStateOf(0) }
    var frameTick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<Pair<List<PeqMenuItem>, PeqPoint>?>(null) }
    var editField by remember { mutableStateOf<PeqHudField?>(null) }
    val haptics = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    val gestures = remember(editor) { PeqTouchGestures(editor) }
    gestures.config =
        PeqTouchConfig(
            touchSlop = viewConfig.touchSlop / density.toDouble(),
            doubleTapTimeoutMs = viewConfig.doubleTapTimeoutMillis,
            doubleTapSlop = 32.0,
            longPressTimeoutMs = viewConfig.longPressTimeoutMillis
        )
    gestures.onMenu = { items, at -> menu = items to at }
    gestures.onZoom = onZoom

    SideEffect {
        editor.onChanged = { tick++ }
        // The system beep: nothing to create, or text that didn't parse.
        editor.onBeep = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        editor.onBeginHudEditRequested = { editField = it }
        editor.onEndHudEditRequested = { editField = null }
    }
    LaunchedEffect(editor, config) { editor.apply(config) }
    // Hover and selection animations run the frame loop just for their few hundred milliseconds.
    LaunchedEffect(tick) {
        while (editor.animating) {
            androidx.compose.runtime.withFrameNanos { }
            editor.frame(System.nanoTime() / 1e9)
            frameTick++
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value.toDouble()
        val h = maxHeight.value.toDouble()
        LaunchedEffect(w, h) { editor.resize(w, h) }

        Canvas(
            Modifier
                .fillMaxSize()
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
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: continue
                            val p = PeqPoint(change.position.x / density.toDouble(), change.position.y / density.toDouble())
                            val mods = modsOf(ev.keyboardModifiers)
                            if (change.type == PointerType.Mouse) {
                                run {
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
                                                        change.uptimeMillis - lastClickMs <= viewConfig.doubleTapTimeoutMillis &&
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
                                            val raw = -change.scrollDelta.y * 8.0
                                            if (editor.wheel(p, raw, mods)) {
                                                change.consume()
                                            } else if (p.x <= 40) {
                                                // The dB zoom strip at the left edge (macOS zoomZone).
                                                onZoom(1 + raw / 8 * 3 / 50)
                                                change.consume()
                                            }
                                        }
                                    }
                                    continue
                                }
                            }
                            // Touch (and stylus contact).
                            val pressed = ev.changes.count { it.pressed }
                            when (ev.type) {
                                PointerEventType.Press -> {
                                    if (pressed >= 2) {
                                        ev.centreAndSpan(density)?.let { (c, s) -> gestures.pinchStart(c, s) }
                                        ev.changes.forEach { it.consume() }
                                    } else if (editor.editing) {
                                        val id = gestures.down(p, change.uptimeMillis, mods)
                                        launchLongPress(scope, gestures, id, viewConfig.longPressTimeoutMillis)
                                        change.consume()
                                    }
                                }
                                PointerEventType.Move -> {
                                    if (pressed >= 2) {
                                        ev.centreAndSpan(density)?.let { (c, s) -> gestures.pinch(c, s) }
                                        ev.changes.forEach { it.consume() }
                                    } else if (gestures.active) {
                                        gestures.move(p, mods)
                                        change.consume()
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (pressed == 1) gestures.pinchEnd()
                                    if (pressed == 0) {
                                        if (gestures.active) change.consume()
                                        gestures.up(p, change.uptimeMillis)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_EXPRESSION")
            tick + frameTick
            if (!editor.editing) return@Canvas
            scale(density, density, pivot = Offset.Zero) { renderer.draw(this, editor.picture, background) }
        }

        // Readouts: frequency at the bottom, level at the left edge.
        @Suppress("UNUSED_EXPRESSION")
        tick + frameTick
        if (editor.editing) {
            editor.axisLabel?.let { text ->
                val x = min(max(editor.axisLabelX - 40, 2.0), max(w - 82, 2.0))
                ReadoutLabel(text, Modifier.offset(x.dp, (h - 18).dp))
            }
            editor.gainLabel?.let { text ->
                ReadoutLabel(text, Modifier.offset(2.dp, (editor.gainLabelY - 8).coerceIn(0.0, max(h - 16, 0.0)).dp))
            }
            PeqBandHud(editor, tick + frameTick) { editField = it }
            PeqAddBandCard(editor, tick + frameTick)
        }

        menu?.let { (items, at) ->
            Box(Modifier.offset(at.x.dp, at.y.dp)) {
                DropdownMenu(expanded = true, onDismissRequest = { menu = null }) {
                    PeqMenuItems(items) { menu = null }
                }
            }
        }
    }

    editField?.let { field ->
        PeqHudTextDialog(editor, field) { editField = null }
    }
}

private fun launchLongPress(scope: CoroutineScope, gestures: PeqTouchGestures, id: Int, timeoutMs: Long) {
    scope.launch {
        delay(timeoutMs)
        gestures.longPress(id)
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

/** Typing a chip value (macOS: the chip's field in place; here a dialog, the phone's keyboard needs the room). */
@Composable
private fun PeqHudTextDialog(editor: PeqGraphEditor, field: PeqHudField, close: () -> Unit) {
    val p = editor.hudParams
    val initial =
        remember(field) {
            when (field) {
                PeqHudField.FREQ -> p?.let {
                    PeqValueText.frequencyParts(it.freq.toDouble()).let { (n, u) ->
                        if (u ==
                            "kHz"
                        ) {
                            "${n}k"
                        } else {
                            n
                        }
                    }
                }
                    ?: ""
                PeqHudField.GAIN -> p?.let { PeqValueText.truncated(it.gain.toDouble(), sign = true) } ?: ""
                PeqHudField.Q -> p?.let { PeqValueText.truncated(it.q.toDouble()) } ?: ""
            }
        }
    var text by remember(field) { mutableStateOf(initial) }
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
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = {
                    text = it
                    failed = false
                }, singleLine = true, isError = failed)
                if (failed) Text("Couldn't read that value.", color = Color(0xFFFF6B6B), fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (editor.hudText(field, text)) close() else failed = true
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } }
    )
}
