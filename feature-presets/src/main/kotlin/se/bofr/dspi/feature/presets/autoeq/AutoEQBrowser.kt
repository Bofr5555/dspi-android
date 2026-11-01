// Port of DSPi-Console (macOS) `DSPi Console/AutoEQ/AutoEQBrowser.swift` @ v1.1.6-beta5
// (`AutoEQBrowser`, `HeadphoneRow`): search field, result list, bottom bar with
// the selection and Cancel / Apply. Touch: the heart is always shown (no hover).
package se.bofr.dspi.feature.presets.autoeq

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The AutoEQ browser. [onApply] applies the selected profile and returns whether it was applied (then the browser closes). */
@Composable
fun AutoEQBrowser(manager: AutoEQManager, onApply: (HeadphoneEntry) -> Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var searchText by rememberSaveable { mutableStateOf("") }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val entries by manager.entries.collectAsState()
    val favorites by manager.favoriteProfiles.collectAsState()
    val errorMessage by manager.errorMessage.collectAsState()
    val loaded by manager.isLoaded.collectAsState()
    val filteredEntries = remember(entries, searchText) { AutoEQManager.search(entries, searchText) }
    val selectedEntry = filteredEntries.firstOrNull { it.id == selectedEntryId }
    val favoriteIds = remember(favorites) { favorites.map { it.id }.toSet() }

    Column(modifier.fillMaxSize()) {
        Text(
            "AutoEQ - Browse Profiles",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search headphones...") },
            singleLine = true,
            trailingIcon = {
                if (searchText.isNotEmpty()) TextButton(onClick = { searchText = "" }) { Text("✕") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (filteredEntries.isEmpty()) {
                val text =
                    when {
                        errorMessage != null -> "⚠ $errorMessage"
                        searchText.isEmpty() || !loaded -> "Loading headphone database..."
                        else -> "No headphones found matching \"$searchText\""
                    }
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        HeadphoneRow(
                            entry = entry,
                            isSelected = selectedEntryId == entry.id,
                            isFavorite = entry.id in favoriteIds,
                            onToggleFavorite = { manager.toggleFavorite(entry) },
                            onClick = { selectedEntryId = entry.id }
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (selectedEntry != null) {
                    Text(selectedEntry.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${selectedEntry.sourceDisplayName} · ${selectedEntry.formFactor}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Select a headphone to apply its EQ profile", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = { selectedEntry?.let { if (onApply(it)) onDismiss() } }, enabled = selectedEntry != null) { Text("Apply") }
        }
    }
}

/** One result row: form-factor glyph, name, source capsule, form factor, heart. */
@Composable
fun HeadphoneRow(entry: HeadphoneEntry, isSelected: Boolean, isFavorite: Boolean, onToggleFavorite: () -> Unit, onClick: () -> Unit) {
    val selectedBg = MaterialTheme.colorScheme.primary
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val secondary = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) selectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (entry.formFactor == "over-ear") "🎧" else "◖", modifier = Modifier.width(30.dp), color = fg)
        Column(Modifier.weight(1f)) {
            Text(entry.displayName, color = fg)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val c = sourceColor(entry.source)
                Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = if (isSelected) 0.3f else 0.15f)) {
                    Text(
                        entry.sourceDisplayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) fg else c,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(entry.formFactor, style = MaterialTheme.typography.labelSmall, color = secondary)
            }
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onToggleFavorite) {
            Text(if (isFavorite) "♥" else "♡", color = if (isFavorite) Color(0xFFFF3B30) else secondary)
        }
    }
}

/** HeadphoneRow.sourceColor. */
fun sourceColor(source: String): Color = when (source) {
    "oratory1990" -> Color(0xFFFF9500)
    "crinacle" -> Color(0xFFAF52DE)
    "rtings" -> Color(0xFF007AFF)
    "innerfidelity" -> Color(0xFF34C759)
    else -> Color(0xFF8E8E93)
}
