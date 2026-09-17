package com.boomerang.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boomerang.app.domain.*

@Composable
fun RecordEditor(draft: EditorState, errors: Map<String, String>, busy: Boolean, message: String?,
    onContent: (RecordContent) -> Unit, onSources: (List<SourceInput>) -> Unit, onSave: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier) = Page(modifier) {
    val c = draft.content
    TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag("back")) { Text("返回") }
    Heading(if (draft.id == null) "新建记录" else "编辑记录", "保留原话，不替当时的表达下结论。")
    if (errors.isNotEmpty()) Text("请检查下方填写内容", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("validation_error"))
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Choice("类型", c.recordType, recordTypes) { onContent(c.copy(recordType = it)) }
    EditorField("人物 / 主体", c.subject, "subject", errors, busy) { onContent(c.copy(subject = it)) }
    EditorField("原话", c.originalText, "originalText", errors, busy, multiline = true, tag = "quote_input") { onContent(c.copy(originalText = it)) }
    EditorField("说出日期（可留空）", c.saidAt.orEmpty(), "saidAt", errors, busy, hint = "YYYY-MM-DD") { onContent(c.copy(saidAt = it.ifBlank { null })) }
    HorizontalDivider()
    Text("日期与期限", style = MaterialTheme.typography.titleLarge)
    Text("不明确的日期请留空。结束日期包含当天。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Choice("日期精度", c.datePrecision, datePrecisions) { onContent(c.copy(datePrecision = it)) }
    errors["datePrecision"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    EditorField("开始日期", c.dueStart.orEmpty(), "dueStart", errors, busy, hint = "YYYY-MM-DD") { onContent(c.copy(dueStart = it.ifBlank { null })) }
    EditorField("结束日期", c.dueEnd.orEmpty(), "dueEnd", errors, busy, hint = "YYYY-MM-DD") { onContent(c.copy(dueEnd = it.ifBlank { null })) }
    EditorField("日期原文", c.dateText, "dateText", errors, busy, hint = "例如：明年第一季度") { onContent(c.copy(dateText = it)) }
    EditorField("原时区", c.timezone, "timezone", errors, busy) { onContent(c.copy(timezone = it)) }
    EditorField("验证标准", c.verificationCriteria, "verificationCriteria", errors, busy, multiline = true) { onContent(c.copy(verificationCriteria = it)) }
    HorizontalDivider()
    Text("来源", style = MaterialTheme.typography.titleLarge)
    Text("手工来源仅保留你的引用，不代表已核实。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    errors["sources"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    draft.sources.forEachIndexed { index, source ->
        EditorField("来源 ${index + 1} 标题", source.title, "sourceTitle$index", errors, busy) { value -> onSources(draft.sources.mapIndexed { i, item -> if (i == index) item.copy(title = value) else item }) }
        EditorField("HTTPS 链接", source.url, "sourceUrl$index", errors, busy) { value -> onSources(draft.sources.mapIndexed { i, item -> if (i == index) item.copy(url = value) else item }) }
        TextButton(onClick = { onSources(draft.sources.filterIndexed { i, _ -> i != index }) }, enabled = !busy) { Text("移除此来源") }
    }
    OutlinedButton(onClick = { onSources(draft.sources + SourceInput()) }, enabled = !busy && draft.sources.size < 10) { Text("添加来源") }
    var more by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { more = !more }) { Text(if (more) "收起主题与备注" else "展开主题与备注") }
    if (more) {
        EditorField("主题", c.topic, "topic", errors, busy) { onContent(c.copy(topic = it)) }
        EditorField("私人备注", c.notes, "notes", errors, busy, multiline = true) { onContent(c.copy(notes = it)) }
        Choice("记录状态", c.lifecycle, mapOf("ACTIVE" to "进行中", "DRAFT" to "草稿", "CANCELLED" to "已取消")) { onContent(c.copy(lifecycle = it)) }
    }
    Text("返回后可继续编辑。点击保存才会写入镖库。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("save_record")) { Text(if (busy) "正在保存…" else "保存记录") }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun EditorField(label: String, value: String, key: String, errors: Map<String, String>, busy: Boolean,
    multiline: Boolean = false, hint: String = "", tag: String = key, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        placeholder = { Text(hint) }, enabled = !busy, minLines = if (multiline) 3 else 1, singleLine = !multiline,
        isError = errors.containsKey(key), supportingText = errors[key]?.let { error -> { Text(error) } })
}

@Preview(showBackground = true, name = "编辑 · 明确演示内容")
@Composable private fun EditorPreview() { BoomerangTheme { RecordEditor(EditorState(RecordContent(originalText = "演示：今年读完十二本书")), emptyMap(), false, null, {}, {}, {}, {}) } }
