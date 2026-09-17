package com.boomerang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boomerang.app.R
import com.boomerang.app.data.RecordEntity
import com.boomerang.app.domain.*

@Composable
internal fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable
internal fun Heading(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
private fun NewRecordButton(onCreate: () -> Unit) {
    Button(onClick = onCreate, modifier = Modifier.testTag("create_record")) { Text("新建记录") }
}
@Composable
internal fun Choice(label: String, value: String, choices: Map<String, String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label：${choices[value] ?: value}") }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}
@Composable
private fun LoadingOrError(state: LibraryState, onRetry: () -> Unit) {
    if (state.loading) { CircularProgressIndicator(); Text("正在读取本机记录…") }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = onRetry) { Text("重试") } }
}
@Composable
private fun RecordRow(record: RecordEntity, countdown: String, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(record.id) }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${recordTypes[record.content.recordType]} · ${record.content.subject}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(record.content.originalText, style = MaterialTheme.typography.titleMedium, maxLines = 3)
        Text(countdown, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}
@Composable
fun HomeScreen(state: LibraryState, countdown: (RecordEntity) -> String, onCreate: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    Heading("回旋镖", "记下原话，留待时间验证。")
    NewRecordButton(onCreate)
    LoadingOrError(state, onRetry)
    if (!state.loading && state.error == null) {
        val pending = state.records.filter { it.content.lifecycle == "ACTIVE" && it.content.confirmedStatus == null && it.content.dueEnd != null }
        Text("待回响", style = MaterialTheme.typography.headlineMedium)
        if (pending.isEmpty()) {
            Icon(painterResource(R.drawable.ic_boomerang), null, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
            Text("还没有待跟进的期限", style = MaterialTheme.typography.titleMedium)
            Text("无期限的记录可在镖库查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else pending.take(10).forEach { RecordRow(it, countdown(it), onOpen) }
    }
}
@Composable
fun LibraryScreen(state: LibraryState, visible: List<RecordEntity>, query: String, type: String, result: String, sort: String,
    onQuery: (String) -> Unit, onType: (String) -> Unit, onResult: (String) -> Unit, onSort: (String) -> Unit,
    countdown: (RecordEntity) -> String, onCreate: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    Heading("你的镖库", "原话、证据与修改历史，都留在这里。")
    NewRecordButton(onCreate)
    OutlinedTextField(query, onQuery, label = { Text("搜索原话、人物、主题或备注") }, modifier = Modifier.fillMaxWidth().testTag("search"), singleLine = true)
    Choice("类型", type, mapOf("" to "全部") + recordTypes, onType)
    Choice("结果", result, mapOf("" to "全部", "UNCONFIRMED" to "未确认") + resultStatuses, onResult)
    Choice("排序", sort, mapOf("due" to "截止日期优先", "updated" to "最近修改优先"), onSort)
    LoadingOrError(state, onRetry)
    if (!state.loading && state.error == null) {
        if (visible.isEmpty()) Text(if (state.records.isEmpty()) "这里还没有记录" else "没有符合条件的记录", style = MaterialTheme.typography.titleMedium)
        else visible.forEach { RecordRow(it, countdown(it), onOpen) }
    }
}
@Composable
fun AiScreen(modifier: Modifier = Modifier) = Page(modifier) {
    Heading("AI 助手", "帮你整理原话，寻找可追溯的证据。")
    HorizontalDivider(); Text("尚未启用", style = MaterialTheme.typography.titleLarge)
    Text("智能录入与联网调查正在准备中。AI 建议会先交给你检查，确认后才会进入镖库。")
    Text("当前不会上传内容，也不会发起 AI 请求。", color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) = Page(modifier) {
    Heading("我的空间", "回旋镖 · 0.2.0")
    HorizontalDivider(); Text("本机空间", style = MaterialTheme.typography.titleLarge)
    Text("记录保存在当前设备，暂未登录或同步。卸载应用会移除本机数据。")
    HorizontalDivider(); Text("关于记录", style = MaterialTheme.typography.titleMedium)
    Text("原话、来源证据、AI 建议和你的确认分别保留。你的记录默认私有。")
}
@Preview(showBackground = true, name = "首页 · 明亮")
@Composable private fun HomePreview() { BoomerangTheme(false) { HomeScreen(LibraryState(false), { "未设期限" }, {}, {}, {}) } }
@Preview(showBackground = true, name = "首页 · 深色")
@Composable private fun DarkHomePreview() { BoomerangTheme(true) { HomeScreen(LibraryState(false), { "未设期限" }, {}, {}, {}) } }
