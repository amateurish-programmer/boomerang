package com.boomerang.app.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotificationRepository(application)
    private val owner = MutableStateFlow<String?>(null)
    private val _items = MutableStateFlow<List<LocalNotification>>(emptyList())
    val items = _items.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    init {
        viewModelScope.launch {
            combine(owner, NotificationRepository.updates) { account, _ -> account }.collectLatest { account ->
                _items.value = emptyList(); _error.value = null
                if (account != null) try {
                    _items.value = withContext(Dispatchers.IO) { repository.list(account) }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { _error.value = "提醒暂时无法读取，请稍后重试" }
            }
        }
    }
    fun selectOwner(value: String) { owner.value = value }
    override fun onCleared() { repository.close() }
}

@Composable
fun NotificationCenter(ownerNamespace: String, onOpenRecord: (String) -> Unit, modifier: Modifier = Modifier,
    model: NotificationViewModel = viewModel()) {
    val context = LocalContext.current
    val items by model.items.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(ownerNamespace) { model.selectOwner(ownerNamespace) }
    Column(modifier.fillMaxWidth()) {
        Text("通知中心", style = MaterialTheme.typography.titleLarge)
        Text("后台提醒可能延迟，打开应用会补查。这里保留最近 200 条本机提醒。")
        if (!allowed) {
            Text("系统通知未开启，提醒仍会保存在这里。")
            if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("开启系统通知") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val visible = items.filter { it.owner == ownerNamespace }
        if (visible.isEmpty() && error == null) Text("暂无提醒", modifier = Modifier.padding(vertical = 8.dp))
        visible.forEach { item ->
            TextButton(onClick = { onOpenRecord(item.recordId) }, modifier = Modifier.fillMaxWidth()) {
                Column { Text(item.title); Text(item.message); Text("${item.createdAt.take(10)} · 记录版本 ${item.revision}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
