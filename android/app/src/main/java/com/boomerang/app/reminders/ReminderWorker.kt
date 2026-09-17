package com.boomerang.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.boomerang.app.MainActivity
import com.boomerang.app.R
import com.boomerang.app.data.BoomerangDatabase
import com.boomerang.app.data.BoomerangRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    internal val lock = Any()
    const val OWNER = "reminder_owner"
    const val RECORD = "reminder_record"
    const val CHANNEL = "record_deadlines"
    private fun prefs(context: Context) = context.getSharedPreferences("reminder_account", Context.MODE_PRIVATE)
    internal fun currentOwner(context: Context): String = prefs(context).getString("owner", "local") ?: "local"
    fun activate(context: Context, ownerNamespace: String) = synchronized(lock) {
        require(ownerNamespace == "local" || runCatching { UUID.fromString(ownerNamespace).toString() == ownerNamespace }.getOrDefault(false))
        val work = WorkManager.getInstance(context)
        val previous = currentOwner(context)
        if (previous != ownerNamespace) {
            check(prefs(context).edit().putString("owner", ownerNamespace).commit())
            work.cancelAllWorkByTag("reminders:$previous")
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "到期提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS)
            .setInputData(workDataOf(OWNER to ownerNamespace)).addTag("reminders:$ownerNamespace").build()
        work.enqueueUniquePeriodicWork("reminders:$ownerNamespace", ExistingPeriodicWorkPolicy.KEEP, request)
        scan(context)
    }
    fun scan(context: Context) = synchronized(lock) {
        val owner = currentOwner(context)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf(OWNER to owner))
            .addTag("reminders:$owner").build()
        // APPEND_OR_REPLACE preserves a rescan requested while an older snapshot is being processed.
        WorkManager.getInstance(context).enqueueUniqueWork("reminder-scan:$owner", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString(ReminderScheduler.OWNER) ?: return Result.failure()
        if (owner != ReminderScheduler.currentOwner(applicationContext)) return Result.success()
        return try {
            val db = BoomerangDatabase.open(applicationContext, owner)
            try {
                val repository = BoomerangRepository(db, owner)
                val records = repository.exportRecords()
                val now = Instant.now()
                NotificationRepository(applicationContext).use { inbox ->
                    records.forEach { snapshot ->
                        if (isStopped) return Result.success()
                        // Read latest revision: changed deadlines have no old per-record alarm to cancel.
                        val row = repository.detail(snapshot.id)?.record ?: return@forEach
                        val content = row.content
                        val plan = ReminderPolicy.plan(row.id, owner, content.dueEnd, content.timezone,
                            row.deletedAt == null && content.lifecycle == "ACTIVE" && content.confirmedStatus == null,
                            now, row.localRevision)
                        if (plan == null) {
                            synchronized(ReminderScheduler.lock) {
                                if (owner == ReminderScheduler.currentOwner(applicationContext)) applicationContext.getSystemService(NotificationManager::class.java).cancel("$owner/${row.id}", 0)
                            }
                            return@forEach
                        }
                        val message = when {
                            plan.daysRemaining < 0 -> "已到复核时间，打开记录查看"
                            plan.daysRemaining == 0L -> "今天到期"
                            else -> "还有 ${plan.daysRemaining} 天到期"
                        }
                        val item = LocalNotification(plan.key, owner, row.id, row.localRevision,
                            content.originalText.take(120), message, now.toString())
                        synchronized(ReminderScheduler.lock) {
                            if (owner == ReminderScheduler.currentOwner(applicationContext) && inbox.insertOnce(item)) post(item)
                        }
                    }
                }
            } finally { db.close() }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
    private fun post(item: LocalNotification) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(applicationContext, MainActivity::class.java)
            .setAction(item.key).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReminderScheduler.OWNER, item.owner).putExtra(ReminderScheduler.RECORD, item.recordId)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_boomerang).setContentTitle("回旋镖到期提醒").setContentText(item.message)
            .setAutoCancel(true).setContentIntent(pending).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        // Tag is record-scoped so a later revision replaces an already displayed reminder.
        try { manager.notify("${item.owner}/${item.recordId}", 0, notification) } catch (_: SecurityException) { /* Inbox remains available. */ }
    }
}
