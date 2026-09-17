package com.boomerang.app.extras

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.RecordDetail
import com.boomerang.app.reminders.ReminderScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class ExtrasState(val owner: String = "", val records: List<RecordDetail> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val preview: ImportPreview? = null, val card: File? = null)

class ExtrasViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ExtrasState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    fun selectOwner(owner: String) {
        if (_state.value.owner == owner) return
        work?.cancel(); _state.value = ExtrasState(owner = owner)
        refresh()
    }
    private suspend fun <T> repository(owner: String, block: suspend (ExtrasRepository) -> T): T = withContext(Dispatchers.IO) {
        val db = BoomerangDatabase.open(getApplication(), owner)
        try { block(ExtrasRepository(db, owner)) } finally { db.close() }
    }
    private fun run(owner: String = _state.value.owner, operation: suspend () -> Unit) {
        if (_state.value.busy || owner != _state.value.owner || owner.isEmpty()) return
        _state.value = _state.value.copy(busy = true, message = null)
        work = viewModelScope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (_state.value.owner == owner) _state.value = _state.value.copy(message = e.message?.take(200) ?: "操作失败，请重试") }
            finally { if (_state.value.owner == owner) _state.value = _state.value.copy(busy = false) }
        }
    }
    fun refresh() {
        val owner = _state.value.owner
        run(owner) { val backup = repository(owner) { it.export() }; if (_state.value.owner == owner) _state.value = _state.value.copy(records = backup.records) }
    }
    fun export(uri: Uri, owner: String) = run(owner) {
        val bytes = repository(owner) { BackupCodec.encode(it.export()) }
        withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入所选文件") }
        if (_state.value.owner == owner) _state.value = _state.value.copy(message = "JSON 备份已导出；包含私人备注，请妥善保存")
    }
    fun previewImport(uri: Uri, owner: String) = run(owner) {
        val bytes = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= BackupCodec.MAX_BYTES) { "备份文件不能超过 5 MiB" }; output.write(buffer, 0, size) }
                output.toByteArray()
            } ?: error("无法读取所选文件")
        }
        val preview = repository(owner) { it.preview(bytes) }
        if (_state.value.owner == owner) _state.value = _state.value.copy(preview = preview)
    }
    fun cancelImport() { if (!_state.value.busy) _state.value = _state.value.copy(preview = null) }
    fun confirmImport(choice: ImportChoice) {
        val preview = _state.value.preview ?: return
        val owner = _state.value.owner
        run(owner) {
            val count = repository(owner) { it.importBackup(preview, choice) }
            val backup = repository(owner) { it.export() }
            if (_state.value.owner == owner) { _state.value = _state.value.copy(records = backup.records, preview = null, message = "已导入 $count 条记录"); ReminderScheduler.scan(getApplication()) }
        }
    }
    fun previewCard(id: String) {
        val owner = _state.value.owner
        run(owner) {
            val detail = repository(owner) { it.export().records.firstOrNull { row -> row.record.id == id && row.record.deletedAt == null } } ?: error("记录已删除，请刷新")
            val file = withContext(Dispatchers.Default) { ShareCard.render(getApplication(), detail) }
            if (_state.value.owner == owner) _state.value = _state.value.copy(card = file)
        }
    }
    fun closeCard() { _state.value = _state.value.copy(card = null) }
}
