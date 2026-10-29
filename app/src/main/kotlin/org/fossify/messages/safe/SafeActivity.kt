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

/** Always secure from the very first frame and locked after app backgrounding. */
class SafeActivity: SimpleActivity() {
    private lateinit var main: LinearLayout
    private var launchingThread=false
    private var deviceAuthPending=false
    private val accent by lazy { UiTheme.color(this,"accent") }
    private fun dp(n:Int)=(resources.displayMetrics.density*n).toInt()
    private fun label(t:String,size:Float=16f):TextView = TextView(this).apply {
        text=t;textSize=size;setTextColor(UiTheme.color(this@SafeActivity,"title"))
        setPadding(dp(3),dp(13),dp(3),dp(13))
    }
    private fun action(t:String,callback:()->Unit) {
        main.addView(label(t,17f).apply { setOnClickListener { callback() } })
    }
    private fun msg(t:String)=Toast.makeText(this,t,Toast.LENGTH_SHORT).show()
    override fun onCreate(savedInstanceState:Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        main=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(14),dp(20),dp(20))}
        val scroll=ScrollView(this).apply {addView(main);setBackgroundColor(UiTheme.color(this@SafeActivity,"surface"))}
        setContentView(scroll)
        findViewById<View>(android.R.id.content)?.setPadding(0,dp(30),0,dp(28))
        render()
    }
    override fun onResume() {
        super.onResume()
        launchingThread=false
        if(::main.isInitialized && !deviceAuthPending) render()
    }
    override fun onStop() {
        if(!launchingThread) SafeStore.lock()
        super.onStop()
    }
    private fun render() {
        main.removeAllViews()
        main.addView(label("‹       Safe",26f).apply {setOnClickListener { finish() }})
        if(!SafeStore.configured(this)) return setup()
        if(!SafeStore.unlocked()) return locked()
        val pending=intent.getLongExtra("safe_add_thread",-1L)
        if(pending>0 && !SafeStore.isSafe(this,pending)) {
            intent.removeExtra("safe_add_thread")
            SafeStore.move(this,pending,true)
            msg("Moved conversation to Safe")
        }
        main.addView(label("Private conversations",18f))
        action("Notification privacy: ${if(SafeStore.notificationMode(this)==SafeStore.ALERT_OFF) "Off" else "Redacted"}") {
            val values=arrayOf("Notifications off","Show generic notification only")
            AlertDialog.Builder(this).setTitle("Safe notifications")
                .setSingleChoiceItems(values,if(SafeStore.notificationMode(this)==SafeStore.ALERT_OFF)0 else 1) {d,which->
                    SafeStore.setNotificationMode(this,if(which==0) SafeStore.ALERT_OFF else SafeStore.ALERT_REDACTED)
                    d.dismiss();render()
                }.setNegativeButton("Cancel",null).create().also { secureDialog ->
                    secureDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureDialog.show()
                }
        }
        action("Lock Safe now") { SafeStore.lock();render() }
        main.addView(label("Conversations moved to Safe are hidden from the normal inbox and app search. Other SMS apps with permission can still read Android's shared message database.",12f))
        ensureBackgroundThread {
            val ids=SafeStore.safeThreads(this)
            val all=try { conversationsDB.getNonArchived()+conversationsDB.getAllArchived() } catch (_:Exception) {emptyList()}
            val matches=all.filter { it.threadId in ids }.sortedByDescending { it.date }
            runOnUiThread {
                if(isFinishing || isDestroyed || !SafeStore.unlocked())return@runOnUiThread
                main.addView(label(if(matches.isEmpty()) "No conversations in Safe yet. Move one from its avatar menu in the inbox." else "${matches.size} private conversations",14f))
                matches.forEach {conv ->
                    action(conv.title) {
                        if(!SafeStore.unlocked()) {render();return@action}
                        launchingThread=true
                        startActivity(Intent(this,ThreadActivity::class.java).apply {
                            putExtra(THREAD_ID,conv.threadId);putExtra(THREAD_TITLE,conv.title)
                        })
                    }
                    action("Remove ${conv.title} from Safe") {
                        AlertDialog.Builder(this).setTitle("Move out of Safe?")
                            .setMessage("This conversation will reappear in your regular inbox.")
                            .setNegativeButton("Cancel",null)
                            .setPositiveButton("Move") { _,_ -> SafeStore.move(this,conv.threadId,false);render() }.create().also { secureDialog ->
                    secureDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureDialog.show()
                }
                    }
                }
            }
        }
    }
    private fun setup() {
        main.addView(label("Choose how to unlock Safe",20f))
        main.addView(label("An independent backup unlock method is required. Safe protects the Messages interface, not the shared Android SMS database.",13f))
        val types=arrayOf("Android screen lock / biometrics","App PIN (6–12 digits)","Pattern")
        AlertDialog.Builder(this).setTitle("Set primary lock")
            .setItems(types) { _,i ->
                val kind=listOf(SafeStore.LOCK_DEVICE,SafeStore.LOCK_PIN,SafeStore.LOCK_PATTERN)[i]
                if(kind==SafeStore.LOCK_DEVICE) {
                    val km=getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                    if(!km.isDeviceSecure) {msg("Set up an Android screen lock first");render();return@setItems}
                    setupBackup(kind,null)
                } else enterCredential(kind,"Create primary ${if(kind==SafeStore.LOCK_PIN) "PIN" else "pattern"}") { secret->
                    enterCredential(kind,"Confirm primary unlock method") { confirmation ->
                        if(secret!=confirmation) {msg("Primary entries did not match");render()}
                        else setupBackup(kind,secret)
                    }
                }
            }.setNegativeButton("Cancel") {_,_->finish()}.create().also { secureDialog ->
                    secureDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    secureDialog.show()
                }
    }
    private fun setupBackup(primary:String,secret:String?) {
        val backup=if(primary==SafeStore.LOCK_PIN) SafeStore.LOCK_PATTERN else SafeStore.LOCK_PIN
        enterCredential(backup,"Create required backup ${if(backup==SafeStore.LOCK_PIN)"PIN" else "pattern"}") { one ->
            enterCredential(backup,"Confirm backup method") { two ->
                if(one!=two) {msg("Backup entries did not match");setupBackup(primary,secret)}
                else {
                    try {SafeStore.configure(this,primary,secret,backup,one);render()}
                    catch (e:Exception) {msg("Could not create Safe: ${e.message}")}
                }
            }
        }
    }
    private fun locked() {
        main.addView(label("Safe is locked",20f))
        if(SafeStore.limited()) {main.addView(label("Too many attempts. Try again shortly.",14f));return}
        action("Unlock with primary method") {authenticate(SafeStore.primary(this))}
        action("Use backup ${if(SafeStore.backup(this)==SafeStore.LOCK_PIN)"PIN" else "pattern"}") {
            authenticate(SafeStore.backup(this))
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
                        deviceAuthPending=false;SafeStore.unlock();render()
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
            if(SafeStore.verify(this,kind,value))render() else {msg("Incorrect unlock method");render()}
        }
    }
    @Deprecated("Android system credential confirmation on API 28–29")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==7142) {
            deviceAuthPending=false
            if(resultCode==RESULT_OK) SafeStore.unlock()
            render()
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
