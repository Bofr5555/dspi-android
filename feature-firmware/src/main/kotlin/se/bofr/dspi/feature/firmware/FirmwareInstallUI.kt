// Port of DSPi-Console (macOS) `DSPi Console/FirmwareInstallUI.swift` @
// v1.1.6-beta5 (b5fdeac): SetupCardBackground, LabeledValueRow,
// InstallStateCard, InstallWritingCard, StepDotStrip, BootselHint, failure
// severity. SF Symbols become tinted text glyphs ([InstallIcon]), so the
// module needs no icon library.
package se.bofr.dspi.feature.firmware

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val Orange = Color(0xFFFF9500)
internal val Green = Color(0xFF34C759)

/** SetupCardBackground: rounded panel every status and summary card sits on. */
@Composable
internal fun Modifier.setupCard(): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return this
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape)
        .border(1.dp, Color.Gray.copy(alpha = 0.2f), shape)
}

/** One "LABEL   value" line with a fixed label column. */
@Composable
fun LabeledValueRow(label: String, value: String, modifier: Modifier = Modifier, secondary: Boolean = false, labelWidth: Dp = 120.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(labelWidth)
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Glyph stand-ins for the SF Symbols the macOS cards use. */
enum class InstallIcon(val glyph: String) {
    SEARCH("⌕"),
    CHECK_CIRCLE("✓"),
    CABLE("⏚"),
    DRIVE("▭"),
    DRIVE_CHECK("✓"),
    CYCLE("↻"),
    SEAL("✔"),
    QUESTION("?"),
    WARNING("⚠"),
    LOCK("⚿")
}

/** One centred state: icon or spinner, short title, one or two sentences. */
@Composable
fun InstallStateCard(
    icon: InstallIcon,
    tint: Color,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    iconSize: Dp = 28.dp,
    accessory: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().setupCard().padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.height(iconSize), contentAlignment = Alignment.Center) {
            if (spinning) {
                CircularProgressIndicator(Modifier.size(iconSize), strokeWidth = 3.dp)
            } else {
                Text(icon.glyph, color = tint, fontSize = (iconSize.value * 0.9f).sp)
            }
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp)
        )
        if (accessory != null) {
            Box(Modifier.padding(top = 4.dp)) { accessory() }
        }
    }
}

/** The write in progress: who, what, how far, and that the ending is normal. */
@Composable
fun InstallWritingCard(fraction: Double, boardName: String?, version: String, modifier: Modifier = Modifier, viaDrive: Boolean = false) {
    Column(modifier.fillMaxWidth().setupCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Writing firmware", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "${(fraction * 100).roundToInt()}%",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(progress = { fraction.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (boardName != null) LabeledValueRow("Board", boardName)
            LabeledValueRow("Firmware", version)
        }
        Text(
            if (viaDrive) {
                "ⓘ Near the end the board restarts itself and its drive disappears. That is normal - do not unplug it."
            } else {
                "ⓘ When the write finishes the board restarts itself and disappears for a moment. That is normal - do not unplug it."
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A row of labelled dots showing where a linear process is. */
@Composable
fun StepDotStrip(labels: List<String>, current: Int, modifier: Modifier = Modifier, dimmed: Boolean = false, dotWidth: Dp = 60.dp) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val last = labels.lastIndex
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp).alpha(if (dimmed) 0.4f else 1f), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { index, label ->
            if (index != 0) {
                Box(
                    Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 7.dp).height(2.dp)
                        .background(if (index <= current) accent.copy(alpha = 0.6f) else muted.copy(alpha = 0.2f))
                )
            }
            Column(
                Modifier.width(dotWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val fill = when {
                    index < current -> accent
                    index == current -> if (current == last) Green else accent
                    else -> muted.copy(alpha = 0.25f)
                }
                Box(Modifier.size(16.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                    if (index < current || (index == last && current == last)) Text("✓", color = Color.White, fontSize = 9.sp)
                }
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                    color = if (index == current) MaterialTheme.colorScheme.onSurface else muted
                )
            }
        }
    }
}

/** What BOOTSEL is and how to use it. */
@Composable
fun BootselHint(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⏻", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Hold the BOOTSEL button on your Pico-compatible device while plugging it into your phone.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A failure card dressed to match its severity. */
@Composable
fun InstallFailureCard(error: FirmwareInstallError, modifier: Modifier = Modifier, accessory: (@Composable () -> Unit)? = null) {
    InstallStateCard(
        icon = if (error.isMundane) InstallIcon.QUESTION else InstallIcon.WARNING,
        tint = Orange,
        title = if (error.isMundane) "Not quite ready" else "The update did not complete",
        message = error.message,
        modifier = modifier,
        accessory = accessory
    )
}
