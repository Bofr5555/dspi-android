// Port of DSPi-Console-b5:DSPi Console/Components.swift (MARK: Sidebar Volume
// Mode, Volume Mode Selector, MuteableLabel) @ b5fdeac.
package se.bofr.dspi.feature.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType

/** The sidebar volume slider's mode: `auto` drives the user volume, `master` the master volume. */
enum class SidebarVolumeMode(val raw: String, val heading: String) {
    AUTO("auto", "User Volume"),
    MASTER("master", "Master Volume");

    companion object {
        fun from(raw: String?): SidebarVolumeMode = entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

/**
 * The volume section heading that doubles as the mode picker
 * ("User Volume ▾" / "Master Volume ▾"), macOS `VolumeModeSelector`.
 */
@Composable
fun VolumeModeSelector(mode: SidebarVolumeMode, onModeChange: (SidebarVolumeMode) -> Unit, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mode.heading, style = DspiType.caption2, color = c.secondaryLabel)
            Text(" ▾", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.secondaryLabel)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SidebarVolumeMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.heading) },
                    leadingIcon = {
                        if (m == mode) Icon(DspiIcons.check, null, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp))
                    },
                    onClick = {
                        open = false
                        onModeChange(m)
                    }
                )
            }
        }
    }
}

/** A tiny label that toggles mute (macOS `MuteableLabel`). */
@Composable
fun MuteableLabel(text: String, isMuted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clickable(onClickLabel = if (isMuted) "Unmute" else "Mute", onClick = onToggle),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(text, style = DspiType.mono9, color = DspiTheme.colors.secondaryLabel, modifier = Modifier.alpha(if (isMuted) 0.3f else 1f))
    }
}
