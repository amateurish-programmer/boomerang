package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InkPageTitle(title: String, description: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun InkSectionTitle(title: String, description: String? = null) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(title, style = MaterialTheme.typography.titleLarge)
    description?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun InkPrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

@Composable
fun InkSecondaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}
