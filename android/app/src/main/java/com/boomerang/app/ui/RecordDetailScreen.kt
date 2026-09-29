package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import com.boomerang.app.data.RecordDetail
import com.boomerang.app.domain.datePrecisions
import com.boomerang.app.domain.recordTypes
import com.boomerang.app.domain.resultStatuses

@Composable
fun RecordDetailScreen(detail: RecordDetail?, countdown: String, history: List<HistoryDisplay>, busy: Boolean, message: String?,
    onEdit: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, onLock: () -> Unit = {}) {
    var tab by rememberSaveable(detail?.record?.id) { mutableStateOf("standard") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(detail?.record?.id) { mutableStateOf(false) }
    var confirmLock by rememberSaveable(detail?.record?.id) { mutableStateOf(false) }
    val enabled = detail != null && !busy
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("back")) { Text("返回") }
            Text("记录详情", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("detail_more")) { Text("更多") }
                DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                    if (detail?.record?.content?.capsuleLockedAt == null && detail?.record?.content?.dueEnd != null) {
                        DropdownMenuItem(text = { Text("封存为时间胶囊") }, onClick = { menu = false; confirmLock = true })
                    }
                    DropdownMenuItem(text = { Text("删除记录", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true }, modifier = Modifier.testTag("delete_record"))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (detail == null) { if (message == null) CircularProgressIndicator() }
            else {
                val c = detail.record.content
                Text("${recordTypes[c.recordType]} · ${c.subject}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(c.originalText, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("detail_original"))
                Text("说于 ${c.saidAt ?: "未知日期"} · ${c.timezone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(countdown, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text(if (c.dueEnd == null) "期限未知" else "${c.dueStart} ～ ${c.dueEnd}（${datePrecisions[c.datePrecision]}）")
                        Text(if (c.dueEnd == null) "未设明确截止日期" else "结束日期包含当天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.confirmedStatus?.let { "用户确认：${resultStatuses[it] ?: it}" } ?: "结果未确认", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (c.capsuleLockedAt != null) {
                    Field("时间胶囊解锁时间", c.capsuleUnlockAt.orEmpty())
                    Text("封存期间，原话、日期与验证标准不可修改；仍可添加备注。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("standard" to "验证", "sources" to "来源", "history" to "历史").forEach { (key, label) ->
                        TextButton(onClick = { tab = key }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("detail_tab_$key").semantics { selected = tab == key; role = Role.Tab },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (tab == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(label) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (tab) {
                    "standard" -> {
                        Field("验证标准", c.verificationCriteria.ifBlank { "未填写" })
                        Field("说出日期", c.saidAt ?: "未知")
                        Field("日期原文", c.dateText.ifBlank { "未填写" })
                        Field("原时区", c.timezone)
                        Field("主题", c.topic.ifBlank { "未填写" })
                        Field("私人备注", c.notes.ifBlank { "未填写" })
                    }
                    "sources" -> {
                        Text("来源", style = MaterialTheme.typography.titleLarge)
                        if (detail.sources.isEmpty()) Text("尚未添加来源")
                        detail.sources.forEach {
                            Field(it.title, it.url)
                            Text(if (it.verifiedByTool) "工具返回的信源 · 不代表结论已确认" else if (it.clientEditable()) "手工引用 · 尚未核实" else "云端保留的信源 · 不代表结论已确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Text("来源链接供追溯，链接本身不代表结论已确认", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "history" -> {
                        Text("修改历史", style = MaterialTheme.typography.titleLarge)
                        if (history.isEmpty()) Text("暂无修改历史")
                        history.forEach { entry ->
                            var expanded by rememberSaveable(detail.record.id, entry.version) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text("第 ${entry.version} 版 · ${entry.createdAt}") }
                            if (expanded) entry.fields.forEach { (title, value) -> Field(title, value) }
                        }
                    }
                }
            }
        }
        InkActionBar("编辑记录", "edit_record", enabled, onEdit)
    }
    if (confirmDelete && detail != null) AlertDialog(onDismissRequest = { if (!busy) confirmDelete = false }, title = { Text("删除这条记录？") },
        text = { Text("记录将从镖库隐藏。修订历史与删除标记仍会保留。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }, enabled = !busy, modifier = Modifier.testTag("confirm_delete")) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("取消") } })
    if (confirmLock && detail != null) AlertDialog(onDismissRequest = { if (!busy) confirmLock = false }, title = { Text("封存时间胶囊？") },
        text = { Text("截止日结束前，原话、日期和验证标准不能再改；仍可添加备注。") },
        confirmButton = { TextButton(onClick = { confirmLock = false; onLock() }, enabled = !busy) { Text("确认封存") } },
        dismissButton = { TextButton(onClick = { confirmLock = false }, enabled = !busy) { Text("取消") } })
}

@Composable
private fun Field(title: String, value: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyLarge)
}
