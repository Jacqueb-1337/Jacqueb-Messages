package org.fossify.messages.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact

/**
 * Process-local, read-only snapshot for the new-message picker.
 * A full ContactsProvider query can take seconds on a cold start. Preload
 * while the inbox is already visible and reuse the snapshot immediately when
 * the user taps +. A new query refreshes the list without blocking the UI.
 */
object ComposeContactsCache {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var fetching = false
    private var fetchedAt = 0L
    private var pending = ArrayList<(List<SimpleContact>) -> Unit>()

    @Volatile private var snapshot: List<SimpleContact>? = null

    fun peek(context: Context): List<SimpleContact>? {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            synchronized(lock) { snapshot = null }
            return null
        }
        return snapshot
    }

    fun prefetch(context: Context) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) return
        val fresh = SystemClock.elapsedRealtime() - fetchedAt < 60_000L
        if (snapshot != null && fresh) return
        refresh(context)
    }

    fun refresh(context: Context, onLoaded: ((List<SimpleContact>) -> Unit)? = null) {
        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            onLoaded?.invoke(emptyList())
            return
        }
        synchronized(lock) {
            if (onLoaded != null) pending.add(onLoaded)
            if (fetching) return
            fetching = true
        }
        val appContext = context.applicationContext
        ensureBackgroundThread {
            try {
                SimpleContactsHelper(appContext).getAvailableContacts(false) { contacts ->
                    val loaded = ArrayList(contacts)
                    val callbacks = synchronized(lock) {
                        snapshot = loaded
                        fetchedAt = SystemClock.elapsedRealtime()
                        fetching = false
                        pending.also { pending = ArrayList() }
                    }
                    main.post { callbacks.forEach { it(loaded) } }
                }
            } catch (_: Exception) {
                val callbacks = synchronized(lock) {
                    fetching = false
                    pending.also { pending = ArrayList() }
                }
                val previous = snapshot.orEmpty()
                main.post { callbacks.forEach { it(previous) } }
            }
        }
    }
}
