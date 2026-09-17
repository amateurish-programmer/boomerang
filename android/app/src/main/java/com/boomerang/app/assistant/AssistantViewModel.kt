package com.boomerang.app.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boomerang.app.cloud.*
import com.boomerang.app.domain.RecordContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

data class AssistantState(
    val busy: Boolean = false, val message: String? = null, val draft: AssistantDraft? = null,
    val conversations: List<AssistantConversation> = emptyList(), val sessionId: String? = null,
    val messages: List<AssistantMessage> = emptyList(), val jobs: List<AssistantJob> = emptyList(),
    val candidates: List<AssistantCandidate> = emptyList(), val records: List<AssistantRecord> = emptyList(),
    val selectedRecord: AssistantRecord? = null, val checks: List<AssistantCheck> = emptyList(),
    val selectedCheck: AssistantCheck? = null, val evidence: List<AssistantEvidence> = emptyList(),
    val openRecord: String? = null,
    val weeklySummary: String? = null,
    val notifications: List<AssistantNotification> = emptyList(),
)

class AssistantViewModel(application: Application, owner: String) : AndroidViewModel(application) {
    private val transport=UrlConnectionTransport()
    private val repository=AssistantRepository(AuthRepository(transport,KeystoreSessionStore(application)),transport,owner)
    private val mutable=MutableStateFlow(AssistantState())
    val state=mutable.asStateFlow()
    // Retain operation IDs for identical retries while this screen is alive; process recovery lists server jobs.
    private val operations=mutableMapOf<String,String>()
    private fun operation(key: String)=operations.getOrPut(key) { UUID.randomUUID().toString() }
    private fun action(block: suspend () -> Unit) {
        if(mutable.value.busy) return
        viewModelScope.launch {
            mutable.value=mutable.value.copy(busy=true,message=null)
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val message=when {
                    e is CloudHttpException && e.status==503 -> "AI 服务尚未配置或暂不可用。你仍可手动记录，已保存的调查任务会保留。"
                    e is CloudHttpException && e.status==409 -> "记录已经更新，旧复核不能确认。请刷新后重新复核。"
                    e is CloudException -> e.message
                    e is IllegalArgumentException -> "请检查输入的内容、日期与时区。"
                    else -> "返回内容无法安全读取，请稍后重试。"
                }
                mutable.value=mutable.value.copy(message=message)
            } finally { mutable.value=mutable.value.copy(busy=false) }
        }
    }
    init { refresh() }
    fun refresh()=action {
        val jobs=repository.jobs(); val conversations=repository.conversations(); val records=repository.records()
        val notifications=repository.notifications()
        mutable.value=mutable.value.copy(jobs=jobs,conversations=conversations,records=records,notifications=notifications)
    }
    fun analyze(text: String,date: String,zone: String)=action {
        mutable.value=mutable.value.copy(draft=null)
        mutable.value=mutable.value.copy(draft=repository.analyze(text,date,zone))
    }
    fun dismissDraft() { mutable.value=mutable.value.copy(draft=null) }
    fun conversation(id: String)=action { mutable.value=mutable.value.copy(sessionId=id,messages=repository.messages(id)) }
    fun newConversation() { if(!mutable.value.busy) mutable.value=mutable.value.copy(sessionId=null,messages=emptyList()) }
    fun chat(text: String)=action {
        val (id,_)=repository.chat(text,mutable.value.sessionId)
        mutable.value=mutable.value.copy(sessionId=id)
        mutable.value=mutable.value.copy(messages=repository.messages(id),conversations=repository.conversations())
    }
    fun research(topic: String,date: String,zone: String)=action {
        val key="research:$topic:$date:$zone"
        repository.research(topic,date,zone,operation(key)); operations.remove(key)
        mutable.value=mutable.value.copy(jobs=repository.jobs(),message="调查已加入队列。可以离开此页，稍后刷新查看。")
    }
    fun candidates(id: String)=action {
        mutable.value=mutable.value.copy(candidates=emptyList())
        val candidates=repository.candidates(id)
        mutable.value=mutable.value.copy(candidates=candidates,message=if(candidates.isEmpty()) "暂无候选。进行中的任务请稍后刷新；已完成但没有候选时，不会编造结果。" else null)
    }
    fun accept(candidate: AssistantCandidate)=action {
        val id=repository.accept(candidate,operation("accept:${candidate.id}"))
        mutable.value=mutable.value.copy(openRecord=id)
    }
    fun acceptReviewed(candidate: AssistantCandidate,review: RecordContent)=action {
        val id=repository.acceptReviewed(candidate,review,operation("reviewed:${candidate.id}:$review"))
        mutable.value=mutable.value.copy(openRecord=id)
    }
    fun readNotification(notification: AssistantNotification)=action {
        repository.readNotification(notification.id)
        mutable.value=mutable.value.copy(notifications=repository.notifications(),openRecord=notification.recordId)
    }
    fun selectRecord(record: AssistantRecord)=action {
        mutable.value=mutable.value.copy(selectedRecord=record,checks=emptyList(),selectedCheck=null,evidence=emptyList())
        mutable.value=mutable.value.copy(checks=repository.checks(record.id))
    }
    fun verify(record: AssistantRecord)=action {
        val key="verify:${record.id}:${record.revision}"
        repository.verify(record,operation(key)); operations.remove(key)
        mutable.value=mutable.value.copy(jobs=repository.jobs(),message="复核已排队。稍后刷新并重新选择记录查看建议。")
    }
    fun inspect(check: AssistantCheck)=action {
        mutable.value=mutable.value.copy(selectedCheck=null,evidence=emptyList())
        val evidence=repository.evidence(check)
        mutable.value=mutable.value.copy(selectedCheck=check,evidence=evidence)
    }
    fun confirm(check: AssistantCheck,status: String,reason: String)=action {
        mutable.value=mutable.value.copy(openRecord=repository.confirm(check,status,reason))
    }
    fun consumedNavigation() { mutable.value=mutable.value.copy(openRecord=null) }
    fun weekly()=action {
        mutable.value=mutable.value.copy(weeklySummary=null)
        mutable.value=mutable.value.copy(weeklySummary=repository.weekly(LocalDate.now(),ZoneId.systemDefault()))
    }
}
