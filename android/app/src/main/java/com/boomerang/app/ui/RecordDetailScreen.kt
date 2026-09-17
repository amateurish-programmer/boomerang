package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.boomerang.app.data.RecordDetail
import com.boomerang.app.domain.datePrecisions
import com.boomerang.app.domain.recordTypes

@Composable
fun RecordDetailScreen(detail: RecordDetail?, countdown: String, history: List<HistoryDisplay>, busy: Boolean, message: String?,
    onEdit: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, onLock: () -> Unit = {}) = Page(modifier) {
    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("back")) { Text("返回") }
    Heading("记录详情", countdown)
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (detail == null) { if (message == null) CircularProgressIndicator() }
    else {
        val c = detail.record.content
        Text("${recordTypes[c.recordType]} · ${c.subject}", style = MaterialTheme.typography.titleMedium)
        Text(c.originalText, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("detail_original"))
        OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("edit_record")) { Text("编辑记录") }
        HorizontalDivider()
        if (c.capsuleLockedAt != null) Field("时间胶囊解锁时间", c.capsuleUnlockAt.orEmpty())
        else if (c.dueEnd != null) {
            var confirmLock by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { confirmLock = true }, enabled = !busy) { Text("封存为时间胶囊") }
            if (confirmLock) AlertDialog(onDismissRequest = { confirmLock = false }, title = { Text("封存时间胶囊？") },
                text = { Text("截止日结束前，原话、日期和验证标准不能再改；仍可添加备注。") },
                confirmButton = { TextButton(onClick = { confirmLock = false; onLock() }) { Text("确认封存") } },
                dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("取消") } })
        }
        Field("说出日期", c.saidAt ?: "未知")
        Field("期限", if (c.dueEnd == null) "未知" else "${c.dueStart} ～ ${c.dueEnd}（${datePrecisions[c.datePrecision]}）")
        Field("日期原文", c.dateText.ifBlank { "未填写" })
        Field("原时区", c.timezone)
        Field("验证标准", c.verificationCriteria.ifBlank { "未填写" })
        Field("主题", c.topic.ifBlank { "未填写" })
        Field("私人备注", c.notes.ifBlank { "未填写" })
        HorizontalDivider(); Text("来源", style = MaterialTheme.typography.titleLarge)
        if (detail.sources.isEmpty()) Text("尚未添加来源")
        detail.sources.forEach {
            Field(it.title, it.url)
            Text(if (it.verifiedByTool) "工具返回的信源 · 不代表结论已确认" else if (it.clientEditable()) "手工引用 · 尚未核实" else "云端保留的信源 · 不代表结论已确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("来源链接供追溯，链接本身不代表结论已确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(); Text("修改历史", style = MaterialTheme.typography.titleLarge)
        history.forEach { entry ->
            var expanded by rememberSaveable(entry.version) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text("第 ${entry.version} 版 · ${entry.createdAt}") }
            if (expanded) entry.fields.forEach { (title, value) -> Field(title, value) }
        }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.testTag("delete_record")) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这条记录？") },
            text = { Text("记录将从镖库隐藏。修订历史与删除标记仍会保留。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, modifier = Modifier.testTag("confirm_delete")) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    }
}

@Composable
private fun Field(title: String, value: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyLarge)
}
