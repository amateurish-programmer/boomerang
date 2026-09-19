package com.boomerang.app.updates

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val currentVersion: String = "读取中", val stage: UpdateStage = UpdateStage.IDLE,
    val manifest: UpdateManifest? = null, val downloaded: Long = 0,
    val busy: Boolean = false, val canInstall: Boolean = false, val message: String? = null,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val platform = AndroidUpdatePlatform(application)
    private val repository = platform.repository()
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private val actions = Channel<Intent>(Channel.BUFFERED)
    val systemActions = actions.receiveAsFlow()
    private var operation: Job? = null

    init {
        runOperation {
            val installed = platform.installed()
            val restored = repository.restore()
            _state.update { it.copy(currentVersion = "${installed.versionName} (${installed.versionCode})", stage = restored.stage,
                manifest = restored.manifest, canInstall = platform.canInstall(), message = when (restored.stage) {
                    UpdateStage.READY -> "安装包已重新校验，可以安装"
                    UpdateStage.RETRY -> "上次下载未完成或已失效，请重新下载"
                    else -> null
                }) }
        }
    }

    fun refreshPermission() {
        _state.update { it.copy(canInstall = platform.canInstall()) }
    }

    fun check() = runOperation {
        val manifest = repository.check()
        val installed = platform.installed()
        val available = UpdatePolicy.isAvailable(manifest, installed, Build.VERSION.SDK_INT)
        val saved = repository.restore()
        val ready = saved.stage == UpdateStage.READY && saved.manifest == manifest
        _state.update { it.copy(manifest = manifest, stage = when {
            !available -> UpdateStage.IDLE
            ready -> UpdateStage.READY
            else -> UpdateStage.AVAILABLE
        }, message = when {
            manifest.versionCode <= installed.versionCode -> "当前已是最新版本"
            manifest.minSdk > Build.VERSION.SDK_INT -> "新版本需要更高版本的 Android 系统"
            ready -> "安装包已重新校验，可以安装"
            else -> "发现新版本，可查看更新说明后下载"
        }) }
    }

    fun download() {
        val manifest = _state.value.manifest ?: return
        runOperation {
            _state.update { it.copy(stage = UpdateStage.DOWNLOADING, downloaded = 0) }
            try {
                repository.download(manifest) { count -> _state.update { it.copy(downloaded = count) } }
                _state.update { it.copy(stage = UpdateStage.READY, message = "安装包校验通过，请点击安装") }
            } catch (error: Exception) {
                _state.update { it.copy(stage = UpdateStage.RETRY) }
                throw error
            }
        }
    }

    fun cancelDownload() {
        if (_state.value.stage == UpdateStage.DOWNLOADING) {
            _state.update { it.copy(message = "正在取消下载…") }
            operation?.cancel()
        }
    }

    fun openPermissionSettings() = runOperation { actions.send(platform.permissionIntent()) }

    fun install() = runOperation {
        refreshPermission()
        if (!_state.value.canInstall) throw UpdateException("请先允许回旋镖安装应用，再点击安装")
        try {
            val file = repository.prepareInstall(platform.canInstall())
            // Returning from Settings never reaches here automatically: only the install button does.
            if (!platform.canInstall()) throw UpdateException("安装权限已关闭，请重新开启")
            actions.send(platform.installIntent(file))
            _state.update { it.copy(message = "请在系统界面确认安装；如果取消，可再次点击安装") }
        } catch (error: UpdateException) {
            val restored = repository.restore()
            _state.update { it.copy(stage = restored.stage, manifest = restored.manifest ?: it.manifest) }
            throw error
        }
    }

    fun systemActionFailed() { _state.update { it.copy(message = "无法打开系统界面，请稍后重试") } }

    private fun runOperation(action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) {
                _state.update { it.copy(message = "下载已取消，可重新下载") }
                throw error
            }
            catch (error: Exception) {
                _state.update { it.copy(message = (error as? UpdateException)?.message ?: "更新未完成，请稍后重试") }
            }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}
