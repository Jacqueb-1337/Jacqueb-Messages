package org.fossify.messages.safe

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.theming.UiTheme


import android.view.Gravity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.messages.extensions.config

/** Safe uses the same credential dialog and app lock as Messages. */
class SafeActivity : SimpleActivity() {
    private lateinit var main: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private var launchingThread = false
    private var deviceAuthPending = false
    private var renderVersion = 0
    private var transferring = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expire = Runnable { if (!isFinishing && !isDestroyed) render() }
    private val accent by lazy { UiTheme.color(this, "accent") }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun msg(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun label(t: String, size: Float = 16f) = TextView(this).apply {
        text = t; textSize = size
        setTextColor(UiTheme.color(this@SafeActivity, "title"))
        setPadding(dp(20), dp(12), dp(20), dp(12))
    }
    private fun action(t: String, callback: () -> Unit) {
        main.addView(label(t).apply {
            setTextColor(accent); minHeight = dp(52); setOnClickListener { callback() }
        })
    }
    private fun button(t: String, callback: () -> Unit) {
        main.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = t; backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setTextColor(Color.WHITE); setOnClickListener { callback() }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply {
            setMargins(dp(20), dp(16), dp(20), dp(12))
        })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.color(this@SafeActivity, "surface"))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        render()
    }
    override fun onResume() {
        super.onResume()
        launchingThread = false
        if (::root.isInitialized && !deviceAuthPending) render()
    }
    override fun onStop() {
        handler.removeCallbacks(expire)
        if (!launchingThread) {
            SafeStore.lock()
            // Remove private rows immediately, including during authentication handoff.
            render()
        }
        super.onStop()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private fun requireUnlock(done: () -> Unit) {
        if (SafeStore.unlocked()) done() else {
            render()
            unlockFramework { done() }
        }
    }
    private fun render() {
        if (!::root.isInitialized || isDestroyed) return
        val version = ++renderVersion
        handler.removeCallbacks(expire)
        root.removeAllViews()
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = "Safe"
            setTitleTextColor(UiTheme.color(this@SafeActivity, "title"))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            if (SafeStore.unlocked()) {
                menu.add("Lock").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                menu.add("Notification privacy")
                menu.add("About Safe privacy")
                overflowIcon = overflowIcon?.mutate()?.apply {
                    setTint(UiTheme.color(this@SafeActivity, "title"))
                }
                setOnMenuItemClickListener {
                    if (it.title == "Lock") { SafeStore.lock(); render() }
                    else if (it.title == "Notification privacy") requireUnlock { notificationSettings() }
                    else AlertDialog.Builder(this@SafeActivity).setTitle("Safe privacy")
                        .setMessage("Encrypted conversations are stored only in this app using a device-bound key. Restore them before uninstalling, clearing app data, or changing phones. Older hidden chats must be encrypted using their menu. Incoming MMS passes through the carrier download provider before entering Safe.")
                        .setPositiveButton("OK", null).show()
                    true
                }
            }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(main) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        if (!SafeStore.unlocked() || !SafeStore.frameworkConfigured(this)) {
            main.addView(label("Private conversations", 25f).apply { setTypeface(null, Typeface.BOLD) })
            main.addView(label(if (SafeStore.frameworkConfigured(this)) {
                if (config.isAppPasswordProtectionOn) "Unlock with your Messages app lock."
                else "Unlock with the standard Messages security screen."
            } else if (SafeStore.legacyConfigured(this)) {
                "Verify your previous Safe lock once to switch to the standard Messages security screen."
            } else "Use the same PIN, pattern or biometric security screen as Messages.", 15f))
            if (SafeStore.frameworkConfigured(this)) button("Unlock Safe") { unlockFramework() }
            else if (SafeStore.legacyConfigured(this)) {
                button("Verify previous Safe lock") { authenticate(SafeStore.primary(this)) }
                action("Use previous backup method") { authenticate(SafeStore.backup(this)) }
            } else button("Set up Safe lock") { setupFrameworkLock() }
            return
        }
        SafeStore.clearMessageNotifications(this)
        handler.postDelayed(expire, SafeStore.sessionRemaining() + 20L)
        if (transferring) { main.addView(label("Securing and verifying your messages...",16f)); return }
        val pending = intent.getLongExtra("safe_add_thread", -1L)
        if (pending > 0) {
            intent.removeExtra("safe_add_thread")
            transfer(pending,true)
            return
        }
        main.addView(label("Encrypted conversations and older hidden chats", 14f).apply { alpha = .65f })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        main.addView(list)
        val target = list
        target.addView(label("Loading conversations...", 14f))
        ensureBackgroundThread {
            val result = runCatching {
                val ids = SafeStore.safeThreads(this)
                val cached=(conversationsDB.getNonArchived() + conversationsDB.getAllArchived()).associateBy{it.threadId}
                ids.mapNotNull { id ->
                    if (SafeVault.exists(this,id)) SafeVault.conversation(this,id) else cached[id]
                }.sortedByDescending { it.date }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || version != renderVersion || !SafeStore.unlocked()) return@runOnUiThread
                target.removeAllViews()
                result.onSuccess { conversations ->
                    if (conversations.isEmpty()) {
                        target.addView(label("No conversations in Safe", 22f))
                        target.addView(label("Open a conversation's avatar menu in your inbox and choose Move to Safe.", 15f))
                    }
                    conversations.forEach { conv ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(16), dp(10), dp(8), dp(10)); minimumHeight = dp(80)
                            val out = android.util.TypedValue()
                            theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                            setBackgroundResource(out.resourceId)
                        }
                        row.addView(label(conv.title.take(1).uppercase(), 23f).apply {
                            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); setTextColor(accent)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor((accent and 0x00FFFFFF) or 0x19000000)
                            }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))
                        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        details.addView(label(conv.title, 17f).apply {
                            setTypeface(null, Typeface.BOLD); setPadding(dp(16), 0, dp(4), dp(5))
                        })
                        details.addView(label((if(!SafeVault.exists(this@SafeActivity,conv.threadId)) "Not encrypted yet - use menu to encrypt" else conv.snippet).ifBlank { if (conv.isGroupConversation) "Group conversation" else "No message preview" }, 14f).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                            alpha = .6f; setPadding(dp(16), 0, 0, 0)
                        })
                        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                        val more = label("⋮", 26f).apply {
                            contentDescription = "Options for " + conv.title
                            gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
                        }
                        row.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
                        more.setOnClickListener {
                            if (!SafeStore.unlocked()) { render(); unlockFramework(); return@setOnClickListener }
                            run {
                                PopupMenu(this, more).apply {
                                    if (!SafeVault.exists(this@SafeActivity,conv.threadId)) menu.add("Encrypt messages")
                                    else {
                                        when(runCatching { SafeVault.read(this@SafeActivity,conv.threadId).optString("phase") }.getOrDefault("")) {
                                            "capturing" -> menu.add("Resume encryption")
                                            "restoring","restored" -> menu.add("Resume restore")
                                        }
                                    }
                                    menu.add("Move to inbox")
                                    setOnMenuItemClickListener {
                                        if (it.title=="Move to inbox" || it.title=="Resume restore") confirmMoveOut(conv.threadId) else transfer(conv.threadId,true)
                                        true
                                    }
                                }.show()
                            }
                        }
                        row.setOnClickListener {
                            requireUnlock {
                                launchingThread = true
                                startActivity(Intent(this, if(SafeVault.exists(this,conv.threadId)) SafeConversationActivity::class.java else ThreadActivity::class.java).apply {
                                    putExtra(THREAD_ID, conv.threadId); putExtra(THREAD_TITLE, conv.title)
                                    putExtra("safe_return_to_list", true)
                                })
                            }
                        }
                        target.addView(row)
                    }
                }.onFailure {
                    target.addView(label("Conversations could not be loaded. Tap to retry.", 15f).apply {
                        setOnClickListener { render() }
                    })
                }
            }
        }
    }
    private fun confirmMoveOut(id: Long) {
        requireUnlock {
            AlertDialog.Builder(this).setTitle("Move to inbox?")
                .setMessage("This conversation will appear in your regular inbox again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move") { _, _ ->
                    // Recheck AFTER the dialog: the session may have expired while it was open.
                    requireUnlock {
                        transfer(id,false)
                    }
                }.create().also { dialog ->
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.show()
                }
        }
    }
    private fun transfer(id:Long, into:Boolean) {
        requireUnlock {
            if (transferring) return@requireUnlock
            transferring=true;render()
            ensureBackgroundThread {
                val result=runCatching { check(SafeStore.move(this,id,into)) { "Unlock Safe and try again" } }
                runOnUiThread {
                    transferring=false
                    if (!isFinishing && !isDestroyed) {
                        result.onFailure { error ->
                            AlertDialog.Builder(this).setTitle("Transfer needs attention")
                                .setMessage(error.message ?: "Encrypted recovery copy retained. Retry to finish.")
                                .setPositiveButton("OK",null).create().also {
                                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);it.show()
                                }
                        }.onSuccess { msg(if(into) "Messages encrypted and removed from Android" else "Messages restored to inbox") }
                        render()
                    }
                }
            }
        }
    }
    private fun notificationSettings() {
        AlertDialog.Builder(this).setTitle("Safe notifications")
            .setSingleChoiceItems(arrayOf("Off", "Generic notification only"),
                if (SafeStore.notificationMode(this) == SafeStore.ALERT_OFF) 0 else 1) { dialog, which ->
                requireUnlock {
                    SafeStore.setNotificationMode(this, if (which == 0) SafeStore.ALERT_OFF else SafeStore.ALERT_REDACTED)
                }
                dialog.dismiss()
            }.setNegativeButton("Cancel", null).create().also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show()
            }
    }
    private fun secureSecurityDialog(hash: String, type: Int, callback: (String, Int, Boolean) -> Unit) {
        val security = SecurityDialog(this, hash, type, callback)
        // Commons 7.1.3 exposes no dialog/window accessor. Secure its pinned
        // AlertDialog synchronously before the first draw, including PIN/pattern.
        try {
            val field = SecurityDialog::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            (field.get(security) as? AlertDialog)?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (error: Exception) {
            // Fail closed rather than displaying an unprotected credential surface.
            finish()
            android.util.Log.e("Safe", "Could not secure the standard security dialog", error)
        }
    }
    private fun setupFrameworkLock() {
        if (SafeStore.frameworkConfigured(this)) { unlockFramework(); return }
        // Existing Safe users must verify their old lock before choosing its replacement.
        if (SafeStore.legacyConfigured(this) && !SafeStore.unlocked()) { render(); return }
        secureSecurityDialog("", SHOW_ALL_TABS) { hash, type, success ->
            if (success && SafeStore.saveFrameworkLock(this, hash, type)) {
                SafeStore.unlock(); render()
            }
        }
    }
    private fun unlockFramework(done: () -> Unit = {}) {
        if (!SafeStore.frameworkConfigured(this)) { render(); return }
        secureSecurityDialog(SafeStore.lockHash(this), SafeStore.lockType(this)) { hash, type, success ->
            if (success && !isFinishing && !isDestroyed) {
                // Preserve the existing credential if app-wide protection is disabled later.
                SafeStore.saveFrameworkLock(this, hash, type)
                SafeStore.unlock(); render(); done()
            }
        }
    }
    private fun authenticate(kind:String) {
        if(kind==SafeStore.LOCK_DEVICE) {
            val manager=getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if(!manager.isDeviceSecure) {msg("Android screen lock unavailable; use your backup");return}
            deviceAuthPending=true
            if(Build.VERSION.SDK_INT>=30) {
                val prompt=android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("Unlock Safe")
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(android.os.CancellationSignal(),mainExecutor,object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                        deviceAuthPending=false;SafeStore.unlock();setupFrameworkLock()
                    }
                    override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {
                        deviceAuthPending=false;render()
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                val intent=manager.createConfirmDeviceCredentialIntent("Unlock Safe","Confirm your Android screen lock")
                if(intent!=null)startActivityForResult(intent,7142)
                else {deviceAuthPending=false;render()}
            }
        } else enterCredential(kind,"Unlock Safe") { value ->
            if(SafeStore.verify(this,kind,value))setupFrameworkLock() else {msg("Incorrect unlock method");render()}
        }
    }
    @Deprecated("Android system credential confirmation on API 28–29")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==7142) {
            deviceAuthPending=false
            if(resultCode==RESULT_OK) { SafeStore.unlock();setupFrameworkLock() } else render()
        }
    }
    private fun enterCredential(kind:String,title:String,done:(String)->Unit) {
        if(kind==SafeStore.LOCK_PATTERN) {
            val pattern=PatternInput(this)
            AlertDialog.Builder(this).setTitle(title)
                .setMessage("Connect at least 4 distinct dots")
                .setView(pattern)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Continue") {_,_->
                    val value=pattern.value
                    if(!SafeStore.validFormat(kind,value))msg("Use at least 4 different dots")
                    else done(value)
                }.create().also { secureDialog ->
                    secureDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureDialog.show()
                }
        } else {
            val field=EditText(this).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint="6–12 digit PIN"; setPadding(dp(24),dp(16),dp(24),dp(16))
                filters=arrayOf(android.text.InputFilter.LengthFilter(12))
            }
            AlertDialog.Builder(this).setTitle(title).setView(field)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Continue") {_,_->
                    val value=field.text.toString()
                    if(!SafeStore.validFormat(kind,value))msg("Enter a 6–12 digit PIN") else done(value)
                    field.text.clear()
                }.create().also { secureDialog ->
                    secureDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureDialog.show()
                }
        }
    }
}

