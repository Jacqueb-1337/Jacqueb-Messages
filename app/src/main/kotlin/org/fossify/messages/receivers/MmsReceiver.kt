package org.fossify.messages.receivers

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.extensions.getLatestMMS
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message
import org.fossify.messages.messaging.PendingMmsDownloads
import org.fossify.messages.chatheads.IncomingChatHeads

class MmsReceiver(private val originalNotificationId:Long?=null) : MmsReceivedReceiver() {

    override fun isAddressBlocked(context: Context, address: String): Boolean {
        if (context.isNumberBlocked(address)) return true
        if (context.baseConfig.blockUnknownNumbers) {
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val result = SimpleContactsHelper(context).existsSync(address, privateCursor)
            return result == ContactLookupResult.NotFound
        }

        return false
    }

    override fun isContentBlocked(context: Context, content: String): Boolean {
        return isMessageFilteredOut(context, content)
    }

    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        val mms = if (messageUri != null) {
            context.getMMS(messageUri)
        } else {
            context.getLatestMMS()
        }
        if(mms==null) {
            PendingMmsDownloads.fail(originalNotificationId,"Downloaded MMS could not be read.")
            return
        }
        if(originalNotificationId!=null && messageUri!=null &&
            !PendingMmsDownloads.complete(context,originalNotificationId,messageUri)) {
            PendingMmsDownloads.fail(originalNotificationId,
                "MMS could not be verified after downloading.")
            return
        }

        val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: mms.senderPhoneNumber
        val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
        ensureBackgroundThread {
            handleMmsMessage(context, mms, size, address)
        }
    }

    override fun onError(context: Context, error: String) {
        PendingMmsDownloads.fail(originalNotificationId,error)
        refreshMessages()
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
    }

    private fun handleMmsMessage(
        context: Context,
        mms: Message,
        size: Int,
        address: String
    ) {
        if (runCatching { org.fossify.messages.safe.SafeVault.absorbMms(context,mms.threadId) }
            .getOrElse { android.util.Log.e("Safe","MMS encryption incomplete; retaining provider copy",it); false }) {
            refreshConversations();refreshMessages();return
        }
        val glideBitmap = try {
            Glide.with(context)
                .asBitmap()
                .load(mms.attachment!!.attachments.first().getUri())
                .centerCrop()
                .into(size, size)
                .get()
        } catch (e: Exception) {
            null
        }


        val senderName = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
            context.getNameFromAddress(address, it)
        }

        val headShown = IncomingChatHeads.onReceived(context, mms.threadId, address, senderName, mms.body)
        context.showReceivedMessageNotification(
            messageId = mms.id,
            isMms = true,
            address = address,
            senderName = senderName,
            body = mms.body,
            threadId = mms.threadId,
            bitmap = glideBitmap,
            chatHeadVisualAlert = headShown
        )

        val conversation = context.getConversations(mms.threadId).firstOrNull() ?: return
        runCatching { context.insertOrUpdateConversation(conversation) }
        if (context.shouldUnarchive()) {
            context.updateConversationArchivedStatus(mms.threadId, false)
        }
        org.fossify.messages.search.SearchCatalogWorker.schedule(context,force=true)
        refreshMessages()
        refreshConversations()
    }
}
