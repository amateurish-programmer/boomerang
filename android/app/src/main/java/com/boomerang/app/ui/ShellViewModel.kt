package com.boomerang.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

enum class Destination(val label: String) { HOME("首页"), LIBRARY("镖库"), AI("AI"), PROFILE("我的") }

/** Saved navigation and transient editor state; no pretend persistence or demo records. */
class ShellViewModel(private val state: SavedStateHandle) : ViewModel() {
    val destination = state.getStateFlow("destination", Destination.HOME.name)
    val screen = state.getStateFlow("screen", "main")
    val quote = state.getStateFlow("quote", "")
    fun select(destination: Destination) { state["destination"] = destination.name }
    fun openEditor() { state["screen"] = "editor" }
    fun openDetail() { state["screen"] = "detail" }
    fun back() { state["screen"] = "main" }
    fun editQuote(value: String) { state["quote"] = value.take(4000) }
}
