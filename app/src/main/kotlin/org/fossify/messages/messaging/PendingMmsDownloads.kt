package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.android.mms.transaction.DownloadManager
import com.google.android.mms.pdu_alt.PduHeaders
import java.util.concurrent.ConcurrentHashMap

/** Retry an existing carrier MMS notification via the SAME Klinker engine that
 * processes incoming WAP pushes. It handles the carrier network, parsed PDU,
 * storage in Telephony.Mms and the existing MmsReceiver completion callback. */
object PendingMmsDownloads {
    private val active = ConcurrentHashMap<Long, Long>()
    private const val MAX_IN_PROGRESS_MS = 90_000L
    const val DEFAULT_SMS_REQUIRED = "Set Messages as your default SMS app to download MMS."

    fun isDownloading(id: Long): Boolean {
        val started = active[id] ?: return false
        if (SystemClock.elapsedRealtime() - started < MAX_IN_PROGRESS_MS) return true
        active.remove(id, started)
        return false
    }

    fun clear() = active.clear()

    /** Called from a background thread. Null means the request was handed to
     * Android telephony, NOT that a carrier download has completed. */
    fun request(context: Context, id: Long): String? {
        if (isDownloading(id)) return null
        // Android 10+ assigns default SMS authority through RoleManager.
        // On some OEM ROMs Telephony.Sms.getDefaultSmsPackage() returns null or
        // stale data even though the role is correctly held by this app.
        val isDefaultSms = if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
                ?: (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName)
        } else Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        if (!isDefaultSms)
            return "Messages needs to be selected as the default SMS app to download MMS."
        val uri = Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, id.toString())
        try {
            val fields = arrayOf(
                Telephony.Mms.MESSAGE_TYPE,
                Telephony.Mms.CONTENT_LOCATION,
                Telephony.Mms.TRANSACTION_ID,
                Telephony.Mms.SUBSCRIPTION_ID
            )
            val meta = context.contentResolver.query(uri, fields, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val type = cursor.getInt(0)
                val location = cursor.getString(1)?.trim().orEmpty()
                val transactionId = cursor.getString(2)?.takeIf { it.isNotBlank() }
                val subscription = cursor.getInt(3)
                Triple(type, Pair(location, transactionId), subscription)
            } ?: return "The carrier MMS notification is no longer available."
            if (meta.first != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                return "This MMS has already been retrieved."
            val location = meta.second.first
            if (location.isBlank()) return "The carrier did not provide an MMS download address."
            val scheme = Uri.parse(location).scheme
            if (scheme != "http" && scheme != "https")
                return "This MMS contains an invalid download address."
            val subId = meta.third.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                ?: SmsManager.getDefaultSmsSubscriptionId()
            val now = SystemClock.elapsedRealtime()
            if (active.putIfAbsent(id, now) != null) return null
            try {
                DownloadManager.getInstance().downloadMultimediaMessage(
                    context.applicationContext,
                    location,
                    meta.second.second,
                    uri,
                    false,
                    subId
                )
            } catch (e: Exception) {
                active.remove(id)
                return "Couldn't start MMS download: ${e.localizedMessage ?: "network unavailable"}"
            }
            return null
        } catch (e: SecurityException) {
            active.remove(id)
            return "MMS download requires SMS permissions."
        } catch (e: Exception) {
            active.remove(id)
            return "Couldn't retrieve the MMS notification: ${e.localizedMessage ?: "unknown error"}"
        }
    }
}
