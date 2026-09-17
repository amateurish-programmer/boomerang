package com.boomerang.app.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.boomerang.app.data.*
import com.boomerang.app.domain.*
import com.boomerang.app.cloud.*
import com.boomerang.app.reminders.ReminderScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import org.json.JSONObject

enum class Destination(val label: String) { HOME("首页"), LIBRARY("镖库"), AI("AI"), PROFILE("我的") }
data class EditorState(val content: RecordContent = RecordContent(), val sources: List<SourceInput> = emptyList(), val id: String? = null, val revision: Long? = null)
data class LibraryState(val loading: Boolean = true, val records: List<RecordEntity> = emptyList(), val error: String? = null)
data class AccountUiState(val ready: Boolean = false, val userId: String? = null, val message: String? = null, val importPreview: List<RecordDetail> = emptyList(), val conflicts: List<ConflictDisplay> = emptyList())
data class ConflictDisplay(val id: String, val local: String, val cloud: String)
data class HistoryDisplay(val version: Long, val createdAt: String, val fields: List<Pair<String, String>>)

class ShellViewModel(application: Application, private val state: SavedStateHandle) : AndroidViewModel(application) {
    private var database = BoomerangDatabase.open(application)
    private var repository = BoomerangRepository(database)
    private val auth = AuthRepository(UrlConnectionTransport(), KeystoreSessionStore(application))
    private val backend = BackendRepository(UrlConnectionTransport())
    private val _ownerNamespace = MutableStateFlow("local")
    val ownerNamespace = _ownerNamespace.asStateFlow()
    private val _account = MutableStateFlow(AccountUiState())
    val account = _account.asStateFlow()
    private var libraryJob: Job? = null
    private var detailJob: Job? = null
    private var pendingReminder: Pair<String, String>? = null
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

