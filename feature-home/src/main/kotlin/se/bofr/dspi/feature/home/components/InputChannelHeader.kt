// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Input Pair
// Link Alert: InputLinkChoice, InputLinkAlerts; MARK: Input Channel Header:
// InputChannelHeader) @ b5fdeac. Linux qt/qml/components/InputHeaderCard.qml
// for the bridge calls (setInputLinked with keepCh, setInputPreamp sendOnly).
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.feature.home.HomeModel
import se.bofr.dspi.feature.home.channelMap
import se.bofr.dspi.feature.home.observeState
import se.bofr.dspi.feature.home.observeStatus
import se.bofr.dspi.ui.AlertButton
import se.bofr.dspi.ui.CustomSlider
import se.bofr.dspi.ui.DspiAlertDialog
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.HorizontalRule
import se.bofr.dspi.ui.PillButton
import se.bofr.dspi.ui.ValueField
import se.bofr.dspi.ui.VerticalRule
import se.bofr.dspi.ui.dspiCard

/** How a pair differs before linking (`inputPairMismatch`). */
data class InputPairMismatch(val bands: Boolean, val preamp: Boolean) {
    val any: Boolean get() = bands || preamp

    companion object {
        /** Compare app channels [ch] and [partner] in [s]. */
        fun of(s: DspState, ch: Int, partner: Int): InputPairMismatch {
            val map = s.channelMap
            val a = map.wire(ch)
            val b = map.wire(partner)
            if (a < 0 || b < 0) return InputPairMismatch(bands = false, preamp = false)
            val preamp = s.inputPreampDb.getOrNull(map.inputOf(ch)) != s.inputPreampDb.getOrNull(map.inputOf(partner))
            val fa = s.filters.getOrElse(a) { emptyList() }
            val fb = s.filters.getOrElse(b) { emptyList() }
            return InputPairMismatch(bands = fa != fb, preamp = preamp)
        }
    }
}

/**
 * The input page header (macOS `InputChannelHeader`): Link n/m (when the
 * partner is live), the preamp slider + value, and Clear PEQ (both channels
 * of a linked pair). Wide screens keep the macOS single row; phones put the
 * two buttons above the preamp.
 */
@Composable
fun InputChannelHeader(bridge: DspiBridge, ch: Int, onClearPEQ: () -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val activeInputs = bridge.observeStatus { it.activeInputChannels }
    val map = s.channelMap
    val input = map.inputOf(ch)
    val partner = bridge.pairPartner(ch)
    // Both follow the state and the polled active-input count read above.
    val pairAvailable = activeInputs >= 0 && bridge.pairAvailable(ch)
    val linked = pairAvailable && bridge.isInputLinked(ch)
    val pairLabel = HomeModel.pairLabel(maxOf(input, 0))
    val preamp = rememberLiveDrag(s.inputPreampDb.getOrElse(input) { 0f })
    var mismatch by remember { mutableStateOf<InputPairMismatch?>(null) }

    fun toggleLink() {
        if (!pairAvailable) return
        if (linked) {
            bridge.setInputLinked(ch, false, -1)
            return
        }
        // Linking only mirrors future edits: a pair that already differs is reconciled first.
        val m = InputPairMismatch.of(s, ch, partner)
        if (m.any) mismatch = m else bridge.setInputLinked(ch, true, -1)
    }

    val linkButton: @Composable () -> Unit = {
        PillButton(
            title = "Link $pairLabel",
            icon = if (linked) DspiIcons.link else DspiIcons.linkPlus,
            selected = linked,
            cornerRadius = 8.dp,
            onClick = ::toggleLink
        )
    }
    val clearButton: @Composable () -> Unit = {
        PillButton(title = if (linked) "Clear $pairLabel PEQ" else "Clear PEQ", cornerRadius = 8.dp, onClick = onClearPEQ)
    }
    val preampRow: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Preamp", style = DspiType.captionMedium, color = c.secondaryLabel)
            CustomSlider(
                value = preamp.value,
                onValueChange = { preamp.change(it) { v -> bridge.setInputPreamp(input, v, sendOnly = true) } },
                valueRange = -60f..10f,
                defaultValue = 0f,
                onEditingChanged = { e -> preamp.editing(e) { v -> bridge.setInputPreamp(input, v) } },
                onReset = { preamp.set(0f) { v -> bridge.setInputPreamp(input, v) } },
                modifier = Modifier.weight(1f).widthIn(max = 320.dp)
            )
            ValueField(label = "dB", value = preamp.value, width = 52.dp) { v -> preamp.set(v) { bridge.setInputPreamp(input, it) } }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.height(IntrinsicSize.Min).dspiCard(), verticalAlignment = Alignment.CenterVertically) {
                if (pairAvailable) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { linkButton() }
                    VerticalRule()
                }
                preampRow(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp))
                VerticalRule()
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { clearButton() }
            }
        } else {
            Column(Modifier.dspiCard()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pairAvailable) linkButton()
                    Spacer(Modifier.weight(1f))
                    clearButton()
                }
                HorizontalRule(Modifier.fillMaxWidth())
                preampRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }

    mismatch?.let { m ->
        val pageLabel = "IN${input + 1}"
        val partnerInput = map.inputOf(partner)
        val partnerLabel = "IN${partnerInput + 1}"
        val dismiss = { mismatch = null }
        DspiAlertDialog(
            title = HomeModel.linkMismatchTitle(input, partnerInput),
            message = HomeModel.linkMismatchMessage(m.bands, m.preamp),
            buttons =
            listOf(
                AlertButton("Keep $pageLabel") {
                    mismatch = null
                    bridge.setInputLinked(ch, true, ch)
                },
                AlertButton("Keep $partnerLabel") {
                    mismatch = null
                    bridge.setInputLinked(ch, true, partner)
                },
                AlertButton("Cancel", AlertButton.Role.Cancel, dismiss)
            ),
            onDismiss = dismiss
        )
    }
}
