package org.fossify.messages.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.fossify.messages.chatheads.ChatHeadsSettingsActivity
import org.fossify.messages.chatheads.ChatHeadNotificationActivity
import org.fossify.messages.chatheads.FloatingChatHeadsService
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.shortcutHelper
import org.fossify.messages.messaging.isShortCodeWithLetters
import org.fossify.messages.receivers.DeleteSmsReceiver
import org.fossify.messages.receivers.DirectReplyReceiver
import org.fossify.messages.receivers.MarkAsReadReceiver

class NotificationHelper(private val context: Context) {

    private val notificationManager = context.notificationManager
    private val soundUri get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    companion object {
        // New channel ID: Android does not let apps downgrade an existing HIGH
        // channel to DEFAULT after it has been created.
        private const val CHAT_HEAD_INCOMING_CHANNEL_ID = "message_chatheads_no_banner_v2"
    }
    private val user = Person.Builder()
        .setName(context.getString(R.string.me))
        .build()

    @SuppressLint("NewApi")
    fun showMessageNotification(
        messageId: Long,
        isMms: Boolean,
        address: String,
        body: String,
        threadId: Long,
        bitmap: Bitmap?,
        sender: String?,
        alertOnlyOnce: Boolean = false,
        chatHeadVisualAlert: Boolean = false
    ) {
        if (!ConversationCustomization.notificationsEnabled(context,threadId)) return
        val appCustomNotifications=ConversationCustomization.hasCustomAlert(context,threadId)
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        // A DEFAULT-importance notification DID display a heads-up banner on
        // the user's crDroid device, despite our earlier claims. LOW importance
        // guarantees no app-requested heads-up banner. Restore notification
        // audio/vibration separately while respecting settings and DND.
        val useNoBannerChannel = chatHeadVisualAlert
        val notificationChannelId = when {
            useNoBannerChannel -> CHAT_HEAD_INCOMING_CHANNEL_ID
            appCustomNotifications -> ConversationCustomization.ensureChannel(context,threadId,
                sender.orEmpty().ifBlank { address })
            hasCustomNotifications -> threadId.toString()
            else -> NOTIFICATION_CHANNEL_ID
        }
        if (useNoBannerChannel) {
            createChannel(notificationChannelId, "Chat-head messages (no popup)", IMPORTANCE_LOW)
        } else if (!hasCustomNotifications && !appCustomNotifications) {
            createChannel(notificationChannelId, context.getString(R.string.channel_received_sms))
        }

        val notificationId = threadId.hashCode()
        val headsEnabled = context.getSharedPreferences(ChatHeadsSettingsActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ChatHeadsSettingsActivity.ENABLED, false) && Settings.canDrawOverlays(context)
        val contentIntent = if (headsEnabled) {
            Intent(context, ChatHeadNotificationActivity::class.java).apply {
                putExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, threadId)
                putExtra(FloatingChatHeadsService.EXTRA_ADDRESS, address)
                putExtra(FloatingChatHeadsService.EXTRA_TITLE, sender.orEmpty().ifBlank { address })
            }
        } else {
            Intent(context, ThreadActivity::class.java).apply {
                putExtra(THREAD_ID, threadId)
            }
        }
        val contentPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

