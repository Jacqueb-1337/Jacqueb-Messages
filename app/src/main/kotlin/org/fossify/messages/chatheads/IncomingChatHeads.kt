package org.fossify.messages.chatheads

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.messages.helpers.ConversationCustomization
import org.fossify.messages.extensions.conversationsDB

/**
 * Messenger-style delivery: when the opted-in foreground overlay is already
 * available, show the collapsed head/flyout and skip the duplicate message
 * notification. Fall back to normal notification if Android prevents launch.
 */
object IncomingChatHeads {
    fun armIfEnabled(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(ChatHeadsSettingsActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ChatHeadsSettingsActivity.ENABLED, false) ||
            !prefs.getBoolean(ChatHeadsSettingsActivity.AUTO_SHOW, true) ||
            !Settings.canDrawOverlays(app)) return
        try {
            ContextCompat.startForegroundService(app, Intent(app,
                FloatingChatHeadsService::class.java).apply {
                action = FloatingChatHeadsService.ACTION_ARM
            })
        } catch (e: Exception) {
            Log.w("IncomingChatHeads", "Unable to re-arm foreground heads on boot", e)
        }
    }

    fun onReceived(
        context: Context,
        threadId: Long,
        address: String,
        senderName: String,
        preview: String
    ): Boolean {
        if (threadId <= 0L || org.fossify.messages.safe.SafeStore.isSafe(context,threadId) ||
            !ConversationCustomization.notificationsEnabled(context,threadId)) return false
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(ChatHeadsSettingsActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ChatHeadsSettingsActivity.ENABLED, false) ||
            !prefs.getBoolean(ChatHeadsSettingsActivity.AUTO_SHOW, true) ||
            !Settings.canDrawOverlays(app)) return false

        val title = runCatching {
            val conversation=app.conversationsDB.getConversationWithThreadId(threadId)
            if (conversation?.isGroupConversation == true) conversation.title.ifBlank { senderName }
            else senderName
        }.getOrDefault(senderName)
        val intent = Intent(app, FloatingChatHeadsService::class.java).apply {
            action = FloatingChatHeadsService.ACTION_ADD
            putExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, threadId)
            putExtra(FloatingChatHeadsService.EXTRA_ADDRESS, address)
            putExtra(FloatingChatHeadsService.EXTRA_TITLE, title.ifBlank { address })
            putExtra(FloatingChatHeadsService.EXTRA_PREVIEW, preview.take(250))
            putExtra(FloatingChatHeadsService.EXTRA_INCOMING, true)
            putExtra(FloatingChatHeadsService.EXTRA_EXPAND, false)
        }
        // A running foreground service can show the head immediately.
        if (FloatingChatHeadsService.dispatchToRunning(intent)) return true

        // Best-effort cold start: do NOT suppress fallback notification,
        // because on Android 15+ there is no guarantee the overlay opens.
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: Exception) {
            Log.w("IncomingChatHeads", "Unable to show chat head from background", e)
        }
        return false
    }
}
