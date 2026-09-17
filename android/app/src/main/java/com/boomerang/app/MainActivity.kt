package com.boomerang.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boomerang.app.ui.BoomerangApp
import com.boomerang.app.ui.BoomerangTheme
import com.boomerang.app.ui.ShellViewModel
import com.boomerang.app.reminders.ReminderScheduler

class MainActivity : ComponentActivity() {
    private val model: ShellViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BoomerangTheme { BoomerangApp(model) } }
        openReminder(intent)
    }
    override fun onResume() { super.onResume(); ReminderScheduler.scan(this) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openReminder(intent) }
    private fun openReminder(intent: Intent) {
        val owner = intent.getStringExtra(ReminderScheduler.OWNER) ?: return
        val id = intent.getStringExtra(ReminderScheduler.RECORD) ?: return
        model.openReminder(owner, id)
        intent.removeExtra(ReminderScheduler.OWNER); intent.removeExtra(ReminderScheduler.RECORD)
    }
}
