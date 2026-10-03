package com.boomerang.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import com.boomerang.app.assistant.AssistantScreen
import com.boomerang.app.extras.ExtrasScreen
import com.boomerang.app.updates.UpdateScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun BoomerangApp(model: ShellViewModel = viewModel()) {
    val account by model.account.collectAsStateWithLifecycle()
    val owner by model.ownerNamespace.collectAsStateWithLifecycle()
    val selected by model.destination.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val library by model.library.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val errors by model.errors.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val type by model.typeFilter.collectAsStateWithLifecycle()
    val result by model.resultFilter.collectAsStateWithLifecycle()
    val sort by model.sort.collectAsStateWithLifecycle()
    val motionMode by model.motionMode.collectAsStateWithLifecycle()
    val systemScale by model.systemAnimationScale.collectAsStateWithLifecycle()
    val saveFeedback by model.pendingSaveFeedback.collectAsStateWithLifecycle()
    val dateLabel by model.homeDateLabel.collectAsStateWithLifecycle()
    var homeEntranceShown by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = screen != "main") { model.back() }
    Scaffold(bottomBar = {
        if (screen == "main") Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
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
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.background),
                    )
                }
        }
        }
    }) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        when (screen) {
            "updates" -> UpdateScreen(model::back, modifier)
            "extras" -> key(owner) { ExtrasScreen(owner, model::openDetail, modifier, onBack = model::back) }
            "editor" -> RecordEditor(editor, errors, busy, message, model::updateContent, model::updateSources, model::save, model::back, modifier)
            "detail" -> RecordDetailScreen(detail, detail?.record?.let(model::countdown).orEmpty(), model.history(detail), busy, message, model::editCurrent, model::deleteCurrent, model::back, modifier, model::lockCurrent)
            else -> when (Destination.valueOf(selected)) {
                Destination.HOME -> HomeScreen(library, model::countdown, model::openEditor, model::openDetail, model::retry, modifier,
                    onLibrary = { model.select(Destination.LIBRARY) }, motionMode = motionMode, systemAnimationScale = systemScale,
                    pendingSaveFeedback = saveFeedback, onConsumeSaveFeedback = model::consumeSaveFeedback,
                    entranceShown = homeEntranceShown, onEntranceShown = { homeEntranceShown = true }, dateLabel = dateLabel, onHomeVisible = model::refreshHomeDate)
                Destination.LIBRARY -> LibraryScreen(library, model.filtered(library.records, query, type, result, sort), query, type, result, sort,
                    model::setQuery, model::setType, model::setResult, model::setSort, model::countdown, model::openEditor, model::openDetail, model::retry, modifier)
                Destination.AI -> key(owner) { AssistantScreen(owner, model::openAiDraft, model::syncAndOpenDetail, modifier = modifier) }
                Destination.PROFILE -> {
                    AccountScreen(account, owner, busy, model::signIn, model::signUp, model::signOut, model::sync,
                        model::previewAnonymous, model::importAnonymous, model::resolveConflict, model::openDetail,
                        modifier = modifier, onUpdates = model::openUpdates, onExtras = model::openExtras,
                        motionMode = motionMode, systemAnimationScale = systemScale, onMotionMode = model::setMotionMode)
                }
            }
        }
    }
}
