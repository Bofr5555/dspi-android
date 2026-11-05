// Port of DSPi-Console-b5:DSPi Console/FirmwareUpdateView.swift
// (FirmwareMismatchBanner, FirmwareMismatchBannerState) @ b5fdeac; README
// "Firmware Version Warning". Linux qt/qml/main.qml shows the bridge's
// compatMessage the same way.
package se.bofr.dspi.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.WireCompat
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.HorizontalRule

/**
 * The firmware warning across the top of the window: shown while the
 * connected device's firmware is not the one this Console speaks, until
 * Hide (for this launch). "Update..." / "Details..." open the firmware page.
 */
@Composable
fun FirmwareMismatchBanner(bridge: DspiBridge, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    bridge.observeState()
    val connected = bridge.observeStatus { it.connected }
    val hidden by HomeSession.bannerHidden
    val compat = if (connected) bridge.compat else 0
    val displayOnly = connected && WireCompat.isDisplayOnly(bridge.state.value)
    val message = HomeModel.firmwareBannerMessage(compat, bridge.firmwareVersion, HomeModel.EXPECTED_FIRMWARE, displayOnly)
    AnimatedVisibility(visible = message != null && !hidden, modifier = modifier) {
        Column(Modifier.fillMaxWidth().background(c.orange.copy(alpha = 0.12f).compositeOver(c.windowBackground))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(DspiIcons.warning, null, tint = c.orange, modifier = Modifier.size(20.dp))
                Text(message.orEmpty(), color = c.label, modifier = Modifier.weight(1f))
                TextButton(onClick = onUpdate) { Text(HomeModel.firmwareBannerButton(compat)) }
                TextButton(onClick = { HomeSession.bannerHidden.value = true }) { Text("Hide") }
            }
            HorizontalRule(Modifier.fillMaxWidth())
        }
    }
}
