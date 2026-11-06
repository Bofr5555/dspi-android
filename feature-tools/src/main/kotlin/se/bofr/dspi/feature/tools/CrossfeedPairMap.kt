// K340 FORK FEATURE (not in DSPi-Console; docs/UPSTREAM-DEVIATIONS.md
// "CROSSFEED PAIR MAP UI"): choose which outputs each crossfeed pair
// processes, on firmware with the crossfeed pair map (core patch 0002,
// HardwareCaps.crossfeedPairMapSupported). Upstream firmware keeps the fixed
// pairs (outputs 2p / 2p+1) and the macOS view unchanged.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.bridge.CrossfeedPairs
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.HelpTip
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.ToolChip
import se.bofr.dspi.ui.writeEnabled

/** The "Pairs" picker entries (labels 1-based, as the chips). */
enum class CrossfeedPairChoice(val label: String) {
    DEFAULT("Default (1/2, 3/4, …)"),
    ONE_DAC_PER_EAR("One DAC per ear (K340) — woofers 3↔1, tweeters 4↔2"),
    CUSTOM("Custom…")
}

/**
 * What the OUTPUT PAIRS section shows, computed from OBSERVED state only
 * (the collected `DspState`, the capabilities and writesAllowed read in the
 * same composition), so nothing in it can go stale (reviews/stale-state-audit.md).
 */
data class CrossfeedPairsModel(
    /** The "Pairs" row (and its custom editor) exists: the firmware has the pair map. */
    val pairMapSupported: Boolean,
    /** Stereo pairs (chips, editor rows). */
    val pairCount: Int,
    /** The pair map the device reports ([CrossfeedPairs] encoding). */
    val map: List<Int>,
    /** The preset the map matches, or [CrossfeedPairChoice.CUSTOM]. */
    val choice: CrossfeedPairChoice,
    /** Chip text per pair: "1".."n" on upstream firmware (macOS), "Pair 1: Out 3 / Out 1" with a pair map. */
    val chipLabels: List<String>,
    /** Chip long-press help per pair: the channel names of its outputs (macOS chip `.help`). */
    val chipHelp: List<String>,
    /** Outputs a pair can use: every output but the PDM sub. */
    val selectableOutputs: List<Int>,
    /** Display name per selectable output ("3 — Output 3"). */
    val outputLabels: List<String>,
    /** Writes possible: connected and not read-only (G1). */
    val enabled: Boolean
) {
    /** The current [choice]'s picker title ("Custom" for a map matching no preset). */
    val choiceTitle: String get() = if (choice == CrossfeedPairChoice.CUSTOM) "Custom" else choice.label

    companion object {
        /** Both K340 pairs (woofers, tweeters): the mask offered after the K340 preset. */
        const val K340_PAIR_MASK = 0x03

        fun choiceOf(map: List<Int>): CrossfeedPairChoice = when {
            CrossfeedPairs.samePairing(map, CrossfeedPairs.DEFAULT) -> CrossfeedPairChoice.DEFAULT
            CrossfeedPairs.samePairing(map, CrossfeedPairs.ONE_DAC_PER_EAR) -> CrossfeedPairChoice.ONE_DAC_PER_EAR
            else -> CrossfeedPairChoice.CUSTOM
        }

        fun chipLabel(map: List<Int>, p: Int): String {
            val (l, r) = CrossfeedPairs.pair(map, p)
            return "Pair ${p + 1}: Out ${l + 1} / Out ${r + 1}"
        }

        /**
         * [st] is the collected state; [supported] / [writesAllowed] are read
         * in the same composition ([DspiBridge.hardwareCaps], LocalWritesAllowed).
         * [outputName] is the channel name of output o (0-based).
         */
        fun from(
            st: DspState,
            supported: Boolean,
            connected: Boolean,
            writesAllowed: Boolean,
            outputName: (Int) -> String
        ): CrossfeedPairsModel {
            val pairCount = ToolMasks.pairCount(st.numOutputChannels)
            // Upstream firmware reports all zeros (the default pairing); never trust a fork map it cannot have.
            val map = if (supported) st.crossfeedPairMap else CrossfeedPairs.DEFAULT
            val outputs = (0 until 2 * pairCount).toList()
            return CrossfeedPairsModel(
                pairMapSupported = supported,
                pairCount = pairCount,
                map = map,
                choice = choiceOf(map),
                chipLabels = (0 until pairCount).map { if (supported) chipLabel(map, it) else "${it + 1}" },
                chipHelp = (0 until pairCount).map { p ->
                    val (l, r) = CrossfeedPairs.pair(map, p)
                    "${outputName(l)} / ${outputName(r)}"
                },
                selectableOutputs = outputs,
                outputLabels = outputs.map { "${it + 1} — ${outputName(it)}" },
                enabled = connected && writesAllowed
            )
        }
    }
}

