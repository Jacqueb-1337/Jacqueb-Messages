package org.fossify.messages.chatheads

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.models.Conversation

/** Native settings surface using the app's actual theme, not a separate light UI. */
class ChatHeadsSettingsActivity : SimpleActivity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var enabledSwitch: SwitchCompat
    private lateinit var autoSwitch: SwitchCompat
    private var pendingThread: ThreadPreview? = null
    private var requestingOverlay = false
    private var overlayPromptedOnce = false
    private var pendingEnable = false
    private var primary = 0
    private var inkColor = 0
    private var secondary = 0
    private var surface = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primary = getProperPrimaryColor()
        inkColor = getProperTextColor()
        val bg = getProperBackgroundColor()
        val dark = ColorUtils.calculateLuminance(bg) < .35
        secondary = if (dark) 0xFFA5A5A8.toInt() else 0xFF68686C.toInt()
        surface = if (dark) 0xFF29292B.toInt() else 0xFFF1F1F4.toInt()

        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        if (intent.action == ACTION_OPEN_HEAD) {
            val id = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
            if (id > 0) {
                pendingThread = ThreadPreview(
                    id, intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    intent.getStringExtra(EXTRA_ADDRESS).orEmpty(), ""
                )
            }
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        // The app is edge-to-edge. Pad below the actual status-bar inset so
        // the Chat heads title does not overlap the clock/status indicators.
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(dp(20), status + dp(12), dp(20), bottom + dp(8))
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 36f
            setTextColor(inkColor)
            gravity = Gravity.CENTER
            contentDescription = "Go back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(52)))
        header.addView(text("Chat heads", 24f, inkColor, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(header)
        outer.addView(text("Messenger-style floating conversations, not Android bubbles.", 14f, secondary).apply {
            setPadding(dp(4), dp(6), dp(4), dp(16))
        })
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(8), dp(15), dp(10))
            background = rounded(surface, 18)
        }
        enabledSwitch = SwitchCompat(this).apply {
            text = "Enable chat heads"
            textSize = 16f
            setTextColor(inkColor)
            setPadding(0, dp(7), 0, dp(7))
            isChecked = prefs.getBoolean(ENABLED, false) && Settings.canDrawOverlays(this@ChatHeadsSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !Settings.canDrawOverlays(this@ChatHeadsSettingsActivity)) {
                    pendingEnable = true
                    isChecked = false
                    requestOverlay()
                } else {
                    prefs.edit().putBoolean(ENABLED, checked).apply()
                    if (::autoSwitch.isInitialized) autoSwitch.isEnabled = checked
                    if (!checked) stopService(Intent(this@ChatHeadsSettingsActivity,
                        FloatingChatHeadsService::class.java))
                    else armIncomingHeads()
                }
            }
        }
        controls.addView(enabledSwitch)
        controls.addView(View(this).apply { setBackgroundColor(if (dark) 0xFF46464A.toInt() else 0xFFDADADD.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)))
        autoSwitch = SwitchCompat(this).apply {
            text = "Show heads for incoming messages"
            textSize = 15f
            setTextColor(inkColor)
            setPadding(0, dp(8), 0, dp(5))
            isChecked = prefs.getBoolean(AUTO_SHOW, true)
            isEnabled = enabledSwitch.isChecked
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AUTO_SHOW, checked).apply()
                if (checked && enabledSwitch.isChecked) armIncomingHeads()
                if (!checked) startService(Intent(this@ChatHeadsSettingsActivity,
                    FloatingChatHeadsService::class.java).apply {
                    action = FloatingChatHeadsService.ACTION_DISARM
                })
            }
        }
        controls.addView(autoSwitch)
        controls.addView(text("Keeps a low-priority notification active so incoming SMS/MMS can open heads even while this app is closed. Turn off automatic heads to stop monitoring.", 12f, secondary).apply {
            setPadding(0, dp(6), 0, dp(2))
        })
        body.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        body.addView(actionRow("Remove all floating heads", "Dismiss existing heads; stay ready for new messages") {
            startService(Intent(this, FloatingChatHeadsService::class.java).apply {
                action = FloatingChatHeadsService.ACTION_CLEAR
            })
        })
        body.addView(text("CONVERSATIONS", 13f, secondary, true).apply {
            setPadding(dp(5), dp(24), dp(4), dp(14))
        })
        body.addView(text("Tap any thread to open its chat head.", 13f, secondary).apply {
            setPadding(dp(5), 0, 0, dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        setContentView(outer)
    }

    override fun onResume() {
        super.onResume()
        val permission = Settings.canDrawOverlays(this)
        if (permission && pendingEnable) {
            prefs.edit().putBoolean(ENABLED, true).apply()
            pendingEnable = false
        }
        enabledSwitch.isChecked = prefs.getBoolean(ENABLED, false) && permission
        autoSwitch.isEnabled = enabledSwitch.isChecked
        if (enabledSwitch.isChecked && autoSwitch.isChecked) armIncomingHeads()
        val thread = pendingThread
        if (thread != null) {
            if (permission) {
                pendingThread = null
                openHead(thread)
                return
            }
            if (!overlayPromptedOnce) requestOverlay()
        }
        requestingOverlay = false
        renderThreads()
    }

    private fun armIncomingHeads() {
        if (!Settings.canDrawOverlays(this) || !prefs.getBoolean(ENABLED, false) ||
            !prefs.getBoolean(AUTO_SHOW, true)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, FloatingChatHeadsService::class.java).apply {
                action = FloatingChatHeadsService.ACTION_ARM
            })
        } catch (e: Exception) {
            android.util.Log.w("ChatHeadsSettings", "Unable to arm incoming chat heads", e)
        }
    }

    private fun requestOverlay() {
        requestingOverlay = true
        overlayPromptedOnce = true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun renderThreads() {
        list.removeAllViews()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            list.addView(actionRow("Grant SMS access",
                "Required to display and manage SMS conversation history") {
                if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.READ_SMS), 4104)
            })
            return
        }
        list.addView(text("Loading conversations…", 14f, secondary).apply { setPadding(dp(8), dp(16), 0, 0) })
        ensureBackgroundThread {
            val conversations: List<Conversation> = try {
                getConversations().take(40)
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeAllViews()
                if (conversations.isEmpty())
                    list.addView(text("No conversations to show yet.", 14f, secondary))
                for (thread in conversations) {
                    val info = ThreadPreview(thread.threadId, thread.title,
                        thread.phoneNumber, thread.snippet)
                    list.addView(actionRow(info.title.ifBlank { info.address },
                        info.preview.take(100)) {
                        if (Settings.canDrawOverlays(this)) openHead(info)
                        else {
                            pendingThread = info
                            requestOverlay()
                        }
                    })
                }
            }
        }
    }

    private fun openHead(thread: ThreadPreview) {
        prefs.edit().putBoolean(ENABLED, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, FloatingChatHeadsService::class.java).apply {
            action = FloatingChatHeadsService.ACTION_ADD
            putExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, thread.id)
            putExtra(FloatingChatHeadsService.EXTRA_TITLE, thread.title)
            putExtra(FloatingChatHeadsService.EXTRA_ADDRESS, thread.address)
            putExtra(FloatingChatHeadsService.EXTRA_EXPAND, true)
        })
        moveTaskToBack(true)
        // This screen was only a permission picker / head chooser; don't leave
        // it sitting on top of the conversation when Messages is reopened.
        finish()
    }

    private fun actionRow(title: String, detail: String, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = rounded(surface, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        box.addView(text(title, 16f, inkColor, true))
        if (detail.isNotBlank()) box.addView(text(detail, 13f, secondary).apply {
            setPadding(0, dp(5), 0, 0)
            maxLines = 2
        })
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
        return box
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value + 0.5f).toInt()

    data class ThreadPreview(val id: Long, val title: String, val address: String, val preview: String)

    companion object {
        const val PREFS = "messenger_heads_settings"
        const val ENABLED = "enabled"
        const val AUTO_SHOW = "auto_show_incoming"
        const val ACTION_OPEN_HEAD = "me.jacqueb.messages.chatheads.OPEN_THREAD"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_TITLE = "thread_title"
        const val EXTRA_ADDRESS = "thread_address"

        fun openThread(context: Context, threadId: Long, title: String, address: String) {
            if (threadId <= 0L) return
            if (Settings.canDrawOverlays(context)) {
                // Directly open the floating conversation from the long-press
                // action; never navigate through the settings Activity.
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(ENABLED, true).apply()
                try {
                    ContextCompat.startForegroundService(
                        context, Intent(context, FloatingChatHeadsService::class.java).apply {
                            action = FloatingChatHeadsService.ACTION_ADD
                            putExtra(FloatingChatHeadsService.EXTRA_THREAD_ID, threadId)
                            putExtra(FloatingChatHeadsService.EXTRA_TITLE, title)
                            putExtra(FloatingChatHeadsService.EXTRA_ADDRESS, address)
                            putExtra(FloatingChatHeadsService.EXTRA_EXPAND, true)
                        }
                    )
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                    return
                } catch (ex: Exception) {
                    android.util.Log.w("ChatHeadsSettings", "Direct floating head launch failed", ex)
                }
            }
            // Settings is only shown when overlay permission needs granting,
            // or when the direct service launch was blocked by Android.
            context.startActivity(Intent(context, ChatHeadsSettingsActivity::class.java).apply {
                action = ACTION_OPEN_HEAD
                putExtra(EXTRA_THREAD_ID, threadId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ADDRESS, address)
            })
        }
    }
}
