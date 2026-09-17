package com.boomerang.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boomerang.app.data.*
import com.boomerang.app.domain.RecordContent
import com.boomerang.app.reminders.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderColdStartTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun coldNotificationClickWaitsForAccountRestoreAndOpensRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = BoomerangDatabase.open(context)
        val repository = BoomerangRepository(db)
        val id = repository.save(RecordContent(originalText = "通知冷启动记录"), emptyList())
        try {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ReminderScheduler.OWNER, "local").putExtra(ReminderScheduler.RECORD, id)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail_original").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("detail_original").assertTextEquals("通知冷启动记录")
            }
        } finally { repository.detail(id)?.let { repository.softDelete(id, it.record.localRevision) }; db.close() }
    }
    @Test fun coldNotificationForDifferentAccountDoesNotOpenLocalRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ReminderScheduler.OWNER, UUID.randomUUID().toString())
            .putExtra(ReminderScheduler.RECORD, UUID.randomUUID().toString())
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("create_record").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("detail_original").assertDoesNotExist()
        }
    }
}
