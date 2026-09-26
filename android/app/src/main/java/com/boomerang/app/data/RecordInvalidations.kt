package com.boomerang.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription

/** Committed writes from short-lived database instances must also reach existing library readers. */
internal object RecordInvalidations {
    private val commits = MutableSharedFlow<String>()

    fun observe(owner: String): Flow<String> = commits
        // Register first, then start the initial query: a concurrent commit cannot fall in between.
        .onSubscription { emit(owner) }
        .filter { it == owner }
        // Coalesce only this owner's events; a slow reader must not hold a committed import open.
        .buffer(Channel.CONFLATED)

    suspend fun committed(owner: String) {
        commits.emit(owner)
    }
}
