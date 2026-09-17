package com.boomerang.app.cloud

import org.junit.Assert.*
import org.junit.Test

class SyncMergePolicyTest {
    @Test fun dirtyRecordKeepsNewEditWhenCloudOnlyContainsOwnAcknowledgement() {
        assertEquals(SyncMergePolicy.Pull.KEEP_LOCAL, SyncMergePolicy.pull(3, true, 3))
    }
    @Test fun newerCloudVersionPreservesDirtyLocalAsConflict() {
        assertEquals(SyncMergePolicy.Pull.CONFLICT, SyncMergePolicy.pull(3, true, 4))
    }
    @Test fun rejectedSourceRemovalConflictsEvenWhenRecordVersionDidNotAdvance() {
        assertEquals(SyncMergePolicy.Pull.CONFLICT, SyncMergePolicy.pull(3, true, 3, true))
    }
    @Test fun olderCloudResponseNeverOverwritesHigherKnownVersion() {
        assertEquals(SyncMergePolicy.Pull.KEEP_LOCAL, SyncMergePolicy.pull(4, false, 3))
    }
    @Test fun cleanRecordAndNewRemoteRecordCanBeDownloaded() {
        assertEquals(SyncMergePolicy.Pull.APPLY, SyncMergePolicy.pull(3, false, 4))
        assertEquals(SyncMergePolicy.Pull.APPLY, SyncMergePolicy.pull(null, false, 1))
    }
    @Test fun acknowledgementMayAdvanceBaseOfNewerLocalEditButRejectsChangedBase() {
        val sent = PendingUpload("id", 2, 1, "{}", "[]")
        assertTrue(SyncMergePolicy.canAcknowledge(3, 1, sent))
        assertFalse(SyncMergePolicy.canAcknowledge(3, 2, sent))
    }
}