    init {
        viewModelScope.launch {
            try { switchAccount(auth.currentSession()?.userId, restoring = true) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _account.value = AccountUiState(message = (error as? CloudException)?.message ?: "账号初始化失败，请退出后重试")
                _library.value = LibraryState(false, error = "请先到“我的”恢复账号")
            }
        }
    }
    private suspend fun switchAccount(userId: String?, restoring: Boolean = false) {
        val owner = userId ?: "local"
        _account.value = _account.value.copy(ready = false)
        libraryJob?.cancelAndJoin()
        detailJob?.cancelAndJoin()
        _library.value = LibraryState(); _detail.value = null
        if (!restoring || state.get<String>("draftOwner")?.let { it != owner } == true) {
            writeDraft(EditorState()); state["selectedId"] = null; state["screen"] = "main"
        }
        state["draftOwner"] = owner
        if (repository.ownerNamespace != owner) {
            val old = database
            database = BoomerangDatabase.open(getApplication(), owner)
            repository = BoomerangRepository(database, owner)
            old.close()
        }
        _ownerNamespace.value = owner
        _account.value = AccountUiState(ready = true, userId = userId)
        ReminderScheduler.activate(getApplication(), owner)
        libraryJob = load()
        if (restoring && screen.value == "detail") state.get<String>("selectedId")?.let(::openDetail)
        refreshConflicts()
        val notification = pendingReminder
        pendingReminder = null
        if (notification?.first == owner) openDetail(notification.second)
    }
    private fun accountAction(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _account.value = _account.value.copy(message = (error as? CloudException)?.message ?: "操作未完成，本机记录已保留，请重试")
            } finally { _busy.value = false }
        }
    }
    fun signIn(email: String, password: String) = accountAction { switchAccount(auth.signIn(email, password).userId) }
    fun signUp(email: String, password: String) = accountAction {
        when (val result = auth.signUp(email, password)) {
            is SignUpResult.SignedIn -> switchAccount(result.session.userId)
            SignUpResult.EmailConfirmationRequired -> { _account.value = _account.value.copy(message = "请查收验证邮件，完成验证后再登录。") }
        }
    }
    fun signOut() = accountAction {
        var remoteFailure = false
        try { auth.signOut() } catch (error: CloudException) { remoteFailure = true }
        // Only switch after verified local clear, including offline logout.
        if (auth.currentSession() != null) throw CredentialsUnavailable()
        switchAccount(null)
        if (remoteFailure) _account.value = _account.value.copy(message = "已在本机退出。云端连接失败，远端会话撤销尚未确认。")
    }
    fun sync() = accountAction {
        val owner = repository.ownerNamespace
        val local = RoomSyncStore(database, owner)
        val result = SyncEngine(local, backend.syncRemote()) { _ownerNamespace.value == owner }.sync(auth.activeSession())
        refreshConflicts()
        _account.value = _account.value.copy(message = "同步完成：上传 ${result.uploaded} 条，读取 ${result.downloaded} 条，冲突 ${result.conflicts} 条。")
        ReminderScheduler.scan(getApplication())
    }
    private suspend fun refreshConflicts() {
        if (repository.ownerNamespace == "local") return
        val conflicts = RoomSyncStore(database, repository.ownerNamespace).conflicts().map { conflict ->
            ConflictDisplay(conflict.recordId, repository.detail(conflict.recordId)?.let { RecordSnapshot.encode(it.record, it.sources) }.orEmpty(), JSONObject().put("record", JSONObject(conflict.remoteRecordJson)).put("sources", org.json.JSONArray(conflict.remoteSourcesJson)).toString())
        }
        _account.value = _account.value.copy(conflicts = conflicts)
    }
    fun resolveConflict(id: String, useLocal: Boolean) = accountAction {
        RoomSyncStore(database, repository.ownerNamespace).resolve(id, useLocal)
        refreshConflicts()
        _account.value = _account.value.copy(message = if (useLocal) "已保留本机版本，请同步提交。双方历史均保留。" else "已采用云端版本，双方历史均保留。")
    }
    fun previewAnonymous() = accountAction {
        require(repository.ownerNamespace != "local")
        val localDb = BoomerangDatabase.open(getApplication())
        try {
            val anonymous = BoomerangRepository(localDb)
            val existing = repository.exportRecords().map { it.id }.toSet()
            val preview = anonymous.exportRecords().filter { it.deletedAt == null && repository.anonymousTargetId(it.id) !in existing }.mapNotNull { anonymous.detail(it.id) }
            _account.value = _account.value.copy(importPreview = preview, message = if (preview.isEmpty()) "没有可导入的新本机记录。" else "选择记录后确认导入；本机原记录仍会保留。")
        } finally { localDb.close() }
    }
    fun importAnonymous(ids: Set<String>) = accountAction {
        val selection = _account.value.importPreview.filter { it.record.id in ids }
        require(selection.isNotEmpty() && selection.size == ids.size)
        repository.importAnonymous(selection)
        _account.value = _account.value.copy(importPreview = emptyList(), message = "已导入 ${selection.size} 条，请同步上传。")
        ReminderScheduler.scan(getApplication())
    }
    fun syncAndOpenDetail(id: String) = accountAction {
        val owner = repository.ownerNamespace
        SyncEngine(RoomSyncStore(database, owner), backend.syncRemote()) { _ownerNamespace.value == owner }.sync(auth.activeSession())
        refreshConflicts()
        openDetail(id)
    }
    fun openAiDraft(content: RecordContent) {
        if (!_account.value.ready || _busy.value) return
        writeDraft(EditorState(content.copy(confirmedStatus = null)))
        _errors.value = emptyMap(); state["screen"] = "editor"
    }
    fun openReminder(owner: String, id: String) {
        if (!_account.value.ready) {
            pendingReminder = owner to id
            return
        }
        if (owner == _ownerNamespace.value) openDetail(id)
    }
    private fun load() = viewModelScope.launch {
        _library.value = LibraryState(loading = true)
        repository.observeRecords().catch { _library.value = LibraryState(loading = false, error = "读取本机记录失败，请重试") }
            .collect { _library.value = LibraryState(loading = false, records = it) }
    }
    fun retry() { if (_account.value.ready) { libraryJob?.cancel(); libraryJob = load() } }
    fun openExtras() { if (_account.value.ready && !_busy.value) state["screen"] = "extras" }
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
            "verification_criteria" to "验证标准", "topic" to "主题", "notes" to "备注", "lifecycle" to "记录状态", "confirmed_status" to "确认结果", "deleted_at" to "删除时间", "capsule_locked_at" to "封存时间", "capsule_unlock_at" to "解锁时间")
        val fields = labels.map { (key, label) -> label to if (row.isNull(key)) "未填写" else row.optString(key).ifBlank { "未填写" } }.toMutableList()
        if (snapshot.optBoolean("untrusted_archive", false)) fields.add(0, "来源" to "外部备份存档，未核验，不代表当前确认结果")
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
        if (!_account.value.ready || _busy.value) return
        if (currentDraft().id != null) writeDraft(EditorState())
        _errors.value = emptyMap(); _message.value = null; state["screen"] = "editor"
    }
    fun editCurrent() {
        val detail = _detail.value ?: return
        val draft = currentDraft()
        if (draft.id != detail.record.id || draft.revision != detail.record.localRevision) {
            writeDraft(EditorState(detail.record.content, detail.sources.filter { it.clientEditable() }.map { SourceInput(it.title, it.url) }, detail.record.id, detail.record.localRevision))
        }
        _errors.value = emptyMap(); _message.value = null; state["screen"] = "editor"
    }
    fun updateContent(content: RecordContent) { writeDraft(currentDraft().copy(content = content)) }
    fun updateSources(sources: List<SourceInput>) { writeDraft(currentDraft().copy(sources = sources)) }
    fun openDetail(id: String) {
        if (!_account.value.ready) return
        state["selectedId"] = id; state["screen"] = "detail"; _detail.value = null
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
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
        if (_busy.value || !_account.value.ready) return
        val draft = currentDraft()
        _errors.value = RecordRules.validate(draft.content) + RecordRules.sourceErrors(draft.sources)
        if (_errors.value.isNotEmpty()) return
        _busy.value = true; _message.value = null
        viewModelScope.launch {
            try {
                val id = repository.save(draft.content, draft.sources, draft.id, draft.revision)
                ReminderScheduler.scan(getApplication())
                writeDraft(EditorState()); _busy.value = false; openDetail(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is ValidationException) _errors.value = error.errors
                _message.value = if (error is com.boomerang.app.data.LocalConflictException || error is IllegalArgumentException) error.message else "保存失败，输入已保留，请重试"
            } finally { _busy.value = false }
        }
    }
    fun lockCurrent() {
        val detail = _detail.value ?: return
        if (_busy.value || detail.record.content.capsuleLockedAt != null) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val unlock = CapsulePolicy.unlockAt(detail.record.content)
                require(java.time.Instant.parse(unlock) > clock.instant()) { "请先设置尚未结束的期限" }
                val content = detail.record.content.copy(capsuleLockedAt = clock.instant().toString(), capsuleUnlockAt = unlock)
                repository.save(content, detail.sources.filter { it.clientEditable() }.map { SourceInput(it.title, it.url) }, detail.record.id, detail.record.localRevision)
                openDetail(detail.record.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _message.value = (error as? IllegalArgumentException)?.message ?: "封存失败，请重试"
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
                ReminderScheduler.scan(getApplication())
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
        putString("capsuleLockedAt", c.capsuleLockedAt); putString("capsuleUnlockAt", c.capsuleUnlockAt)
        putStringArrayList("sourceTitles", ArrayList(value.sources.map { it.title })); putStringArrayList("sourceUrls", ArrayList(value.sources.map { it.url }))
    } }
    private fun readDraft(bundle: Bundle): EditorState = EditorState(
        RecordContent(recordType = (bundle.getString("recordType") ?: "FLAG"), originalText = (bundle.getString("originalText") ?: ""),
            subject = (bundle.getString("subject") ?: "我"), topic = (bundle.getString("topic") ?: ""), lifecycle = (bundle.getString("lifecycle") ?: "ACTIVE"),
            confirmedStatus = bundle.getString("confirmedStatus"), saidAt = bundle.getString("saidAt"), dueStart = bundle.getString("dueStart"), dueEnd = bundle.getString("dueEnd"),
            dateText = (bundle.getString("dateText") ?: ""), datePrecision = (bundle.getString("datePrecision") ?: "UNKNOWN"), timezone = (bundle.getString("timezone") ?: "Asia/Shanghai"),
            verificationCriteria = (bundle.getString("verificationCriteria") ?: ""), notes = (bundle.getString("notes") ?: ""),
            capsuleLockedAt = bundle.getString("capsuleLockedAt"), capsuleUnlockAt = bundle.getString("capsuleUnlockAt")),
        (bundle.getStringArrayList("sourceTitles") ?: arrayListOf()).zip(bundle.getStringArrayList("sourceUrls") ?: arrayListOf()) { title, url -> SourceInput(title, url) },
        bundle.getString("id"), if (bundle.containsKey("revision")) bundle.getLong("revision") else null,
    )
    override fun onCleared() { database.close() }
}
