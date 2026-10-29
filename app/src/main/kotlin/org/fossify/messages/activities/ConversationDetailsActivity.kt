package org.fossify.messages.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import org.fossify.messages.helpers.ConversationCustomization
import org.fossify.messages.theming.UiMotion
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.res.ResourcesCompat
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.adapters.ContactsAdapter
import org.fossify.messages.databinding.ActivityConversationDetailsBinding
import org.fossify.messages.dialogs.RenameConversationDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.renameConversation
import org.fossify.messages.extensions.startContactDetailsIntent
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.models.Conversation

class ConversationDetailsActivity : SimpleActivity() {

    private var threadId: Long = 0L
    private var conversation: Conversation? = null
    private lateinit var participants: ArrayList<SimpleContact>

    private val binding by viewBinding(ActivityConversationDetailsBinding::inflate)
    private var customSection: LinearLayout? = null
    private var soundPreview: MediaPlayer? = null
    private var soundPreviewUri: String? = null
    private var soundPreviewButton: TextView? = null
    private val iconPicker=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && threadId > 0L) {
            ensureBackgroundThread {
                val success=ConversationCustomization.savePhoto(this,threadId,uri)
                runOnUiThread {
                    if (success) renderCustomSection()
                    else android.widget.Toast.makeText(this,"Unable to load that picture",android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val audioDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && threadId > 0L) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers grant only a transient read permission.
            }
            stopSoundPreview()
            ConversationCustomization.setSound(this, threadId, uri.toString(),
                conversation?.title.orEmpty())
            renderCustomSection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.conversationDetailsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.conversationDetailsNestedScrollview,
            topAppBar = binding.conversationDetailsAppbar,
        )

        threadId = intent.getLongExtra(THREAD_ID, 0L)
        ensureBackgroundThread {
            conversation = conversationsDB.getConversationWithThreadId(threadId)
            participants = if (conversation != null && conversation!!.isScheduled) {
                val message = messagesDB.getThreadMessages(conversation!!.threadId).firstOrNull()
                message?.participants ?: arrayListOf()
            } else {
                getThreadParticipants(threadId, null)
            }
            runOnUiThread {
                setupTextViews()
                setupParticipants()
                setupCustomNotifications()
                renderCustomSection()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.conversationDetailsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.conversationDetailsHolder)

        val primaryColor = getProperPrimaryColor()
        arrayOf(
            binding.notificationsHeading,
            binding.conversationNameHeading,
            binding.membersHeading
        ).forEach {
            it.setTextColor(primaryColor)
        }
    }

    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
    /** The stock Android ringtone picker automatically auditions selections.
     * This picker deliberately does not: only the explicit preview control
     * starts playback, including for an audio file chosen from storage. */
    private fun showQuietSoundPicker() {
        val manager = RingtoneManager(this).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
        val titles = mutableListOf("System default", "Silent")
        val uris = mutableListOf("default", "")
        try {
            val cursor = manager.cursor
            for (index in 0 until cursor.count) {
                if (!cursor.moveToPosition(index)) continue
                val uri = manager.getRingtoneUri(index) ?: continue
                titles.add(cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "Notification tone")
                uris.add(uri.toString())
            }
        } catch (_: Exception) {
            // The built-in choices and document picker remain usable.
        }
        titles.add("Choose audio file...")
        AlertDialog.Builder(this)
            .setTitle("Sound for this conversation")
            .setItems(titles.toTypedArray()) { _, selection ->
                if (selection == uris.size) {
                    audioDocumentPicker.launch(arrayOf("audio/*"))
                } else {
                    stopSoundPreview()
                    ConversationCustomization.setSound(this, threadId, uris[selection],
                        conversation?.title.orEmpty())
                    renderCustomSection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        stopSoundPreview()
        super.onPause()
    }

    private fun stopSoundPreview() {
        try { soundPreview?.stop() } catch (_: Exception) {}
        soundPreview?.release()
        soundPreview = null
        soundPreviewUri = null
        soundPreviewButton?.text = "Play selected sound"
    }

    private fun toggleSoundPreview() {
        val uri = ConversationCustomization.soundUri(this, threadId) ?: return
        val key = uri.toString()
        val existing = soundPreview
        if (existing != null && soundPreviewUri == key) {
            try {
                if (existing.isPlaying) {
                    existing.pause()
                    soundPreviewButton?.text = "Resume sound"
                } else {
                    existing.start()
                    soundPreviewButton?.text = "Pause sound"
                }
            } catch (_: Exception) { stopSoundPreview() }
            return
        }
        stopSoundPreview()
        try {
            val player = MediaPlayer()
            soundPreview = player
            soundPreviewUri = key
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(this, uri)
            player.setOnPreparedListener {
                if (soundPreview === it) {
                    it.start()
                    soundPreviewButton?.text = "Pause sound"
                }
            }
            player.setOnCompletionListener {
                if (soundPreview === it) {
                    it.seekTo(0)
                    soundPreviewButton?.text = "Play selected sound"
                }
            }
            soundPreviewButton?.text = "Loading sound..."
            player.prepareAsync()
        } catch (_: Exception) {
            stopSoundPreview()
            android.widget.Toast.makeText(this, "Unable to preview this sound",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Audition exactly two copies of the newly selected vibration pattern,
     * inserting one brief silence between the two complete performances. */
    private fun previewVibrationTwice() {
        if (ConversationCustomization.vibration(this, threadId) == "none") return
        val pattern = ConversationCustomization.vibrationPattern(this, threadId)
            ?: longArrayOf(0L, 100L) // system default is device-dependent
        if (pattern.size < 2) return
        val twice = pattern + longArrayOf(190L) + pattern.copyOfRange(1, pattern.size)
        val vibrator = if (Build.VERSION.SDK_INT >= 31)
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            vibrator?.cancel()
            if (Build.VERSION.SDK_INT >= 26)
                vibrator?.vibrate(VibrationEffect.createWaveform(twice, -1))
            else @Suppress("DEPRECATION")
                vibrator?.vibrate(twice, -1)
        } catch (_: SecurityException) {
            // Devices with vibration disabled or disallowed simply remain silent.
        }
    }


    private fun renderCustomSection() {
        stopSoundPreview()
        soundPreviewButton = null
        val holder=binding.conversationDetailsHolder
        customSection?.let { holder.removeView(it) }
        val id=threadId
        if (id<=0L) return
        val dark=(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground=if(dark) Color.WHITE else Color.BLACK
        val sub=if(dark) 0xFFB3B3B7.toInt() else 0xFF63636B.toInt()
        val section=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(16),dp(18),dp(20))
        }
        customSection=section
        // Put the visual controls at the beginning, above advanced channel/member options.
        holder.addView(section,0)
        val image=ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            contentDescription="Conversation picture (animated GIF supported). Tap to change."
            background=android.graphics.drawable.GradientDrawable().apply {
                shape=android.graphics.drawable.GradientDrawable.OVAL
                setColor(if(dark) 0xFF303034.toInt() else 0xFFE5E5E8.toInt())
            }
            clipToOutline=true
            setOnClickListener { iconPicker.launch("image/*") }
        }
        val photo=ConversationCustomization.photoFile(this,id)
        if(photo!=null) {
            if (ConversationCustomization.isAnimatedPhoto(photo))
                Glide.with(this).asGif().load(photo).circleCrop().into(image)
            else Glide.with(this).load(photo).circleCrop().into(image)
        }
        else image.setImageResource(android.R.drawable.ic_menu_camera)
        val avatarRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        avatarRow.addView(image,LinearLayout.LayoutParams(dp(74),dp(74)).apply{rightMargin=dp(16)})
        val avatarActions=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val avatarTitle=TextView(this).apply {
            text=conversation?.title ?: "Conversation"
            textSize=19f
            setTextColor(foreground)
        }
        avatarActions.addView(avatarTitle)
        val change=TextView(this).apply {
            text=if(photo!=null)"Change picture  ·  Remove picture" else "Choose conversation picture"
            setTextColor(getProperPrimaryColor())
            textSize=14f
            setPadding(0,dp(9),0,dp(8))
            setOnClickListener {
                if(photo==null) iconPicker.launch("image/*")
                else org.fossify.messages.theming.FluidSheets.actions(
                    this@ConversationDetailsActivity,"Conversation picture",
                    arrayOf("Choose a new picture","Remove picture")) { option ->
                    if(option==0)iconPicker.launch("image/*")
                    else {
                        ConversationCustomization.removePhoto(this@ConversationDetailsActivity,id)
                        renderCustomSection()
                    }
                }
            }
        }
        avatarActions.addView(change)
        avatarRow.addView(avatarActions,LinearLayout.LayoutParams(0,-2,1f))
        section.addView(avatarRow)

        val description=TextView(this).apply {
            text="Choose a photo or animated GIF. GIFs play in the inbox and chat heads; notifications show a still frame. Your contacts aren't changed."
            textSize=12.5f
            setTextColor(sub)
            setPadding(0,dp(12),0,dp(14))
        }
        section.addView(description)
        fun setting(name:String,summary:String,action:()->Unit) {
            val cell=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(12),dp(12),dp(12),dp(12))
                background=android.graphics.drawable.GradientDrawable().apply{
                    cornerRadius=dp(14).toFloat()
                    setColor(if(dark)0xFF242426.toInt() else 0xFFF2F2F4.toInt())
                }
            }
            val title=TextView(this).apply{text=name;textSize=16f;setTextColor(foreground)}
            val subtitle=TextView(this).apply{
                text=summary;textSize=13f;setTextColor(sub);setPadding(0,dp(4),0,0)
            }
            cell.addView(title);cell.addView(subtitle)
            UiMotion.press(cell,.97f)
            cell.setOnClickListener{action()}
            section.addView(cell,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
        }
        val sound=ConversationCustomization.sound(this,id)
        setting("Notification sound",when(sound) {
            "default" -> "Default notification sound"
            "" -> "Silent"
            else -> "Custom sound"
        }) {
            showQuietSoundPicker()
        }
        // Never autoplay a newly chosen ringtone. A visible, explicit control
        // previews it with true pause/resume rather than restarting on each tap.
        val soundButton = TextView(this).apply {
            text = if (sound == "") "Silent, no sound to preview" else "Play selected sound"
            textSize = 14f
            setTextColor(getProperPrimaryColor())
            setPadding(dp(12), dp(7), dp(12), dp(17))
            isEnabled = sound != ""
            alpha = if (isEnabled) 1f else .5f
            setOnClickListener { toggleSoundPreview() }
        }
        soundPreviewButton = soundButton
        section.addView(soundButton)
        val patterns=arrayOf("default","none","short","double","heartbeat","long")
        val names=arrayOf("System default","Off","Short pulse","Double tap","Heartbeat","Long pulse")
        val vibration=ConversationCustomization.vibration(this,id)
        setting("Vibration pattern",names.getOrElse(patterns.indexOf(vibration)){"System default"}) {
            org.fossify.messages.theming.FluidSheets.actions(
                this,"Vibration for this conversation",names){which ->
                ConversationCustomization.setVibration(this,id,patterns[which],
                    conversation?.title.orEmpty())
                renderCustomSection()
                previewVibrationTwice()
            }
        }
        val notifications=ConversationCustomization.notificationsEnabled(this,id)
        setting("Message notifications",if(notifications)"On · tap to turn off" else "Off · also disables automatic chat heads") {
            ConversationCustomization.setNotificationsEnabled(this,id,!notifications)
            renderCustomSection()
        }
        setting("Android notification channel","Advanced settings, importance, and Do Not Disturb") {
            val channel=ConversationCustomization.ensureChannel(this,id,conversation?.title.orEmpty())
            startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply{
                putExtra(Settings.EXTRA_APP_PACKAGE,packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID,channel)
            })
        }
    }

    private fun setupCustomNotifications() {
        binding.apply {
            notificationsHeading.beVisible()
            customNotificationsHolder.beVisible()
            customNotifications.isChecked = config.customNotifications.contains(threadId.toString())
            customNotificationsButton.beVisibleIf(customNotifications.isChecked)

            customNotificationsHolder.setOnClickListener {
                customNotifications.toggle()
                if (customNotifications.isChecked) {
                    customNotificationsButton.beVisible()
                    config.addCustomNotificationsByThreadId(threadId)
                    createNotificationChannel()
                } else {
                    customNotificationsButton.beGone()
                    config.removeCustomNotificationsByThreadId(threadId)
                    removeNotificationChannel()
                }
            }

            customNotificationsButton.setOnClickListener {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, threadId.toString())
                    startActivity(this)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val name = conversation?.title
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
            .build()

        NotificationChannel(threadId.toString(), name, NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(false)
            enableLights(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                audioAttributes
            )
            enableVibration(true)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun removeNotificationChannel() {
        notificationManager.deleteNotificationChannel(threadId.toString())
    }

    private fun setupTextViews() {
        binding.conversationName.apply {
            ResourcesCompat.getDrawable(
                resources,
                org.fossify.commons.R.drawable.ic_edit_vector,
                theme
            )?.apply {
                applyColorFilter(getProperTextColor())
                setCompoundDrawablesWithIntrinsicBounds(null, null, this, null)
            }

            text = conversation?.title
            setOnClickListener {
                RenameConversationDialog(
                    this@ConversationDetailsActivity,
                    conversation!!
                ) { title ->
                    text = title
                    ensureBackgroundThread {
                        conversation = renameConversation(conversation!!, newTitle = title)
                    }
                }
            }
        }
    }

    private fun setupParticipants() {
        val adapter = ContactsAdapter(this, participants, binding.participantsRecyclerview) {
            val contact = it as SimpleContact
            val address = contact.phoneNumbers.first().normalizedNumber
            getContactFromAddress(address) { simpleContact ->
                if (simpleContact != null) {
                    startContactDetailsIntent(simpleContact)
                }
            }
        }
        binding.participantsRecyclerview.adapter = adapter
    }
}
