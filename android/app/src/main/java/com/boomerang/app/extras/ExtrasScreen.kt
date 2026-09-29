package com.boomerang.app.extras

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.boomerang.app.ui.InkPageTitle
import com.boomerang.app.ui.InkSectionTitle
import com.boomerang.app.ui.InkPrimaryAction
import com.boomerang.app.ui.InkSecondaryAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boomerang.app.domain.resultStatuses
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ExtrasScreen(ownerNamespace: String, onOpenRecord: (String) -> Unit, modifier: Modifier = Modifier, model: ExtrasViewModel = viewModel(), onBack: () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var transferOwner by rememberSaveable { mutableStateOf(ownerNamespace) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { uri -> model.export(uri, transferOwner) } }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> model.previewImport(uri, transferOwner) } }
    LaunchedEffect(ownerNamespace) { model.selectOwner(ownerNamespace) }
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    var timezone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var subject by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    val rows = if (state.owner == ownerNamespace) state.records.map { ReviewRecord(it.record.id, it.record.content, it.record.createdAt, it.record.deletedAt) } else emptyList()
    val report = remember(rows, year, timezone) { runCatching { ReviewPolicy.annual(rows, year.toInt(), timezone) }.getOrNull() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
        InkPageTitle("回顾与备份", "本机统计、回旋卡与资料迁移")
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it) }
        TextButton(onClick = model::refresh, enabled = !state.busy) { Text("刷新本机记录") }
        InkSectionTitle("年度报告")
        Text("按创建时间归入所选年份和时区，排除已删除记录；AI 建议不计作用户确认。")
        OutlinedTextField(year, { year = it }, label = { Text("年份") }, singleLine = true)
        OutlinedTextField(timezone, { timezone = it }, label = { Text("统计时区，例如 Asia/Shanghai") }, singleLine = true)
        if (report == null) Text("请输入有效年份与时区") else {
            Text("${report.year} 年 · ${report.timezone}：共 ${report.total} 条，未确认 ${report.unconfirmed} 条")
            report.confirmed.forEach { (status, count) -> Text("用户确认 · ${resultStatuses[status] ?: status}：$count 条") }
        }
        InkSectionTitle("备份与恢复")
        Text("备份包含原话、私人备注、来源、修订和删除标记，不包含登录密钥。仅允许恢复到同一账号或原本机空间。")
        InkPrimaryAction("导出 JSON", enabled = !state.busy) { transferOwner = ownerNamespace; export.launch("boomerang-${LocalDate.now()}.json") }
        InkSecondaryAction("导入预览", enabled = !state.busy) { transferOwner = ownerNamespace; load.launch(arrayOf("application/json", "text/plain")) }
        InkSectionTitle("人物 / 事件时间轴")
        Text("按原样填写的主体与主题精确筛选，不推断同名人物身份。说出日期未知的记录保留在末尾；打开详情可查看修订。")
        OutlinedTextField(subject, { subject = it }, label = { Text("主体，留空查看全部") }, singleLine = true)
        OutlinedTextField(topic, { topic = it }, label = { Text("主题，留空查看全部") }, singleLine = true)
        val timeline = ReviewPolicy.timeline(rows, subject, topic)
        if (timeline.isEmpty()) Text("没有匹配记录")
        timeline.forEach { row ->
            Text("${row.content.saidAt ?: "日期未知"} · ${row.content.subject}", style = MaterialTheme.typography.labelLarge)
            Text(row.content.originalText.take(160))
            TextButton(onClick = { onOpenRecord(row.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看详情与历史") }
            TextButton(onClick = { model.previewCard(row.id) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("预览回旋卡") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    state.preview?.takeIf { state.owner == ownerNamespace }?.let { preview ->
        ImportPreviewDialog(preview.backup.records.size, preview.conflicts,
            preview.backup.records.take(5).map { it.record.content.originalText.take(50) }, state.busy,
            onSkip = { model.confirmImport(ImportChoice.SKIP_EXISTING) },
            onReplace = { model.confirmImport(ImportChoice.REPLACE_EXISTING) }, onCancel = model::cancelImport)
    }
    state.card?.takeIf { state.owner == ownerNamespace }?.let { file ->
        val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath).asImageBitmap() }
        AlertDialog(onDismissRequest = model::closeCard, title = { Text("回旋卡预览（不含私人备注）") },
            text = { Image(bitmap, "待分享的回旋卡", Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { context.startActivity(Intent.createChooser(ShareCard.intent(context, file), "分享回旋卡")) }) { Text("打开系统分享") } },
            dismissButton = { TextButton(onClick = model::closeCard) { Text("关闭") } })
    }
}

@Composable
fun ImportPreviewDialog(recordCount: Int, conflicts: Int, excerpts: List<String>, busy: Boolean,
    onSkip: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("确认导入预览", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已校验 $recordCount 条记录，其中 $conflicts 条 UUID 已存在。替换会保留原历史，并追加导入版本。")
                    Text("删除标记将被保留。账号内导入的记录随后参与云同步；不会迁入其他账号。")
                    Text("文件中的确认结果仅作为未核验历史存档，导入后的当前记录为未确认。超过 10 个云端来源的记录请通过云同步恢复。")
                    excerpts.forEach { Text(it) }
                }
                ImportPreviewActions(conflicts, busy, onSkip, onReplace, onCancel)
            }
        }
    }
}

@Composable
fun ImportPreviewActions(conflicts: Int, busy: Boolean, onSkip: () -> Unit, onReplace: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InkPrimaryAction("跳过重复并导入", enabled = !busy, onClick = onSkip)
        if (conflicts > 0) InkSecondaryAction("替换重复并导入", enabled = !busy, onClick = onReplace)
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("取消") }
    }
}
