package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun InkActionBar(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) =
    InkDecoratedActionBar(label, tag, enabled, onClick = onClick)

@Composable
internal fun InkDecoratedActionBar(label: String, tag: String, enabled: Boolean = true,
    feedback: @Composable BoxScope.() -> Unit = {}, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Box {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(shape = MaterialTheme.shapes.small, onClick = onClick, enabled = enabled,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
            }
            feedback()
        }
    }
}
