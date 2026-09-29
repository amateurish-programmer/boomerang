package com.boomerang.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Decorative only: geometry scales with available space, with no text or semantics. */
@Composable
internal fun InkMountains(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        fun ridge(points: List<Pair<Float, Float>>, alpha: Float) {
            val path = Path().apply {
                moveTo(points.first().first * size.width, size.height)
                points.forEach { (x, y) -> lineTo(x * size.width, y * size.height) }
                lineTo(size.width, size.height); close()
            }
            drawPath(path, ink.copy(alpha = alpha))
        }
        ridge(listOf(.18f to .91f, .31f to .72f, .39f to .76f, .52f to .24f, .61f to .48f, .68f to .32f, .83f to .68f, 1f to .85f), .045f)
        ridge(listOf(.33f to 1f, .45f to .75f, .52f to .79f, .62f to .43f, .7f to .8f, .8f to .55f, .92f to .87f, 1f to .92f), .075f)
        ridge(listOf(.62f to 1f, .72f to .81f, .77f to .83f, .85f to .63f, .91f to .9f, 1f to .97f), .11f)
    }
}

@Composable
internal fun InkActionBar(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(shape = MaterialTheme.shapes.small, onClick = onClick, enabled = enabled,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
        }
    }
}
