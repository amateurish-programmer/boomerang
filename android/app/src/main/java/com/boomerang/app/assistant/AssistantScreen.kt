package com.boomerang.app.assistant

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.boomerang.app.domain.*
import java.time.LocalDate
import java.time.ZoneId

/** Host must key this composition by its account namespace, including anonymous. */
@Composable
fun AssistantScreen(ownerNamespace: String, onDraft: (RecordContent)->Unit, onRecord: (String)->Unit, modifier: Modifier = Modifier) {
    val app=LocalContext.current.applicationContext as Application
    if(ownerNamespace=="local") {
        Column(modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("AI 助手",style=MaterialTheme.typography.headlineMedium)
            Text("请先在「我的」登录，才能使用 AI 助手。")
            Text("AI 内容需要你核对。没有配置服务时仍可手动记录。")
        }
        return
    }
    val id=ownerNamespace
    val factory=remember(id) { object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T = AssistantViewModel(app,id) as T
    } }
    val model: AssistantViewModel=viewModel(key="assistant-$id",factory=factory)
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.openRecord) { state.openRecord?.let { model.consumedNavigation(); onRecord(it) } }
    var section by rememberSaveable { mutableStateOf("录入") }
    var text by rememberSaveable(section) { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var zone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("AI 助手",style=MaterialTheme.typography.headlineMedium)
        Text("原话、来源、AI 建议与你的确认分开保存。AI 不会替你作最终判断。")
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("录入","对话","调查","复核").forEach { item -> FilterChip(selected=section==item,onClick={ section=item },label={ Text(item) },enabled=!state.busy) }
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("assistant_message")) }
        if(section in listOf("录入","调查","对话")) {
            OutlinedTextField(text,{ if(it.length<=8000) text=it },label={ Text(if(section=="调查") "调查主题" else "想说的话") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,minLines=3)
        }
        if(section in listOf("录入","调查")) {
            OutlinedTextField(date,{ date=it },label={ Text(if(section=="录入") "说出日期 YYYY-MM-DD" else "本次调查日期 YYYY-MM-DD") },enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(zone,{ zone=it },label={ Text("原时区，如 Asia/Shanghai") },enabled=!state.busy,modifier=Modifier.fillMaxWidth())
        }
        when(section) {
            "录入" -> {
                Button(onClick={ model.analyze(text,date,zone) },enabled=!state.busy && text.isNotBlank(),modifier=Modifier.testTag("assistant_analyze")) { Text("整理为待确认草稿") }
                state.draft?.let { draft ->
                    HorizontalDivider(); Text("待确认草稿 · 尚未保存",style=MaterialTheme.typography.titleMedium)
                    DraftPreview(draft.content)
                    draft.questions.forEach { Text("待澄清：$it") }
                    Button(onClick={ model.dismissDraft(); onDraft(draft.content.copy(lifecycle="ACTIVE")) },enabled=!state.busy) { Text("核对并编辑") }
                    TextButton(onClick=model::dismissDraft) { Text("取消草稿") }
                    Text("下一页可修改全部字段，只有点击保存才写入镖库。")
                }
            }
            "对话" -> {
                Button(onClick={ model.chat(text) },enabled=!state.busy && text.isNotBlank()) { Text("发送") }
                TextButton(onClick=model::newConversation,enabled=!state.busy) { Text("新对话") }
                state.messages.forEach { Text((if(it.role=="user") "我：" else "AI：")+it.text) }
                HorizontalDivider(); Text("最近对话（最多 20 条）")
                state.conversations.forEach { c -> TextButton(onClick={ model.conversation(c.id) },enabled=!state.busy) { Text(c.title.ifBlank { "未命名对话" }) } }
                Text("对话只保存为聊天记录，不会自动写入镖库。")
                HorizontalDivider(); Text("我的 AI 周报",style=MaterialTheme.typography.titleMedium)
                Text("按当前时区周一至周日，汇总本人本周新增的最近 10 条云端记录；原话最多取前 200 字。先同步再生成。仅供本人查看，不自动发布。")
                Button(onClick=model::weekly,enabled=!state.busy) { Text("生成本周摘要") }
                state.weeklySummary?.let { Text("AI 摘要（请核对引用）\n$it") }
            }
            "调查" -> {
                Button(onClick={ model.research(text,date,zone) },enabled=!state.busy && text.isNotBlank()) { Text("开始调查") }
                TextButton(onClick=model::refresh,enabled=!state.busy) { Text("刷新最近任务") }
                Text("离开或重启后可在这里继续查看最近 20 个任务。")
                state.jobs.forEach { job ->
                    Text(job.query)
                    TextButton(onClick={ model.candidates(job.id) },enabled=!state.busy) { Text(jobStatus(job.status)+" · 查看候选") }
                }
                state.candidates.forEach { candidate ->
                    HorizontalDivider(); Text("调查候选 · AI 建议",style=MaterialTheme.typography.titleMedium)
                    DraftPreview(candidate.content)
                    candidate.sources.forEach { SourceView(it) }
                    Text("请核对原话、日期与来源。来源数量不代表独立证据数量。")
                    if(candidate.acceptedId!=null) TextButton(onClick={ onRecord(candidate.acceptedId) },enabled=!state.busy) { Text("已收录 · 打开记录") }
                    else key(candidate.id) { CandidateReview(candidate,state.busy) { model.acceptReviewed(candidate,it) } }
                }
            }
            "复核" -> {
                TextButton(onClick=model::refresh,enabled=!state.busy) { Text("刷新云端记录与任务") }
                Text("云端通知（最近 30 条）",style=MaterialTheme.typography.titleMedium)
                if(state.notifications.isEmpty()) Text("暂无云端通知。")
                state.notifications.forEach { notification ->
                    Text((if(notification.read) "已读 · " else "未读 · ")+notification.title)
                    Text(notification.body)
                    TextButton(onClick={ model.readNotification(notification) },enabled=!state.busy) { Text(if(notification.recordId==null) "标为已读" else "查看记录并标为已读") }
                }
                HorizontalDivider()
                Text("先同步本地修改，再选择记录。只显示最近 30 条云端有效记录。")
                state.records.forEach { record -> TextButton(onClick={ model.selectRecord(record) },enabled=!state.busy) { Text(record.text) } }
                state.selectedRecord?.let { record ->
                    HorizontalDivider(); Text("已选择：${record.text}")
                    Button(onClick={ model.verify(record) },enabled=!state.busy) { Text("请求证据复核") }
                    if(state.checks.isEmpty()) Text("尚无复核报告。任务完成后重新选择此记录。")
                    state.checks.forEach { check ->
                        Text("AI 建议：${resultStatuses[check.suggestion]}"+(if(check.revision!=record.revision) "（旧版本，不能确认）" else ""))
                        TextButton(onClick={ model.inspect(check) },enabled=!state.busy) { Text("查看分析与证据") }
                    }
                }
                state.selectedCheck?.let { check ->
                    Text(check.summary)
                    if(state.evidence.isEmpty()) Text("本报告没有可用来源，请谨慎判断。")
                    state.evidence.forEach { item ->
                        Text(when(item.stance) { "SUPPORT" -> "支持证据"; "OPPOSE" -> "反对证据"; else -> "中性证据" })
                        SourceView(item.source)
                    }
                    key(check.id) {
                        var choice by rememberSaveable { mutableStateOf("") }
                        var reason by rememberSaveable { mutableStateOf("") }
                        Text("由你选择最终结果（尚未确认）")
                        resultStatuses.forEach { (value,label) ->
                            Row { RadioButton(selected=choice==value,onClick={ choice=value },enabled=!state.busy); Text(label,modifier=Modifier.padding(top=12.dp)) }
                        }
                        OutlinedTextField(reason,{ if(it.length<=4000) reason=it },label={ Text("确认理由") },enabled=!state.busy,modifier=Modifier.fillMaxWidth())
                        Button(onClick={ model.confirm(check,choice,reason) },enabled=!state.busy && choice.isNotBlank() && reason.isNotBlank() && check.revision==state.selectedRecord?.revision) { Text("确认我的判断") }
                    }
                }
            }
        }
        if(state.jobs.isEmpty() && section=="调查") Text("暂无云端调查任务。服务未配置时不会生成示例候选。")
    }
}

