package org.fossify.messages.messaging

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fossify.messages.extensions.deleteSmsDraft
import org.fossify.messages.extensions.saveSmsDraft
import java.util.concurrent.Executors

/** One durable draft per telephony thread, shared by Activity and chat heads.
 * SharedPreferences provides immediate cross-surface reads and crash-safe
 * persistence. The existing Room drafts table is mirrored after typing pauses,
 * preserving the inbox draft indicators and legacy draft compatibility. */
object ConversationDrafts {
    private const val STORE = "conversation_drafts_shared_v1"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "messages-draft-store").apply { isDaemon = true }
    }
    private val pending = HashMap<Long, Runnable>()
    private val observers = HashMap<Long, MutableSet<(String) -> Unit>>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private fun key(threadId: Long) = "draft_$threadId"

    /** Null = not migrated yet. Empty string = explicitly cleared draft. */
    fun read(context: Context, threadId: Long): String? {
        if (threadId <= 0L) return null
        val p = prefs(context)
        return if (p.contains(key(threadId))) p.getString(key(threadId), "") ?: "" else null
    }

    /** Call on the main thread. Listeners never write back the same text. */
    fun observe(threadId: Long, callback: (String) -> Unit) {
        if (threadId > 0) observers.getOrPut(threadId) { LinkedHashSet() }.add(callback)
    }
    fun unobserve(threadId: Long, callback: (String) -> Unit) {
        observers[threadId]?.let { it.remove(callback); if (it.isEmpty()) observers.remove(threadId) }
    }

    fun edit(context: Context, threadId: Long, text: String) {
        if (threadId <= 0) return
        val before = read(context, threadId)
        if (before == text) return
        prefs(context).edit().putString(key(threadId), text).apply()
        observers[threadId]?.toList()?.forEach { it(text) }
        pending.remove(threadId)?.let { main.removeCallbacks(it) }
        val app = context.applicationContext
        val task = Runnable {
            pending.remove(threadId)
            io.execute { persistToDatabase(app, threadId, text) }
        }
        pending[threadId] = task
        main.postDelayed(task, 300L)
    }

    /** Flush on background/close to keep the very last keystroke. */
    fun flush(context: Context, threadId: Long, text: String) {
        if (threadId <= 0) return
        if (read(context, threadId) != text) edit(context, threadId, text)
        // commit avoids relying on async disk flushing during process teardown
        prefs(context).edit().putString(key(threadId), text).commit()
        pending.remove(threadId)?.let { main.removeCallbacks(it) }
        val app = context.applicationContext
        io.execute { persistToDatabase(app, threadId, text) }
    }

    /** Import an existing Room draft exactly once, never over newer edits. */
    fun migrateLegacy(context: Context, threadId: Long, legacy: String) {
        if (legacy.isNotEmpty() && read(context, threadId) == null)
            edit(context, threadId, legacy)
    }

    private fun persistToDatabase(context: Context, threadId: Long, value: String) {
        // An older enqueued write must never resurrect a newer cleared draft.
        if (read(context, threadId) != value) return
        if (value.isEmpty()) context.deleteSmsDraft(threadId)
        else context.saveSmsDraft(value, threadId)
    }
}
