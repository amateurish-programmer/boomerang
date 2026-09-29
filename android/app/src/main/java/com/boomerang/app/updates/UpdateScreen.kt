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
import com.boomerang.app.ui.InkPageTitle
import com.boomerang.app.ui.InkSectionTitle
import com.boomerang.app.ui.InkPrimaryAction
import com.boomerang.app.ui.InkSecondaryAction

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
    UpdateContent(state, onBack, model::check, model::download, model::cancelDownload,
        model::openPermissionSettings, model::install, modifier)
}

@Composable
fun UpdateContent(state: UpdateUiState, onBack: () -> Unit, onCheck: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onPermission: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
        InkPageTitle("版本与更新", "安装包将经完整性与签名校验")
        Text("当前版本：${state.currentVersion}", modifier = Modifier.testTag("update_current_version"))
        if (state.stage == UpdateStage.IDLE) InkPrimaryAction("检查更新", Modifier.testTag("update_check"), !state.busy, onCheck)
        else InkSecondaryAction("检查更新", Modifier.testTag("update_check"), !state.busy, onCheck)
        state.message?.let { Text(it, modifier = Modifier.testTag("update_message")) }
        if (state.busy && state.stage != UpdateStage.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.manifest?.let { manifest ->
            InkSectionTitle("发现版本 ${manifest.versionName}", "更新说明")
            Text("安装包 ${String.format(Locale.ROOT, "%.1f", manifest.sizeBytes / 1048576.0)} MB")
            Text(manifest.notes)
            when (state.stage) {
                UpdateStage.AVAILABLE, UpdateStage.RETRY -> InkPrimaryAction(if (state.stage == UpdateStage.RETRY) "重新下载" else "下载更新",
                    Modifier.testTag("update_download"), !state.busy, onDownload)
                UpdateStage.DOWNLOADING -> {
                    val fraction = (state.downloaded.toFloat() / manifest.sizeBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${(fraction * 100).toInt()}%")
                    InkSecondaryAction("取消下载", modifier = Modifier.testTag("update_cancel"), onClick = onCancel)
                }
                UpdateStage.READY -> {
                    if (!state.canInstall) {
                        Text("安装更新需要允许回旋镖安装应用。设置完成后，请返回并点击安装。")
                        InkSecondaryAction("允许安装应用", Modifier.testTag("update_permission"), !state.busy, onPermission)
                    }
                    InkPrimaryAction("安装更新", Modifier.testTag("update_install"), !state.busy && state.canInstall, onInstall)
                }
                UpdateStage.IDLE -> Unit
            }
        }
    }
}
