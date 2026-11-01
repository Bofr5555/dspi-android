// The analysis views' own small pieces (state dots and their colours, the
// plain accent button, the centred notice). ValueField, the segmented picker,
// section labels, captions, switch rows and MacColors are :ui-common's
// (decision E6).
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.bofr.dspi.ui.MacColors

internal fun StateDot.color(): Color = when (this) {
    StateDot.GRAY -> MacColors.gray
    StateDot.YELLOW -> MacColors.yellow
    StateDot.GREEN -> MacColors.green
    StateDot.ORANGE -> MacColors.orange
}

internal fun FillHealth.color(): Color = when (this) {
    FillHealth.GREEN -> MacColors.green
    FillHealth.YELLOW -> MacColors.yellow
    FillHealth.RED -> MacColors.red
}

@Composable
internal fun secondaryText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun Dot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** A tappable text button in the accent colour (Swift `.buttonStyle(.plain)` + accent). */
@Composable
internal fun PlainButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = if (enabled) MaterialTheme.colorScheme.primary else secondaryText().copy(alpha = 0.4f)
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        color = c,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
}

/** A centred notice: title and explanation (the macOS unavailable / hidden notices). */
@Composable
internal fun Notice(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (detail != null) Text(detail, fontSize = 13.sp, color = secondaryText(), textAlign = TextAlign.Center)
    }
}