@Composable private fun CandidateReview(candidate: AssistantCandidate,busy: Boolean,onAccept: (RecordContent)->Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val saver=remember(candidate.id) { listSaver<RecordContent,String>(
        save={ listOf(it.subject,it.topic,it.saidAt.orEmpty(),it.dueStart.orEmpty(),it.dueEnd.orEmpty(),it.dateText,it.datePrecision,it.timezone,it.verificationCriteria,it.notes) },
        restore={ candidate.content.copy(subject=it[0],topic=it[1],saidAt=it[2].ifBlank { null },dueStart=it[3].ifBlank { null },dueEnd=it[4].ifBlank { null },dateText=it[5],datePrecision=it[6],timezone=it[7],verificationCriteria=it[8],notes=it[9]) },
    ) }
    var review by rememberSaveable(candidate.id,stateSaver=saver) { mutableStateOf(candidate.content) }
    TextButton(onClick={ editing=!editing },enabled=!busy) { Text(if(editing) "收起核对内容" else "编辑候选信息") }
    if(editing) {
        Text("来源原话保持不变。下列修改只在你确认收录时保存，AI 原提案会保留。")
        OutlinedTextField(review.subject,{ review=review.copy(subject=it) },label={ Text("主体") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.topic,{ review=review.copy(topic=it) },label={ Text("主题") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.saidAt.orEmpty(),{ review=review.copy(saidAt=it.ifBlank { null }) },label={ Text("说出日期 YYYY-MM-DD，可留空") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.dueStart.orEmpty(),{ review=review.copy(dueStart=it.ifBlank { null }) },label={ Text("期限开始 YYYY-MM-DD，可留空") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.dueEnd.orEmpty(),{ review=review.copy(dueEnd=it.ifBlank { null }) },label={ Text("期限结束 YYYY-MM-DD，可留空") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            datePrecisions.forEach { (value,label) -> FilterChip(selected=review.datePrecision==value,onClick={ review=review.copy(datePrecision=value) },label={ Text(label) },enabled=!busy) }
        }
        OutlinedTextField(review.dateText,{ review=review.copy(dateText=it) },label={ Text("日期原文") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.timezone,{ review=review.copy(timezone=it) },label={ Text("时区") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.verificationCriteria,{ review=review.copy(verificationCriteria=it) },label={ Text("验证标准") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(review.notes,{ review=review.copy(notes=it) },label={ Text("备注") },enabled=!busy,modifier=Modifier.fillMaxWidth())
    }
    val errors=RecordRules.validate(review)
    errors.values.distinct().forEach { Text(it,color=MaterialTheme.colorScheme.error) }
    Button(onClick={ onAccept(review) },enabled=!busy && errors.isEmpty()) { Text("确认收录核对后的候选") }
}

private fun jobStatus(status: String)=when(status) { "QUEUED"->"排队中"; "RUNNING"->"调查中"; "SUCCEEDED"->"已完成"; else->"未完成，可稍后重新发起" }
@Composable private fun DraftPreview(content: RecordContent) {
    Text(content.originalText)
    Text("主体：${content.subject}；类型：${recordTypes[content.recordType]}")
    Text("说出日期：${content.saidAt ?: "未知"}；期限：${content.dueStart ?: "未知"} 至 ${content.dueEnd ?: "未知"}")
    Text("日期原文：${content.dateText.ifBlank { "未提供" }}；时区：${content.timezone}")
    Text("验证标准：${content.verificationCriteria.ifBlank { "待补充" }}")
}
@Composable private fun SourceView(source: AssistantSource) {
    val uriHandler=LocalUriHandler.current
    var error by remember(source.url) { mutableStateOf(false) }
    Text(source.title,style=MaterialTheme.typography.titleSmall)
    if(source.quote.isNotBlank()) Text("引文：${source.quote}")
    if(source.excerpt.isNotBlank()) Text("摘录：${source.excerpt}")
    TextButton(onClick={ if(AssistantCodec.safeUrl(source.url)) error=runCatching { uriHandler.openUri(source.url) }.isFailure },enabled=AssistantCodec.safeUrl(source.url)) { Text("打开来源网页") }
    if(error) Text("无法打开此链接。")
}
