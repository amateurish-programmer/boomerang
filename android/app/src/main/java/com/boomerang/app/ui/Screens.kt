package com.boomerang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boomerang.app.data.RecordEntity
import com.boomerang.app.domain.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

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
internal fun Choice(label: String, value: String, choices: Map<String, String>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("$label：${choices[value] ?: value}") }
        DropdownMenu(expanded && enabled, { expanded = false }) {
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
    val c = record.content
    Column(Modifier.fillMaxWidth().clickable { onOpen(record.id) }.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${recordTypes[c.recordType]} · ${c.subject}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(c.originalText, style = MaterialTheme.typography.titleLarge, maxLines = 3)
        Text(countdown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text("${c.dueEnd?.let { "$it 截止" } ?: "期限未知"} · ${c.confirmedStatus?.let { resultStatuses[it] } ?: "结果未确认"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
@Composable
fun HomeScreen(state: LibraryState, countdown: (RecordEntity) -> String, onCreate: () -> Unit, onOpen: (String) -> Unit,
    onRetry: () -> Unit, modifier: Modifier = Modifier, onLibrary: () -> Unit = {},
    motionMode: InkMotionMode = InkMotionMode.FULL, systemAnimationScale: Float = defaultSystemAnimationScale(),
    pendingSaveFeedback: Long? = null, onConsumeSaveFeedback: (Long) -> Unit = {},
    entranceShown: Boolean = false, onEntranceShown: () -> Unit = {}, dateLabel: String = "", onHomeVisible: () -> Unit = {}) {
    val pending = state.records.filter { it.content.lifecycle == "ACTIVE" && it.content.confirmedStatus == null && it.content.dueEnd != null }
    val list = rememberLazyListState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focused = LocalWindowInfo.current.isWindowFocused
    val heroVisible by remember(list) { derivedStateOf {
        val layout = list.layoutInfo
        layout.visibleItemsInfo.any { it.key == "home_hero" && it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
    } }
    val eligible = inkMotionEligible(lifecycle == Lifecycle.State.RESUMED, focused, true, heroVisible)
    val mode = effectiveMotionMode(motionMode, minOf(systemAnimationScale, frameworkAnimationScale()))
    val entrance = remember { InkEntranceState() }
    var hasEntered by rememberSaveable { mutableStateOf(entranceShown) }
    val latestVisible by rememberUpdatedState(onHomeVisible)
    LaunchedEffect(eligible) { if (eligible) latestVisible() }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "home_hero") {
                InkLandscape(mode, eligible, hasEntered, { hasEntered = true; onEntranceShown() }, entrance,
                    parallax = { if (list.firstVisibleItemIndex == 0) (list.firstVisibleItemScrollOffset / 40f).coerceAtMost(6f) else 0f }, dateLabel = dateLabel)
            }
            item(key = "home_heading") {
                Column(Modifier.padding(horizontal = 24.dp).graphicsLayer { alpha = if (eligible && mode != InkMotionMode.OFF) entrance.recordsAlpha else 1f }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("待回响", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onLibrary, modifier = Modifier.heightIn(min = 48.dp).testTag("all_records")) { Text("全部记录") }
                    }
                    LoadingOrError(state, onRetry)
                }
            }
            if (!state.loading && state.error == null) {
                if (pending.isEmpty()) item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 32.dp).graphicsLayer { alpha = if (eligible && mode != InkMotionMode.OFF) entrance.recordsAlpha else 1f }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("还没有待跟进的期限", style = MaterialTheme.typography.titleLarge)
                        Text("无期限的记录可在镖库查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(pending, key = { it.id }) { record ->
                    Box(Modifier.padding(horizontal = 24.dp).graphicsLayer { alpha = if (eligible && mode != InkMotionMode.OFF) entrance.recordsAlpha else 1f }) { RecordRow(record, countdown(record), onOpen) }
                }
            }
        }
        InkDecoratedActionBar("记下原话", "create_record", feedback = {
            InkSaveFeedback(pendingSaveFeedback, onConsumeSaveFeedback, mode, eligible, Modifier.matchParentSize())
        }, onClick = onCreate)
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(state: LibraryState, visible: List<RecordEntity>, query: String, type: String, result: String, sort: String,
    onQuery: (String) -> Unit, onType: (String) -> Unit, onResult: (String) -> Unit, onSort: (String) -> Unit,
    countdown: (RecordEntity) -> String, onCreate: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var filters by rememberSaveable { mutableStateOf(false) }
    var draftType by rememberSaveable { mutableStateOf(type) }
    var draftResult by rememberSaveable { mutableStateOf(result) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        item(key = "library_heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("你的镖库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp).testTag("create_record")) { Text("新建记录") }
            }
            Text("原话有迹，往事可循。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
        }
        item(key = "library_search") {
            OutlinedTextField(query, onQuery, label = { Text("搜索原话、人物、主题或备注") }, modifier = Modifier.fillMaxWidth().testTag("search"), singleLine = true)
        }
        item(key = "library_filters") {
            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { draftType = type; draftResult = result; filters = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_filters")) {
                    Text("${recordTypes[type] ?: "全部类型"} · ${resultStatuses[result] ?: if (result == "UNCONFIRMED") "未确认" else "全部结果"}")
                }
                Choice("排序", sort, mapOf("due" to "截止日期优先", "updated" to "最近修改优先"), onChange = onSort)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item(key = "library_status") {
            Text("共 ${visible.size} 条记录", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LoadingOrError(state, onRetry)
        }
        if (!state.loading && state.error == null) {
            if (visible.isEmpty()) item(key = "library_empty") {
                Text(if (state.records.isEmpty()) "这里还没有记录" else "没有符合条件的记录", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.titleMedium)
            }
            items(visible, key = { it.id }) { RecordRow(it, countdown(it), onOpen) }
        }
    }
    if (filters) ModalBottomSheet(onDismissRequest = { filters = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 24.dp)) {
            Text("筛选记录", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("类型", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (mapOf("" to "全部类型") + recordTypes).forEach { (key, label) ->
                        FilterChip(selected = draftType == key, onClick = { draftType = key }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("filter_type_$key"))
                    }
                }
                Text("结果", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (mapOf("" to "全部结果", "UNCONFIRMED" to "未确认") + resultStatuses).forEach { (key, label) ->
                        FilterChip(selected = draftResult == key, onClick = { draftResult = key }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("filter_result_$key"))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { filters = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("cancel_filters")) { Text("取消") }
                Button(shape = MaterialTheme.shapes.small, onClick = { onType(draftType); onResult(draftResult); filters = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("apply_filters")) { Text("应用筛选") }
            }
        }
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
@Composable private fun HomePreview() { BoomerangTheme(false) { HomeScreen(LibraryState(false), { "未设期限" }, {}, {}, {}, motionMode = InkMotionMode.OFF, systemAnimationScale = 1f) } }
@Preview(showBackground = true, name = "首页 · 深色")
@Composable private fun DarkHomePreview() { BoomerangTheme(true) { HomeScreen(LibraryState(false), { "未设期限" }, {}, {}, {}, motionMode = InkMotionMode.OFF, systemAnimationScale = 1f) } }