/** Gesture-driven 3x3 pattern grid, not a text imitation of Android pattern lock. */
private class PatternInput(ctx:android.content.Context): View(ctx) {
    var value="";private set
    private val paint=Paint(3).apply {strokeWidth=8f;strokeCap=Paint.Cap.ROUND}
    private val nodes=mutableListOf<Int>()
    private val path=Path()
    private var x=0f;private var y=0f
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val size=(resources.displayMetrics.density*260).toInt()
        setMeasuredDimension(resolveSize(size,widthMeasureSpec),resolveSize(size,heightMeasureSpec))
    }
    private fun cx(col:Int)=width*(col+1)/4f
    private fun cy(row:Int)=height*(row+1)/4f
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        paint.color=Color.CYAN;paint.style=Paint.Style.STROKE
        if(nodes.isNotEmpty()) {
            path.reset();nodes.forEachIndexed {i,id->
                val px=cx((id-1)%3);val py=cy((id-1)/3)
                if(i==0)path.moveTo(px,py) else path.lineTo(px,py)
            }
            canvas.drawPath(path,paint)
        }
        paint.style=Paint.Style.FILL
        for(id in 1..9) {paint.color=if(id in nodes)Color.CYAN else Color.GRAY
            canvas.drawCircle(cx((id-1)%3),cy((id-1)/3),13f,paint)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { nodes.clear(); value="";x=e.x;y=e.y }
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                val col=(0..2).minByOrNull {kotlin.math.abs(e.x-cx(it))} ?: 0
                val row=(0..2).minByOrNull {kotlin.math.abs(e.y-cy(it))} ?: 0
                val id=row*3+col+1
                val dx=e.x-cx(col);val dy=e.y-cy(row)
                if(dx*dx+dy*dy < (width/7f)*(height/7f) && id !in nodes)nodes.add(id)
                if(e.actionMasked==MotionEvent.ACTION_UP) value=nodes.joinToString("")
            }
        }
        invalidate();return true
    }
}
