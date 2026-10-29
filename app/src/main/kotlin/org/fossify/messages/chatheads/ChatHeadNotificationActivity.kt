package org.fossify.messages.chatheads

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.helpers.THREAD_ID

/**
 * A direct notification PendingIntent target. Android 12+ blocks notification
 * trampolines through broadcasts/services, so this user-initiated Activity
 * starts the overlay service directly and finishes immediately.
 *
 * No Android native bubble APIs are used.
 */
class ChatHeadNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, -1L)
        if (id <= 0L) {
            finish()
            return
        }
        val address = intent.getStringExtra(FloatingChatHeadsService.EXTRA_ADDRESS).orEmpty()
        val title = intent.getStringExtra(FloatingChatHeadsService.EXTRA_TITLE).orEmpty().ifBlank { address }
        val enabled = getSharedPreferences(ChatHeadsSettingsActivity.PREFS, MODE_PRIVATE)
            .getBoolean(ChatHeadsSettingsActivity.ENABLED, false)
        val started = if (enabled && Settings.canDrawOverlays(this)) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this,
                    FloatingChatHeadsService::class.java).apply {
                    action = FloatingChatHeadsService.ACTION_ADD
                    putExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, id)
                    putExtra(FloatingChatHeadsService.EXTRA_ADDRESS, address)
                    putExtra(FloatingChatHeadsService.EXTRA_TITLE, title)
                    putExtra(FloatingChatHeadsService.EXTRA_EXPAND, true)
                })
            }.isSuccess
        } else false

        if (!started) {
            startActivity(Intent(this, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
