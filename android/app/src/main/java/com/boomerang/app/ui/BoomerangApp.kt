package com.boomerang.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun BoomerangApp(model: ShellViewModel = viewModel()) {
    val selected by model.destination.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val quote by model.quote.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != "main") { model.back() }
    Scaffold(bottomBar = {
        if (screen == "main") NavigationBar {
            Destination.entries.forEach { destination ->
                val icon = when (destination) {
                    Destination.HOME -> Icons.Outlined.Home
                    Destination.LIBRARY -> Icons.Outlined.Inventory2
                    Destination.AI -> Icons.Outlined.AutoAwesome
                    Destination.PROFILE -> Icons.Outlined.PersonOutline
                }
                NavigationBarItem(
                    selected = selected == destination.name,
                    onClick = { model.select(destination) },
                    modifier = Modifier.testTag("nav_${destination.name}"),
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(destination.label) },
                )
            }
        }
    }) { padding ->
        val modifier = Modifier.padding(padding)
        when (screen) {
            "editor" -> RecordEditor(quote, model::editQuote, model::back, modifier)
            "detail" -> DetailShell(model::back, modifier)
            else -> when (Destination.valueOf(selected)) {
                Destination.HOME -> HomeScreen(model::openEditor, modifier)
                Destination.LIBRARY -> LibraryScreen(model::openEditor, model::openDetail, modifier)
                Destination.AI -> AiScreen(modifier)
                Destination.PROFILE -> ProfileScreen(modifier)
            }
        }
    }
}
