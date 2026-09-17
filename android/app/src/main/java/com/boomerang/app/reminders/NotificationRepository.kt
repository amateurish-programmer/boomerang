package com.boomerang.app.reminders

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalNotification(val key: String, val owner: String, val recordId: String, val revision: Long,
    val title: String, val message: String, val createdAt: String)

/** A separate durable inbox; permission denial never removes these entries. */
class NotificationRepository(context: Context) : SQLiteOpenHelper(context.applicationContext, "notification_inbox.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notifications(delivery_key TEXT PRIMARY KEY, owner TEXT NOT NULL, record_id TEXT NOT NULL, revision INTEGER NOT NULL, title TEXT NOT NULL, message TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE INDEX notification_owner ON notifications(owner,created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Explicit notification migration required")
    fun insertOnce(item: LocalNotification): Boolean {
        val values = ContentValues().apply {
            put("delivery_key", item.key); put("owner", item.owner); put("record_id", item.recordId)
            put("revision", item.revision); put("title", item.title); put("message", item.message); put("created_at", item.createdAt)
        }
        val inserted = writableDatabase.insertWithOnConflict("notifications", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) changes.value += 1
        return inserted
    }
    fun list(owner: String): List<LocalNotification> = readableDatabase.query("notifications", null, "owner=?", arrayOf(owner), null, null, "created_at DESC", "200").use { cursor ->
        buildList { while (cursor.moveToNext()) {
            fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
            add(LocalNotification(text("delivery_key"), text("owner"), text("record_id"), cursor.getLong(cursor.getColumnIndexOrThrow("revision")), text("title"), text("message"), text("created_at")))
        } }
    }
    companion object {
        private val changes = MutableStateFlow(0L)
        val updates = changes.asStateFlow()
    }
}
