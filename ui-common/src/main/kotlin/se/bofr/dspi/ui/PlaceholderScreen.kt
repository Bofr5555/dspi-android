package se.bofr.dspi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Wave 0 stand-in for a screen that a Wave B package will implement. */
@Composable
fun PlaceholderScreen(title: String, owner: String, ports: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Package $owner — ports $ports", style = MaterialTheme.typography.bodySmall)
        content()
    }
}
