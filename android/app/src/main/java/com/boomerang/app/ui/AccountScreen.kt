package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boomerang.app.reminders.NotificationCenter
import org.json.JSONObject

@Composable
fun AccountScreen(account: AccountUiState, owner: String, busy: Boolean, onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit, onSignOut: () -> Unit, onSync: () -> Unit,
    onPreview: () -> Unit, onImport: (Set<String>) -> Unit, onResolve: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit, modifier: Modifier = Modifier, onUpdates: () -> Unit = {}, onExtras: () -> Unit = {}) = Page(modifier) {
    InkPageTitle("我的空间", "账号、提醒与本机资料")
    var email by remember { mutableStateOf("") }
    // Credentials deliberately never enter SavedStateHandle or rememberSaveable.
    var password by remember { mutableStateOf("") }
    var selected by remember(account.importPreview) { mutableStateOf(emptySet<String>()) }
    account.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (account.userId == null && account.ready) {
        InkSectionTitle("本机空间")
        Text("登录后会进入独立账号空间。本机记录需要你明确选择导入，不会自动上传。")
        OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true,
            enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("account_email"))
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("account_password"))
        InkPrimaryAction("登录", Modifier.testTag("account_login"), enabled = !busy && email.isNotBlank() && password.isNotEmpty()) { onSignIn(email, password); password = "" }
        InkSecondaryAction("注册", enabled = !busy && email.isNotBlank() && password.isNotEmpty()) { onSignUp(email, password); password = "" }
    } else if (account.userId != null) {
        InkSectionTitle("已登录")
        Text("账号 ${account.userId.take(8)}…")
        InkPrimaryAction("立即同步", enabled = !busy, onClick = onSync)
        InkSecondaryAction("预览本机记录并导入", enabled = !busy, onClick = onPreview)
        if (account.importPreview.isNotEmpty()) {
            Text("选择要导入当前账号的记录")
            account.importPreview.forEach { detail ->
                Row {
                    Checkbox(detail.record.id in selected, { checked -> selected = if (checked) selected + detail.record.id else selected - detail.record.id }, enabled = !busy)
                    Column { Text(detail.record.content.originalText); Text("${detail.sources.size} 个来源 · ${detail.history.size} 个历史版本") }
                }
            }
            InkPrimaryAction("确认导入 ${selected.size} 条", enabled = !busy && selected.isNotEmpty()) { onImport(selected) }
        }
        account.conflicts.forEach { conflict ->
            InkSectionTitle("版本冲突")
            Text("本机版本"); Text(conflictFields(conflict.local))
            Text("云端版本"); Text(conflictFields(conflict.cloud))
            Text("选择后双方历史均保留；保留本机需要再次同步。")
            InkSecondaryAction("保留本机", enabled = !busy) { onResolve(conflict.id, true) }
            InkSecondaryAction("采用云端", enabled = !busy) { onResolve(conflict.id, false) }
        }
        InkSecondaryAction("退出登录", enabled = !busy, onClick = onSignOut)
    } else {
        Text("账号尚未就绪。读取失败时可明确退出后重新登录。")
        InkSecondaryAction("清除本机登录状态", enabled = !busy, onClick = onSignOut)
    }
    InkSectionTitle("工具与通知")
    InkSecondaryAction("版本与更新", modifier = Modifier.testTag("open_updates"), onClick = onUpdates)
    InkSecondaryAction("回顾、分享与备份", enabled = account.ready && !busy, onClick = onExtras)
    if (account.ready) {
        NotificationCenter(ownerNamespace = owner, onOpenRecord = onOpen)
    }
}

private fun conflictFields(text: String): String = runCatching {
    val root = JSONObject(text)
    val row = root.optJSONObject("record") ?: root
    val fields = linkedMapOf("original_text" to "原话", "subject" to "主体", "due_start" to "起始日期", "due_end" to "结束日期",
        "date_text" to "日期原文", "timezone" to "时区", "verification_criteria" to "验证标准", "notes" to "备注", "deleted_at" to "删除时间")
        .map { (key, label) -> "$label：${if (row.isNull(key)) "未填写" else row.optString(key).ifBlank { "未填写" }}" }.joinToString("\n")
    val sources = root.optJSONArray("sources")
    fields + if (sources == null || sources.length() == 0) "\n来源：无" else "\n来源：\n" + (0 until sources.length()).joinToString("\n") { i -> val source = sources.getJSONObject(i); source.optString("title") + " " + source.optString("url") }
}.getOrDefault("内容无法显示，请稍后重试")
