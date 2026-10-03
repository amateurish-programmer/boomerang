package com.boomerang.app.data

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import com.boomerang.app.domain.InkMotionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device appearance is independent of account databases and backups. Owned by ShellViewModel. */
class AppearanceRepository(context: Context) : AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("ink_appearance", Context.MODE_PRIVATE)
    private val _motionMode = MutableStateFlow(readMode())
    val motionMode = _motionMode.asStateFlow()
    private val _systemScale = MutableStateFlow(readScale())
    val systemScale = _systemScale.asStateFlow()
    private var closed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && key == MOTION_KEY) _motionMode.value = readMode()
    }
    private val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { if (!closed) _systemScale.value = readScale() }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver)
        // Re-read after registration so an intervening system/preference change is not missed.
        _motionMode.value = readMode()
        _systemScale.value = readScale()
    }

    fun setMotionMode(mode: InkMotionMode) {
        if (closed) return
        preferences.edit { putString(MOTION_KEY, mode.name) }
        _motionMode.value = mode
    }

    private fun readMode() = InkMotionMode.fromPreference(runCatching { preferences.getString(MOTION_KEY, null) }.getOrNull())
    private fun readScale(): Float = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f).let { if (it.isFinite() && it >= 0f) it else 1f }

    override fun close() {
        if (closed) return
        closed = true
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        resolver.unregisterContentObserver(scaleObserver)
    }

    private companion object { const val MOTION_KEY = "motion_mode" }
}