        val markAsReadIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            action = MARK_AS_READ
            putExtra(THREAD_ID, threadId)
        }
        val markAsReadPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                markAsReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val deleteSmsIntent = Intent(context, DeleteSmsReceiver::class.java).apply {
            putExtra(THREAD_ID, threadId)
            putExtra(MESSAGE_ID, messageId)
            putExtra(IS_MMS, isMms)
        }
        val deleteSmsPendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                deleteSmsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        var replyAction: NotificationCompat.Action? = null
        val isNoReplySms = isShortCodeWithLetters(address)
        if (!isNoReplySms) {
            val replyLabel = context.getString(R.string.reply)
            val remoteInput = RemoteInput.Builder(REPLY)
                .setLabel(replyLabel)
                .build()

            val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
                putExtra(THREAD_ID, threadId)
                putExtra(THREAD_NUMBER, address)
            }

            val replyPendingIntent =
                PendingIntent.getBroadcast(
                    context.applicationContext,
                    notificationId,
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_send_vector,
                replyLabel,
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .build()
        }

        val largeIcon = ConversationCustomization.photoBitmap(context,threadId)
            ?: bitmap ?: if (sender != null) {
                SimpleContactsHelper(context).getContactLetterIcon(sender)
            } else {
                null
            }
        val builder = NotificationCompat.Builder(context, notificationChannelId).apply {
            when (context.config.lockScreenVisibilitySetting) {
                LOCK_SCREEN_SENDER_MESSAGE -> {
                    setLargeIcon(largeIcon)
                    setStyle(getMessagesStyle(address, body, notificationId, sender))
                }

                LOCK_SCREEN_SENDER -> {
                    setContentTitle(sender)
                    setLargeIcon(largeIcon)
                    val summaryText = context.getString(R.string.new_message)
                    setStyle(
                        NotificationCompat.BigTextStyle().setSummaryText(summaryText).bigText(body)
                    )
                }
            }

            color = context.getProperPrimaryColor()
            setSmallIcon(R.drawable.ic_messenger)
            setContentIntent(contentPendingIntent)
            priority = if (useNoBannerChannel) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_MAX
            setVisibility(if (context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE)
                NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            setDefaults(Notification.DEFAULT_LIGHTS)
            setCategory(Notification.CATEGORY_MESSAGE)
            setAutoCancel(true)
            setOnlyAlertOnce(alertOnlyOnce)
            setSound(soundUri, AudioManager.STREAM_NOTIFICATION)
        }

        if (replyAction != null && context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE) {
            builder.addAction(replyAction)
        }

        builder.addAction(
            org.fossify.commons.R.drawable.ic_check_vector,
            context.getString(R.string.mark_as_read),
            markAsReadPendingIntent
        )
            .setChannelId(notificationChannelId)
        if (isNoReplySms) {
            builder.addAction(
                org.fossify.commons.R.drawable.ic_delete_vector,
                context.getString(org.fossify.commons.R.string.delete),
                deleteSmsPendingIntent
            ).setChannelId(notificationChannelId)
        }

        var shortcut = context.shortcutHelper.getShortcut(threadId)
        if (shortcut == null) {
            ensureBackgroundThread {
                shortcut = context.shortcutHelper.createOrUpdateShortcut(threadId)
                // MessagingStyle shortcuts can promote a conversation to
                // importance HIGH. Never attach one to the no-popup channel.
                if (!useNoBannerChannel) builder.setShortcutInfo(shortcut)
                notificationManager.notify(notificationId, builder.build())
                if (useNoBannerChannel) playChatHeadAlert(notificationChannelId, alertOnlyOnce,threadId)
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        } else {
            if (!useNoBannerChannel) builder.setShortcutInfo(shortcut)
            notificationManager.notify(notificationId, builder.build())
            if (useNoBannerChannel) playChatHeadAlert(notificationChannelId, alertOnlyOnce,threadId)
            ensureBackgroundThread {
                context.shortcutHelper.reportReceiveMessageUsage(threadId)
            }
        }
    }

    @SuppressLint("NewApi")
    fun showSendingFailedNotification(recipientName: String, threadId: Long) {
        val hasCustomNotifications =
            context.config.customNotifications.contains(threadId.toString())
        val notificationChannelId =
            if (hasCustomNotifications) threadId.toString() else NOTIFICATION_CHANNEL_ID
        if (!hasCustomNotifications) {
            createChannel(notificationChannelId, context.getString(R.string.message_not_sent_short))
        }

        val notificationId = generateRandomId().hashCode()
        val intent = Intent(context, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, threadId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val summaryText =
            String.format(context.getString(R.string.message_sending_error), recipientName)
        val largeIcon = SimpleContactsHelper(context).getContactLetterIcon(recipientName)
        val builder = NotificationCompat.Builder(context, notificationChannelId)
            .setContentTitle(context.getString(R.string.message_not_sent_short))
            .setContentText(summaryText)
            .setColor(context.getProperPrimaryColor())
            .setSmallIcon(R.drawable.ic_messenger)
            .setLargeIcon(largeIcon)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_LIGHTS)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setChannelId(notificationChannelId)

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * LOW-importance notifications cannot be promoted to heads-up by this app.
     * On Android these also lose their automatic audible alert. Trigger the
     * user-configured channel sound and vibration manually only if not muted
     * and Do Not Disturb is not active, preserving visible notification history.
     * This does not bypass user DND or muted channel preferences.
     */
    private fun playChatHeadAlert(channelId: String, alertOnlyOnce: Boolean, threadId: Long) {
        if (alertOnlyOnce || !ConversationCustomization.notificationsEnabled(context,threadId)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!nm.areNotificationsEnabled()) return
        val selectedChannel=if(ConversationCustomization.hasCustomAlert(context,threadId))
            ConversationCustomization.ensureChannel(context,threadId,"Message conversation")
        else channelId
        val channel = nm.getNotificationChannel(selectedChannel) ?: return
        if (channel.importance < IMPORTANCE_LOW) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (channel.sound != null &&
            audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
            try {
                RingtoneManager.getRingtone(context, channel.sound)?.play()
            } catch (_: Exception) {}
        }
        if (channel.shouldVibrate() && audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (vibrator.hasVibrator()) {
                    val pattern=if(ConversationCustomization.hasVibrationPreference(context,threadId))
                        ConversationCustomization.vibrationPattern(context,threadId)
                    else channel.vibrationPattern
                    if(pattern!=null && pattern.size>=2)
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern,-1))
                    else vibrator.vibrate(VibrationEffect.createOneShot(
                        130L, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Exception) {}
        }
    }

    private fun createChannel(id: String, name: String, importance: Int = IMPORTANCE_HIGH) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        NotificationChannel(id, name, importance).apply {
            description = if (importance == IMPORTANCE_LOW)
                "No heads-up popup. Full notifications in shade/lockscreen with separate sound and vibration respecting device settings."
            else "New messages"
            setBypassDnd(false)
            enableLights(true)
            setSound(soundUri, audioAttributes)
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun getMessagesStyle(
        address: String,
        body: String,
        notificationId: Int,
        name: String?
    ): NotificationCompat.MessagingStyle {
        val sender = if (name != null) {
            Person.Builder()
                .setName(name)
                .setKey(address)
                .build()
        } else {
            null
        }

        return NotificationCompat.MessagingStyle(user).also { style ->
            getOldMessages(notificationId).forEach {
                style.addMessage(it)
            }
            val newMessage =
                NotificationCompat.MessagingStyle.Message(body, System.currentTimeMillis(), sender)
            style.addMessage(newMessage)
        }
    }

    private fun getOldMessages(notificationId: Int): List<NotificationCompat.MessagingStyle.Message> {
        val currentNotification =
            notificationManager.activeNotifications.find { it.id == notificationId }
        return if (currentNotification != null) {
            val activeStyle =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
                    currentNotification.notification
                )
            activeStyle?.messages.orEmpty()
        } else {
            emptyList()
        }
    }
}
