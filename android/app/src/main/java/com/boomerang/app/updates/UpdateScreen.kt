package com.boomerang.app.updates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun UpdateScreen(onBack: () -> Unit, modifier: Modifier = Modifier, model: UpdateViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refreshPermission() }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshPermission() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) {
        model.refreshPermission()
        model.systemActions.collect { intent ->
            try { system.launch(intent) } catch (_: Exception) { model.systemActionFailed() }
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("返回") }
        Text("版本与更新", style = MaterialTheme.typography.headlineMedium)
        Text("当前版本：${state.currentVersion}", modifier = Modifier.testTag("update_current_version"))
        Button(onClick = model::check, enabled = !state.busy, modifier = Modifier.testTag("update_check")) { Text("检查更新") }
        state.message?.let { Text(it, modifier = Modifier.testTag("update_message")) }
        if (state.busy && state.stage != UpdateStage.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.manifest?.let { manifest ->
            Text("版本 ${manifest.versionName}", style = MaterialTheme.typography.titleLarge)
            Text("安装包 ${String.format(Locale.ROOT, "%.1f", manifest.sizeBytes / 1048576.0)} MB")
            Text(manifest.notes)
            when (state.stage) {
                UpdateStage.AVAILABLE, UpdateStage.RETRY -> Button(onClick = model::download, enabled = !state.busy,
                    modifier = Modifier.testTag("update_download")) { Text(if (state.stage == UpdateStage.RETRY) "重新下载" else "下载更新") }
                UpdateStage.DOWNLOADING -> {
                    val fraction = (state.downloaded.toFloat() / manifest.sizeBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${(fraction * 100).toInt()}%")
                    OutlinedButton(onClick = model::cancelDownload, modifier = Modifier.testTag("update_cancel")) { Text("取消下载") }
                }
                UpdateStage.READY -> {
                    if (!state.canInstall) {
                        Text("安装更新需要允许回旋镖安装应用。设置完成后，请返回并点击安装。")
                        OutlinedButton(onClick = model::openPermissionSettings, enabled = !state.busy,
                            modifier = Modifier.testTag("update_permission")) { Text("允许安装应用") }
                    }
                    Button(onClick = model::install, enabled = !state.busy && state.canInstall,
                        modifier = Modifier.testTag("update_install")) { Text("安装更新") }
                }
                UpdateStage.IDLE -> Unit
            }
        }
    }
}
