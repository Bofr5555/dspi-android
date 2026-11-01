// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Filter List:
// FilterListTab, FilterListHeaderButton, BypassAllControls,
// FilterListTabToggle, FilterListView) @ b5fdeac. Linux
// qt/qml/FilterListView.qml for the bridge calls (setAllBandsBypass,
// clearAllBands).
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.bofr.dspi.ui.AlertButton
import se.bofr.dspi.ui.BandFields
import se.bofr.dspi.ui.DspiAlertDialog
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.FilterType
import se.bofr.dspi.ui.HorizontalRule
import se.bofr.dspi.ui.PillButton
import se.bofr.dspi.ui.SegmentTab
import se.bofr.dspi.ui.SegmentedControl
import se.bofr.dspi.ui.SplitActionPill
import se.bofr.dspi.ui.dspiCard

/**
 * A channel's band table (macOS `FilterListView`): column header, one
 * [FilterRowView] per band, and a footer with Enable All | Bypass All, Clear
 * All and the optional PEQ | XO tabs.
 *
 * @param onSetAllBypass applies Enable All / Bypass All in one bridge call
 *   (`setAllBandsBypass`); Off bands are never touched.
 * @param onClear null hides Clear All (input pages clear from the header).
 */
@Composable
fun FilterListView(
    bands: List<BandFields>,
    availableTypes: List<FilterType>,
    onUpdate: (Int, BandFields) -> Unit,
    onBypassToggle: (Int, Boolean) -> Unit,
    onSetAllBypass: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    bypassSupported: Boolean = true,
    tabs: List<SegmentTab> = emptyList(),
    isCrossoverMode: Boolean = false,
    onClear: (() -> Unit)? = null
) {
    val c = DspiTheme.colors
    var confirmBypassXo by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 560.dp
        Column(Modifier.fillMaxWidth().dspiCard()) {
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().background(c.controlBackground).padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (bypassSupported) Spacer(Modifier.width(FilterColumns.bypass))
                    Header("#", FilterColumns.index)
                    if (isCrossoverMode) {
                        Header("FAMILY", FilterColumns.xoFamily)
                        Header("TYPE", FilterColumns.xoDir)
                        Header("SLOPE", FilterColumns.xoSlope)
                        Header("FREQ", FilterColumns.freq + FilterColumns.unit)
                    } else {
                        Header("TYPE", FilterColumns.type)
                        Header("FREQ", FilterColumns.freq + FilterColumns.unit)
                        Header("GAIN", FilterColumns.gain + FilterColumns.unit)
                        Header("WIDTH", FilterColumns.q + FilterColumns.unit)
                    }
                }
                HorizontalRule(Modifier.fillMaxWidth())
            }
            bands.forEachIndexed { index, band ->
                FilterRowView(
                    index = index,
                    params = band,
                    availableTypes = availableTypes,
                    bypassSupported = bypassSupported,
                    isCrossoverMode = isCrossoverMode,
                    wide = wide,
                    onChange = { onUpdate(index, it) },
                    onBypassToggle = { onBypassToggle(index, it) }
                )
            }
            if (onClear != null || tabs.isNotEmpty() || bypassSupported) {
                HorizontalRule(Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (bypassSupported) {
                        // Off bands have no audio effect: excluded from both the test and the action.
                        val actionable = bands.filter { it.type != FilterType.FLAT }
                        SplitActionPill(
                            leftTitle = "Enable All",
                            leftDisabled = actionable.all { !it.bypass },
                            onLeft = { onSetAllBypass(false) },
                            rightTitle = "Bypass All",
                            rightDisabled = actionable.all { it.bypass },
                            onRight = {
                                // Bypassing a crossover sends full-range audio to the drivers.
                                if (isCrossoverMode) confirmBypassXo = true else onSetAllBypass(true)
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onClear != null) PillButton("Clear All", onClick = { confirmClear = true })
                    if (tabs.isNotEmpty()) SegmentedControl(tabs)
                }
            }
        }
    }

    if (confirmBypassXo) {
        val dismiss = { confirmBypassXo = false }
        DspiAlertDialog(
            title = "Bypass this output's crossovers?",
            message =
            "This sends full-range audio to this output with no crossover protection, which can damage unprotected drivers " +
                "such as tweeters. Continue only if you are sure.",
            buttons =
            listOf(
                AlertButton("Bypass All", AlertButton.Role.Destructive) {
                    confirmBypassXo = false
                    onSetAllBypass(true)
                },
                AlertButton("Cancel", AlertButton.Role.Cancel, dismiss)
            ),
            onDismiss = dismiss
        )
    }
    if (confirmClear && onClear != null) {
        val dismiss = { confirmClear = false }
        DspiAlertDialog(
            title = "Clear All Bands?",
            message = "Every band in this list will be reset to its default (flat) state. This cannot be undone.",
            buttons =
            listOf(
                AlertButton("Clear All") {
                    confirmClear = false
                    onClear()
                },
                AlertButton("Cancel", AlertButton.Role.Cancel, dismiss)
            ),
            onDismiss = dismiss
        )
    }
}

@Composable
private fun Header(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, style = DspiType.captionBold, color = DspiTheme.colors.secondaryLabel, modifier = Modifier.width(width))
}