/**
 * The pair-map writes. Every write is ONE [DspiBridge.setCrossfeedPairMap]
 * with a complete map (never pair-by-pair, pairs must stay disjoint) or an
 * absolute pair mask; nothing reads a value composed earlier, so a tap can
 * not write stale state. All are no-ops while read-only (G1); the bridge also
 * refuses them.
 */
class CrossfeedPairActions(private val bridge: DspiBridge) {
    private val allowed: Boolean get() = bridge.writesAllowed.value && bridge.hardwareCaps.crossfeedPairMapSupported

    /** A preset picked in the "Pairs" menu. [CrossfeedPairChoice.CUSTOM] opens the editor instead (no write). */
    fun choose(choice: CrossfeedPairChoice) {
        if (!allowed) return
        when (choice) {
            CrossfeedPairChoice.DEFAULT -> bridge.setCrossfeedPairMap(CrossfeedPairs.DEFAULT)
            CrossfeedPairChoice.ONE_DAC_PER_EAR -> bridge.setCrossfeedPairMap(CrossfeedPairs.ONE_DAC_PER_EAR)
            CrossfeedPairChoice.CUSTOM -> Unit
        }
    }

    /**
     * Whether the custom editor's [pairs] form a map the device accepts. Each pair is checked
     * itself first: a pair on one output, (0, 0), encodes as 0x00, which the map reads as
     * "default pair" and would pass the map check.
     */
    fun isValid(pairs: List<Pair<Int, Int>>): Boolean =
        pairs.all { it.first != it.second } && bridge.crossfeedPairMapValid(mapOf(pairs))

    /** Apply the custom editor's [pairs] (one per pair, pair 0 first): false (nothing sent) when invalid. */
    fun applyCustom(pairs: List<Pair<Int, Int>>): Boolean {
        if (!allowed) return false
        if (!isValid(pairs)) return false
        bridge.setCrossfeedPairMap(mapOf(pairs))
        return true
    }

    /** The explicit "Enable Pairs 1 and 2" tap after the K340 preset: an absolute mask, not a toggle. */
    fun enableK340Pairs() {
        if (!bridge.writesAllowed.value) return
        bridge.setCrossfeedOutputPairMask(CrossfeedPairsModel.K340_PAIR_MASK)
    }

    companion object {
        /** A full map from explicit pairs; a pair on its default outputs is encoded 0, as the firmware reports it. */
        fun mapOf(pairs: List<Pair<Int, Int>>): List<Int> = List(CrossfeedPairs.MAP_BYTES) { p ->
            val pr = pairs.getOrNull(p)
            if (pr == null || pr == (2 * p) to (2 * p + 1)) 0 else CrossfeedPairs.encode(pr.first, pr.second)
        }
    }
}

