package com.boomerang.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.boomerang.app.R

@Composable
private fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
private fun Heading(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NewRecordButton(onCreate: () -> Unit) {
    Button(onClick = onCreate, modifier = Modifier.testTag("create_record")) { Text("新建记录") }
}

@Composable
fun HomeScreen(onCreate: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    Heading("回旋镖", "记下原话，留待时间验证。")
    Spacer(Modifier.height(12.dp))
    Icon(painterResource(R.drawable.ic_boomerang), contentDescription = null,
        modifier = Modifier.size(104.dp), tint = MaterialTheme.colorScheme.primary)
    Text("待回响", style = MaterialTheme.typography.headlineMedium)
    Text("还没有待跟进的记录", style = MaterialTheme.typography.titleMedium)
    Text("从一句承诺、一个目标或一次预测开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    NewRecordButton(onCreate)
    HorizontalDivider()
    Text("原话与证据分别保留，最终结果由你确认。", style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun LibraryScreen(onCreate: () -> Unit, onDetail: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    Heading("你的镖库", "每一次回看，都有迹可循。")
    Spacer(Modifier.height(32.dp))
    Text("这里还没有记录", style = MaterialTheme.typography.headlineSmall)
    Text("保存后的原话、期限和验证标准会放在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    NewRecordButton(onCreate)
    TextButton(onClick = onDetail) { Text("了解记录详情") }
}

@Composable
fun AiScreen(modifier: Modifier = Modifier) = Page(modifier) {
    Heading("AI 助手", "帮你整理原话，寻找可追溯的证据。")
    HorizontalDivider()
    Text("尚未启用", style = MaterialTheme.typography.titleLarge)
    Text("智能录入与联网调查正在准备中。启用后，AI 建议会先交给你检查，确认后才会进入镖库。")
    Text("当前不会上传内容，也不会发起 AI 请求。", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) = Page(modifier) {
    Heading("我的空间", "回旋镖 · 0.1.0")
    HorizontalDivider()
    Text("当前未登录", style = MaterialTheme.typography.titleLarge)
    Text("账号登录与云端同步尚未启用。")
    HorizontalDivider()
    Text("关于记录", style = MaterialTheme.typography.titleMedium)
    Text("原话、来源证据、AI 建议和你的确认会分别保存。你的记录默认私有。")
    Text("当前为基础预览版，记录保存将在后续版本启用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun RecordEditor(quote: String, onQuote: (String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("返回") }
    Heading("新建记录", "先记下当时说过的话。")
    OutlinedTextField(value = quote, onValueChange = onQuote,
        modifier = Modifier.fillMaxWidth().testTag("quote_input"),
        label = { Text("原话") }, placeholder = { Text("例如：今年读完十二本书") },
        minLines = 5, supportingText = { Text("${quote.length} / 4000") })
    Text("保存尚未启用。返回或旋转屏幕后可继续编辑；关闭应用前，请自行保留文字。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = {}, enabled = false) { Text("保存记录（尚未启用）") }
}

@Composable
fun DetailShell(onBack: () -> Unit, modifier: Modifier = Modifier) = Page(modifier) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("返回") }
    Heading("记录详情", "结构预览 · 这里没有真实记录")
    listOf("原话" to "保留最初的表达", "日期与期限" to "明确的日期才会用于倒计时", "验证标准" to "记录什么情况算达成", "来源与修订" to "保留证据及修改历史").forEach { (title, description) ->
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, name = "首页 · 明亮")
@Composable private fun HomePreview() { BoomerangTheme(false) { HomeScreen({}) } }

@Preview(showBackground = true, name = "首页 · 深色")
@Composable private fun DarkHomePreview() { BoomerangTheme(true) { HomeScreen({}) } }

@Preview(showBackground = true, name = "编辑 · 演示文字")
@Composable private fun EditorPreview() { BoomerangTheme { RecordEditor("演示：今年读完十二本书", {}, {}) } }
