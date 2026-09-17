package com.boomerang.app.cloud

/** Version decisions shared by Room transactions and deterministic JVM behavior tests. */
object SyncMergePolicy {
    enum class Pull { APPLY, KEEP_LOCAL, CONFLICT }
    fun pull(localBase: Long?, dirty: Boolean, remoteVersion: Long, rejectedUpload: Boolean = false): Pull {
        require(remoteVersion > 0)
        if (localBase == null) return Pull.APPLY
        if (remoteVersion < localBase) return Pull.KEEP_LOCAL
        if (dirty) return if (rejectedUpload || remoteVersion != localBase) Pull.CONFLICT else Pull.KEEP_LOCAL
        return Pull.APPLY
    }
    fun canAcknowledge(currentLocalRevision: Long, currentBase: Long, sent: PendingUpload): Boolean =
        currentLocalRevision >= sent.localRevision && currentBase == sent.serverRevision
}
