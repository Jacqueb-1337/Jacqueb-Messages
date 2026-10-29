package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.fossify.messages.messaging.PendingMmsDownloads

/** Keeps the Klinker download result tied to its exact original MMS URI.
 * Klinker's receiver starts an async parsing thread and its callback alone
 * otherwise loses the identity of the notification that initiated it. */
class MmsDownloadResultReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val originalUri:Uri? = if(Build.VERSION.SDK_INT>=33)
            intent.getParcelableExtra("notification_ind_uri",Uri::class.java)
        else @Suppress("DEPRECATION")
            intent.getParcelableExtra("notification_ind_uri")
        val id=originalUri?.lastPathSegment?.toLongOrNull()
            ?.takeIf{PendingMmsDownloads.isDownloading(it)}
        if(id!=null)PendingMmsDownloads.processing(id)
        MmsReceiver(id).onReceive(context,intent)
    }
}
