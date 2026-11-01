// @Previews of the shared building blocks (no device needed).
package se.bofr.dspi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(name = "Building blocks", widthDp = 380, showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun BuildingBlocksPreview() {
    DspiTheme(darkTheme = true) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolHeader("Loudness Compensation", "ISO 226:2003 Fletcher-Munson", isOn = true, switchEnabled = true, onToggle = {})
            SectionLabel("PARAMETERS")
            ParameterRow("Reference SPL", "dB", 83f, 40f..100f, set = {}, caption = "Listening level the curve is flat at.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueField("Hz", 1000f, 80.dp) {}
                PillButton("Clear All", {})
                SegmentedControl(listOf(SegmentTab("PEQ", true) {}, SegmentTab("XO", false) {}))
            }
            ChannelChips(3, { it != 1 }, listOf("Out 1", "Out 2", "Out 3"), enabled = true, onToggle = {})
            HorizontalMeterBar(0.6f, ChannelPalette.input(0), isClipping = true)
            CpuSection(42, 95)
        }
    }
}