/** The "Pairs" row: picker, the K340 mask offer, the custom editor. Only shown with [CrossfeedPairsModel.pairMapSupported]. */
@Composable
internal fun CrossfeedPairsRow(model: CrossfeedPairsModel, crossfeedMask: Int, actions: CrossfeedPairActions) {
    var open by remember { mutableStateOf(false) }
    var offerMask by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val enabled = writeEnabled(model.enabled)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Pairs", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp).weight(1f))
            Box {
                TextButton(onClick = { open = true }, enabled = enabled) {
                    Text(model.choiceTitle + " ▾", maxLines = 2)
                }
                DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
                    CrossfeedPairChoice.entries.forEach { c ->
                        DropdownMenuItem(text = { Text(c.label) }, onClick = {
                            open = false
                            if (c == CrossfeedPairChoice.CUSTOM) {
                                editing = true
                            } else {
                                actions.choose(c)
                                offerMask = c == CrossfeedPairChoice.ONE_DAC_PER_EAR
                            }
                        })
                    }
                }
            }
        }
        Caption("Which outputs each crossfeed pair processes (K340 firmware). Each output belongs to one pair at most.")
        // The offer follows the observed map and mask: gone once both pairs are on or the map changes.
        val bothOn = (crossfeedMask and CrossfeedPairsModel.K340_PAIR_MASK) == CrossfeedPairsModel.K340_PAIR_MASK
        if (offerMask && model.choice == CrossfeedPairChoice.ONE_DAC_PER_EAR && !bothOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption("Crossfeed the woofers and the tweeters?", Modifier.weight(1f))
                TextButton(onClick = { offerMask = false }) { Text("Not Now") }
                TextButton(onClick = {
                    actions.enableK340Pairs()
                    offerMask = false
                }, enabled = enabled) { Text("Enable Pairs 1 and 2") }
            }
        }
    }
    // Disconnect / read-only closes the editor (its draft is dropped with it).
    LaunchedEffect(model.enabled) { if (!model.enabled) editing = false }
    if (editing && model.enabled) {
        CrossfeedPairEditor(model, actions, onDismiss = { editing = false })
    }
}

/** The OUTPUT PAIRS chips with a pair map: one full-width chip per pair, labelled with its outputs. */
@Composable
internal fun CrossfeedPairChips(model: CrossfeedPairsModel, mask: Int, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in 0 until model.pairCount) {
            HelpTip(model.chipHelp[p]) {
                ToolChip(model.chipLabels[p], ToolMasks.isOn(mask, p), model.enabled, Modifier.fillMaxWidth()) { onToggle(p) }
            }
        }
    }
}

/**
 * "Custom…": two output pickers per pair, validated live by the core
 * ([DspiBridge.crossfeedPairMapValid]); Apply sends the whole map once. The
 * draft starts from the observed map when the editor opens.
 */
@Composable
private fun CrossfeedPairEditor(model: CrossfeedPairsModel, actions: CrossfeedPairActions, onDismiss: () -> Unit) {
    val draft = remember(model.pairCount) { mutableStateListOf(*Array(model.pairCount) { CrossfeedPairs.pair(model.map, it) }) }
    val valid = actions.isValid(draft.toList())
    val enabled = writeEnabled(model.enabled)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crossfeed Pairs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in 0 until model.pairCount) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pair ${p + 1}", Modifier.width(56.dp), fontWeight = FontWeight.Medium)
                        OutputPicker(model, draft[p].first, enabled) { draft[p] = it to draft[p].second }
                        Text("↔")
                        OutputPicker(model, draft[p].second, enabled) { draft[p] = draft[p].first to it }
                    }
                }
                if (!valid) Caption("Each pair needs two different outputs, and no output can be in two pairs.", color = MacColors.orange)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (actions.applyCustom(draft.toList())) onDismiss() }, enabled = enabled && valid) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OutputPicker(model: CrossfeedPairsModel, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("Out ${selected + 1} ▾") }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            model.selectableOutputs.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(model.outputLabels[i]) }, onClick = {
                    open = false
                    onPick(o)
                })
            }
        }
    }
}
