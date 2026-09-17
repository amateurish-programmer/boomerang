package com.boomerang.app.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.boomerang.app.data.*
import com.boomerang.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import org.json.JSONObject

enum class Destination(val label: String) { HOME("首页"), LIBRARY("镖库"), AI("AI"), PROFILE("我的") }
data class EditorState(val content: RecordContent = RecordContent(), val sources: List<SourceInput> = emptyList(), val id: String? = null, val revision: Long? = null)
data class LibraryState(val loading: Boolean = true, val records: List<RecordEntity> = emptyList(), val error: String? = null)
data class HistoryDisplay(val version: Long, val createdAt: String, val fields: List<Pair<String, String>>)

class ShellViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private val database = BoomerangDatabase.open(application)
    private val repository = BoomerangRepository(database)
    private val clock = Clock.systemUTC()
    val destination = state.getStateFlow("destination", Destination.HOME.name)
    val screen = state.getStateFlow("screen", "main")
    val query = state.getStateFlow("query", "")
    val typeFilter = state.getStateFlow("typeFilter", "")
    val resultFilter = state.getStateFlow("resultFilter", "")
    val sort = state.getStateFlow("sort", "due")
    val editor = state.getStateFlow("draft", Bundle()).map(::readDraft).stateIn(viewModelScope, SharingStarted.Eagerly, readDraft(state["draft"] ?: Bundle()))
    private val _library = MutableStateFlow(LibraryState())
    val library = _library.asStateFlow()
    private val _detail = MutableStateFlow<RecordDetail?>(null)
    val detail = _detail.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { load(); if (state.get<String>("screen") == "detail") state.get<String>("selectedId")?.let(::openDetail) }
    private fun load() = viewModelScope.launch {
        _library.value = LibraryState(loading = true)
        repository.observeRecords().catch { _library.value = LibraryState(loading = false, error = "读取本机记录失败，请重试") }
            .collect { _library.value = LibraryState(loading = false, records = it) }
    }
    fun retry() { load() }
    fun select(destination: Destination) { state["destination"] = destination.name }
    fun setQuery(value: String) { state["query"] = value }
    fun setType(value: String) { state["typeFilter"] = value }
    fun setResult(value: String) { state["resultFilter"] = value }
    fun setSort(value: String) { state["sort"] = value }
    fun filtered(records: List<RecordEntity>, query: String, type: String, result: String, sort: String): List<RecordEntity> {
        val selected = records.filter { row ->
            val c = row.content
            (type.isEmpty() || c.recordType == type) &&
                (result.isEmpty() || if (result == "UNCONFIRMED") c.confirmedStatus == null else c.confirmedStatus == result) &&
                (query.isBlank() || listOf(c.originalText, c.subject, c.topic, c.notes).any { it.contains(query.trim(), ignoreCase = true) })
        }
        return if (sort == "updated") selected.sortedByDescending { it.updatedAt }
        else selected.sortedWith(compareBy<RecordEntity> { it.content.dueEnd == null }.thenBy { it.content.dueEnd }.thenByDescending { it.updatedAt })
    }
    fun countdown(record: RecordEntity) = RecordRules.countdown(record.content, clock)
    fun history(detail: RecordDetail?): List<HistoryDisplay> = detail?.history.orEmpty().map { revision ->
        val snapshot = JSONObject(revision.snapshotJson)
        val row = snapshot.getJSONObject("record")
        val labels = linkedMapOf("original_text" to "原话", "subject" to "主体", "record_type" to "类型", "said_at" to "说出日期",
            "due_start" to "开始日期", "due_end" to "结束日期", "date_text" to "日期原文", "date_precision" to "日期精度", "timezone" to "时区",
            "verification_criteria" to "验证标准", "topic" to "主题", "notes" to "备注", "lifecycle" to "记录状态", "confirmed_status" to "确认结果", "deleted_at" to "删除时间")
        val fields = labels.map { (key, label) -> label to if (row.isNull(key)) "未填写" else row.optString(key).ifBlank { "未填写" } }.toMutableList()
        val sources = snapshot.getJSONArray("sources")
        for (i in 0 until sources.length()) { val source = sources.getJSONObject(i); fields += source.getString("title") to source.getString("url") }
        HistoryDisplay(revision.localRevision, revision.createdAt, fields)
    }
    fun back() { if (!_busy.value) {
        val editingId = currentDraft().id
        if (screen.value == "editor" && editingId != null) openDetail(editingId) else state["screen"] = "main"
        _message.value = null
    } }
    fun openEditor() {
        if (currentDraft().id != null) writeDraft(EditorState())
        _errors.value = emptyMap(); _message.value = null; state["screen"] = "editor"
    }
    fun editCurrent() {
        val detail = _detail.value ?: return
        val draft = currentDraft()
        if (draft.id != detail.record.id || draft.revision != detail.record.localRevision) {
            writeDraft(EditorState(detail.record.content, detail.sources.map { SourceInput(it.title, it.url) }, detail.record.id, detail.record.localRevision))
        }
        _errors.value = emptyMap(); _message.value = null; state["screen"] = "editor"
    }
    fun updateContent(content: RecordContent) { writeDraft(currentDraft().copy(content = content)) }
    fun updateSources(sources: List<SourceInput>) { writeDraft(currentDraft().copy(sources = sources)) }
    fun openDetail(id: String) {
        state["selectedId"] = id; state["screen"] = "detail"; _detail.value = null
        viewModelScope.launch {
            try {
                val loaded = repository.detail(id)
                if (state.get<String>("selectedId") == id) {
                    _detail.value = loaded; _message.value = if (loaded == null) "记录不存在" else null
                }
            }
            catch (error: Exception) { if (error is CancellationException) throw error; _message.value = "读取详情失败，请返回后重试" }
        }
    }
    fun save() {
        if (_busy.value) return
        val draft = currentDraft()
        _errors.value = RecordRules.validate(draft.content) + RecordRules.sourceErrors(draft.sources)
        if (_errors.value.isNotEmpty()) return
        _busy.value = true; _message.value = null
        viewModelScope.launch {
            try {
                val id = repository.save(draft.content, draft.sources, draft.id, draft.revision)
                writeDraft(EditorState()); _busy.value = false; openDetail(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ValidationException) _errors.value = error.errors
                _message.value = if (error is com.boomerang.app.data.LocalConflictException) error.message else "保存失败，输入已保留，请重试"
            } finally { _busy.value = false }
        }
    }
    fun deleteCurrent() {
        val detail = _detail.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                repository.softDelete(detail.record.id, detail.record.localRevision)
                state["selectedId"] = null; state["screen"] = "main"; state["destination"] = Destination.LIBRARY.name; _detail.value = null
            } catch (error: Exception) { if (error is CancellationException) throw error; _message.value = "删除失败，请重新打开记录后重试" }
            finally { _busy.value = false }
        }
    }
    private fun currentDraft() = readDraft(state["draft"] ?: Bundle())
    private fun writeDraft(value: EditorState) { state["draft"] = Bundle().apply {
        val c = value.content
        putString("id", value.id); value.revision?.let { putLong("revision", it) }
        putString("recordType", c.recordType); putString("originalText", c.originalText); putString("subject", c.subject)
        putString("topic", c.topic); putString("lifecycle", c.lifecycle); putString("confirmedStatus", c.confirmedStatus)
        putString("saidAt", c.saidAt); putString("dueStart", c.dueStart); putString("dueEnd", c.dueEnd)
        putString("dateText", c.dateText); putString("datePrecision", c.datePrecision); putString("timezone", c.timezone)
        putString("verificationCriteria", c.verificationCriteria); putString("notes", c.notes)
        putStringArrayList("sourceTitles", ArrayList(value.sources.map { it.title })); putStringArrayList("sourceUrls", ArrayList(value.sources.map { it.url }))
    } }
    private fun readDraft(bundle: Bundle): EditorState = EditorState(
        RecordContent(recordType = (bundle.getString("recordType") ?: "FLAG"), originalText = (bundle.getString("originalText") ?: ""),
            subject = (bundle.getString("subject") ?: "我"), topic = (bundle.getString("topic") ?: ""), lifecycle = (bundle.getString("lifecycle") ?: "ACTIVE"),
            confirmedStatus = bundle.getString("confirmedStatus"), saidAt = bundle.getString("saidAt"), dueStart = bundle.getString("dueStart"), dueEnd = bundle.getString("dueEnd"),
            dateText = (bundle.getString("dateText") ?: ""), datePrecision = (bundle.getString("datePrecision") ?: "UNKNOWN"), timezone = (bundle.getString("timezone") ?: "Asia/Shanghai"),
            verificationCriteria = (bundle.getString("verificationCriteria") ?: ""), notes = (bundle.getString("notes") ?: "")),
        (bundle.getStringArrayList("sourceTitles") ?: arrayListOf()).zip(bundle.getStringArrayList("sourceUrls") ?: arrayListOf()) { title, url -> SourceInput(title, url) },
        bundle.getString("id"), if (bundle.containsKey("revision")) bundle.getLong("revision") else null,
    )
    override fun onCleared() { database.close() }
}
