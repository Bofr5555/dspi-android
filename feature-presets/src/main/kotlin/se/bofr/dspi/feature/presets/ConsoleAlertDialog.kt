// The NSAlert of the macOS menu actions as a Compose dialog: title
// (messageText), text (informativeText), accessory checkboxes or text field,
// buttons in NSAlert order (first = default, drawn rightmost). Also the
// ConfigurationProgressSheet. Local to :feature-presets.
// TODO(consolidation): a shared alert host belongs in :ui-common (HOME).
package se.bofr.dspi.feature.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Shows [alert]; [onButton] is called with the pressed button and the accessory values. */
@Composable
fun ConsoleAlertDialog(alert: ConsoleAlert, onButton: (AlertButton, AlertResponse) -> Unit, onDismiss: () -> Unit) {
    var checked by remember(alert) { mutableStateOf(alert.checkboxes.filter { it.checked }.map { it.key }.toSet()) }
    var text by remember(alert) { mutableStateOf(alert.textField?.initialText.orEmpty()) }
    fun press(b: AlertButton) = onButton(b, AlertResponse(checked, text))
    val default = alert.buttons.firstOrNull { it.role == AlertButtonRole.DEFAULT }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(alert.messageText) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (alert.informativeText.isNotEmpty()) Text(alert.informativeText, style = MaterialTheme.typography.bodyMedium)
                alert.textField?.let { f ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(f.maxLength) },
                        placeholder = { Text(f.placeholder) },
                        label = { Text(f.placeholder) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { default?.let(::press) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                for (c in alert.checkboxes) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.key in checked, onCheckedChange = { on ->
                            checked =
                                if (on) checked + c.key else checked - c.key
                        })
                        Column {
                            Text(c.title, style = MaterialTheme.typography.bodyMedium)
                            if (c.detail.isNotEmpty()) {
                                Text(
                                    c.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                // NSAlert order: the first added button is the rightmost.
                for (b in alert.buttons.asReversed()) {
                    TextButton(
                        onClick = { press(b) },
                        colors =
                        if (b.role == AlertButtonRole.DESTRUCTIVE) {
                            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.textButtonColors()
                        }
                    ) { Text(b.title) }
                }
            }
        }
    )
}

/** ConfigurationProgressSheet: a modal, non-dismissable progress indicator. */
@Composable
fun ProgressSheet(text: String) {
    Dialog(onDismissRequest = {}) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(text)
            }
        }
    }
}
