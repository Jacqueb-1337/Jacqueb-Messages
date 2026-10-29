package org.fossify.messages

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import org.fossify.messages.safe.SafeVault
import org.fossify.messages.safe.SafeStore
import org.fossify.messages.safe.SafeActivity
import org.fossify.messages.extensions.conversationsDB

/** Runs against actual Android preferences and the real SafeStore on an emulator. */
class SafeRegressionInstrumentation : Instrumentation() {
    private fun verifyKeyboard(context: Context, id: Long, result: Bundle) {
        val serviceClass = org.fossify.messages.chatheads.FloatingChatHeadsService::class.java
        val launch = android.content.Intent(context, serviceClass).apply {
            action = org.fossify.messages.chatheads.FloatingChatHeadsService.ACTION_ADD
            putExtra("thread_id", id); putExtra("title", "Keyboard motion test")
            putExtra("address", "5550100"); putExtra("expand_on_tap", true)
        }
        onUi { context.startForegroundService(launch) }
        val serviceField = serviceClass.getDeclaredField("activeService").apply { isAccessible = true }
        val panelField = serviceClass.getDeclaredField("panel").apply { isAccessible = true }
        var panel: android.view.ViewGroup? = null
        repeat(60) {
            val service = (serviceField.get(null) as? java.lang.ref.WeakReference<*>)?.get()
            if (service != null) panel = panelField.get(service) as? android.view.ViewGroup
            if (panel == null) SystemClock.sleep(50)
        }
        val view = checkNotNull(panel) { "Floating panel did not start" }
        SystemClock.sleep(650)
        waitForIdleSync()
        val panelClass = view.javaClass
        fun field(name: String) = panelClass.getDeclaredField(name).apply { isAccessible = true }
        val input = field("input").get(view) as android.widget.EditText
        val card = field("motionCard").get(view) as android.view.View
        val currentInsets = field("currentImeInsets")
        val animating = field("imeAnimating")
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        val density = context.resources.displayMetrics.density
        val observer = android.view.ViewTreeObserver.OnPreDrawListener {
            val insets = currentInsets.get(view) as? android.view.WindowInsets
            if (insets != null && animating.getBoolean(view)) {
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                val xy = IntArray(2); card.getLocationOnScreen(xy)
                val bottom = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).currentWindowMetrics.bounds.bottom
                val rootPosition = IntArray(2); view.getLocationOnScreen(rootPosition)
                val alreadyExcluded = (bottom - rootPosition[1] - view.height).coerceAtLeast(0)
                samples.add(Triple(ime, xy[1], xy[1] + card.height -
                    (bottom - maxOf(ime, nav, alreadyExcluded) - (12 * density).toInt())))
            }
            true
        }
        onUi {
            view.viewTreeObserver.addOnPreDrawListener(observer)
            input.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        // Exercise the real touch path so Android establishes a served input
        // connection for the overlay before requesting its IME.
        val inputPosition = IntArray(2)
        onUi { input.getLocationOnScreen(inputPosition) }
        val down = SystemClock.uptimeMillis()
        val x = inputPosition[0] + input.width / 2f
        val y = inputPosition[1] + input.height / 2f
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(down, down,
            android.view.MotionEvent.ACTION_DOWN, x, y, 0), true)
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(down, down + 40,
            android.view.MotionEvent.ACTION_UP, x, y, 0), true)
        SystemClock.sleep(300)
        onUi { input.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
        var keyboardShown = false
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!keyboardShown && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            onUi {
                val frame = currentInsets.get(view) as? android.view.WindowInsets
                keyboardShown = frame != null &&
                    frame.getInsets(android.view.WindowInsets.Type.ime()).bottom >
                    frame.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom + 100
            }
        }
        SystemClock.sleep(500)
        waitForIdleSync()
        result.putString("keyboard_open_state", "windowFocus=" + input.hasWindowFocus() +
            " viewFocus=" + input.hasFocus() + " ime=" +
            (currentInsets.get(view) as? android.view.WindowInsets)?.getInsets(android.view.WindowInsets.Type.ime())?.bottom +
            " rootIme=" + view.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom)
        val opening = samples.toList()
        samples.clear()
        onUi {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
        SystemClock.sleep(1200)
        waitForIdleSync()
        val closing = samples.toList()
        onUi {
            view.viewTreeObserver.removeOnPreDrawListener(observer)
            // Cleanup occurs after the collapse/reopen identity check below.

        }
        val service=(serviceField.get(null) as java.lang.ref.WeakReference<*>).get()!!
        val rows=panelClass.getDeclaredField("messages").apply{isAccessible=true}.get(view) as android.view.ViewGroup
        val rowBefore=rows.getChildAt(0)
        onUi {
            input.setText("Retained draft")
            serviceClass.getDeclaredMethod("collapse").apply{isAccessible=true}.invoke(service)
        }
        val stripField=serviceClass.getDeclaredField("expandedStrip").apply{isAccessible=true}
        val collapseDeadline=SystemClock.uptimeMillis()+2500
        while(stripField.get(service)!=null && SystemClock.uptimeMillis()<collapseDeadline)SystemClock.sleep(50)
        onUi {
            (service as org.fossify.messages.chatheads.FloatingChatHeadsService).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            serviceClass.getDeclaredMethod("expand").apply{isAccessible=true}.invoke(service)
        }
        val reopenDeadline=SystemClock.uptimeMillis()+1000
        while(panelField.get(service)==null && SystemClock.uptimeMillis()<reopenDeadline)SystemClock.sleep(10)
        onUi {
            check(panelField.get(service) === view) { "Reopening rebuilt the conversation panel" }
            check(rows.getChildAt(0) === rowBefore) { "Reopening rebuilt message views" }
            check(input.text.toString()=="Retained draft") { "Reopening lost the draft" }
            context.stopService(launch)
        }
        result.putString("chat_head_reopen","PASS: same panel and message view instances, draft preserved")
        fun validate(label: String, frames: List<Triple<Int, Int, Int>>) {
            check(frames.map { it.first }.distinct().size >= 3) { "$label: missing animated IME frames: $frames" }
            check(frames.maxOf { kotlin.math.abs(it.third) } <= 3) { "$label: card detached from keyboard: $frames" }
            check(frames.maxOf { it.second } - frames.minOf { it.second } <= 2) { "$label: card top moved during resize: $frames" }
        }
        validate("opening", opening)
        validate("closing", closing)
        result.putString("keyboard_motion", "PASS: opening=" + opening.size + " closing=" + closing.size +
            " frames; card top fixed; bottom tracks actual animated IME within 3px")
    }
    private fun onUi(block:()->Unit) {
        var failure:Throwable?=null
        runOnMainSync { try{block()}catch(error:Throwable){failure=error} }
        failure?.let{throw it}
    }
    private fun verifyMainKeyboard(c:Context,id:Long,result:Bundle) {
        val activity=startActivitySync(android.content.Intent(c,org.fossify.messages.activities.ThreadActivity::class.java)
            .putExtra(org.fossify.messages.helpers.THREAD_ID,id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync();SystemClock.sleep(600)
        val input=activity.findViewById<android.widget.EditText>(org.fossify.messages.R.id.thread_type_message)
        val rows=activity.findViewById<android.view.ViewGroup>(org.fossify.messages.R.id.thread_messages_list)
        val motion=activity.javaClass.getDeclaredField("reactiveKeyboard").apply{isAccessible=true}.get(activity)
            as org.fossify.messages.theming.ReactiveKeyboardMotion
        val velocity=motion.javaClass.getDeclaredField("inputVelocity").apply{isAccessible=true}
        var duringMotion=false
        var maxDeflection=0f
        val observer=android.view.ViewTreeObserver.OnPreDrawListener {
            for(i in 0 until rows.childCount) {
                maxDeflection=maxOf(maxDeflection,kotlin.math.abs(rows.getChildAt(i).translationY))
                if(kotlin.math.abs(velocity.getFloat(motion))>10f && kotlin.math.abs(rows.getChildAt(i).translationY)>.5f) duringMotion=true
            }
            true
        }
        val xy=IntArray(2)
        onUi { rows.viewTreeObserver.addOnPreDrawListener(observer);input.requestFocus();input.getLocationOnScreen(xy) }
        val t=SystemClock.uptimeMillis()
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(t,t,0,xy[0]+input.width/2f,xy[1]+input.height/2f,0),true)
        uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(t,t+30,1,xy[0]+input.width/2f,xy[1]+input.height/2f,0),true)
        onUi { input.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
        val deadline=SystemClock.uptimeMillis()+8000
        while(!duringMotion && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100)
        SystemClock.sleep(500)
        onUi { input.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        SystemClock.sleep(700);waitForIdleSync()
        onUi { rows.viewTreeObserver.removeOnPreDrawListener(observer);input.text.clear();activity.finish() }
        check(motion.drivenFrames>=3 && duringMotion) {"Main conversation physics did not react during keyboard travel: frames="+motion.drivenFrames+" displacement="+maxDeflection}
        result.putString("main_keyboard","PASS: springs driven during IME travel; frames="+motion.drivenFrames+" max displacement="+maxDeflection)
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val c=targetContext
        val result=Bundle()
        val prefs=c.getSharedPreferences("messages_private_safe_v1",0)
        val snapshot=prefs.all.toMap()
        val address="+15550134455"
        var created=false
        var thread=0L
        var code=Activity.RESULT_OK
        fun count(uri:String,id:Long)=c.contentResolver.query(android.net.Uri.parse(uri),arrayOf("_id"),"thread_id=?",arrayOf(id.toString()),null)!!.use{it.count}
        try {
            check(SafeVault.isDefault(c)){"Test app must hold the default SMS role"}
            SafeStore.saveFrameworkLock(c,"test-only-hash",0);SafeStore.unlock()
            thread=android.provider.Telephony.Threads.getOrCreateThreadId(c,address)
            val prior=c.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI,arrayOf("body"),"thread_id=?",arrayOf(thread.toString()),null)!!.use { cur ->
                buildList { while(cur.moveToNext())add(cur.getString(0).orEmpty()) }
            }
            if(prior.isNotEmpty() && prior.all{it.startsWith("VAULT-PRIVATE-FIXTURE-")}) {
                c.contentResolver.delete(android.provider.Telephony.Sms.CONTENT_URI,"thread_id=?",arrayOf(thread.toString()))
                c.contentResolver.delete(android.provider.Telephony.Mms.CONTENT_URI,"thread_id=?",arrayOf(thread.toString()))
            }
            check(count("content://sms",thread)==0 && count("content://mms",thread)==0){"Fixture address already in use"}
            created=true
            val marker="VAULT-PRIVATE-FIXTURE-"+SystemClock.uptimeMillis()
            val date=System.currentTimeMillis()-30000
            repeat(3) { n->
                val values=android.content.ContentValues().apply{
                    put("thread_id",thread);put("address",address);put("body",marker+if(n<2)" duplicate" else " distinct")
                    put("date",date);put("date_sent",date-100);put("type",1);put("read",1);put("seen",1);put("sub_id",-1)
                }
                check(c.contentResolver.insert(android.provider.Telephony.Sms.CONTENT_URI,values)!=null)
            }
            val mmsUri=c.contentResolver.insert(android.provider.Telephony.Mms.CONTENT_URI,android.content.ContentValues().apply{
                put("thread_id",thread);put("date",date/1000);put("date_sent",date/1000);put("m_type",132);put("msg_box",1)
                put("read",1);put("seen",1);put("sub_id",-1);put("text_only",0);put("ct_t","application/vnd.wap.multipart.related")
                put("sub","Private fixture image")
            })!!
            val mid=mmsUri.lastPathSegment!!
            c.contentResolver.insert(android.net.Uri.parse("content://mms/"+mid+"/addr"),android.content.ContentValues().apply{
                put("address",address);put("type",137);put("charset",106)
            })
            c.contentResolver.insert(android.net.Uri.parse("content://mms/"+mid+"/part"),android.content.ContentValues().apply{
                put("ct","text/plain");put("text",marker+" attachment");put("seq",0);put("chset",106)
            })
            val part=c.contentResolver.insert(android.net.Uri.parse("content://mms/"+mid+"/part"),android.content.ContentValues().apply{
                put("ct","image/png");put("seq",1);put("cl","fixture.png");put("name","fixture.png")
            })!!
            val image=android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1S8AAAAASUVORK5CYII=",0)
            c.contentResolver.openOutputStream(part)!!.use{it.write(image)}
            c.conversationsDB.insertOrUpdate(org.fossify.messages.models.Conversation(thread,marker,(date/1000).toInt(),true,"Safe regression","",false,address))
            SafeVault.testFault="after_encrypted_write"
            check(runCatching{SafeStore.move(c,thread,true)}.isFailure)
            check(count("content://sms",thread)==3 && count("content://mms",thread)==1){"Provider deleted before verified archive"}
            check(SafeVault.read(c,thread).getJSONArray("records").length()==4)
            c.contentResolver.update(android.provider.Telephony.Sms.CONTENT_URI,
                android.content.ContentValues().apply{put("read",0)},"thread_id=?",arrayOf(thread.toString()))
            SafeVault.testFault="after_provider_delete"
            check(runCatching{SafeStore.move(c,thread,true)}.isFailure)
            check(count("content://sms",thread)==2)
            SafeVault.testFault=null
            check(SafeStore.move(c,thread,true))
            check(count("content://sms",thread)==0 && count("content://mms",thread)==0){"Public rows remain after capture"}
            check(SafeVault.read(c,thread).getJSONArray("records").length()==4){"Capture retry duplicated records"}
            val archiveFile=java.io.File(c.noBackupFilesDir,"safe-vault-v2/"+thread+"/manifest.aes")
            val encrypted=archiveFile.readBytes()
            check(!encrypted.toString(Charsets.ISO_8859_1).contains(marker)){"Archive contains plaintext"}
            val damaged=encrypted.copyOf();damaged[damaged.lastIndex]=(damaged.last().toInt() xor 1).toByte()
            archiveFile.writeBytes(damaged)
            check(runCatching{SafeVault.restore(c,thread)}.isFailure){"Tampered archive was accepted"}
            check(count("content://sms",thread)==0)
            archiveFile.writeBytes(encrypted)
            SafeStore.lock()
            check(SafeVault.appendIncoming(c,address,marker+" private arrival",date+5000,-1,0))
            check(count("content://sms",thread)==0){"Locked private SMS leaked to provider"}
            check(runCatching{SafeVault.read(c,thread)}.isFailure){"Locked UI could decrypt archive"}
            SafeStore.unlock()
            check(SafeVault.read(c,thread).getJSONArray("records").length()==5)
            val activity=startActivitySync(android.content.Intent(c,org.fossify.messages.safe.SafeConversationActivity::class.java)
                .putExtra(org.fossify.messages.helpers.THREAD_ID,thread).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync();SystemClock.sleep(700)
            onUi {
                check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                val view=activity.window.decorView
                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(c.getExternalFilesDir(null),"qa-encrypted-safe.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle();activity.finish()
            }
            waitForIdleSync()
            SafeStore.unlock()
            SafeVault.testFault="after_restore_insert"
            check(runCatching{SafeStore.move(c,thread,false)}.isFailure)
            check(SafeVault.exists(c,thread)){"Interrupted restore discarded archive"}
            SafeVault.testFault=null
            SafeVault.restore(c,thread)
            check(!SafeVault.appendIncoming(c,address,marker+" during restore",date+7000,-1,0)) {
                "A post-snapshot arrival must route to normal delivery"
            }
            check(SafeStore.move(c,thread,false))
            check(!SafeVault.exists(c,thread))
            val restoredThread=android.provider.Telephony.Threads.getOrCreateThreadId(c,address)
            check(count("content://sms",restoredThread)==4){"Restore lost or duplicated SMS"}
            check(count("content://mms",restoredThread)==1){"Restore lost or duplicated MMS"}
            val restoredMid=c.contentResolver.query(android.provider.Telephony.Mms.CONTENT_URI,arrayOf("_id"),"thread_id=?",arrayOf(restoredThread.toString()),null)!!.use{it.moveToFirst();it.getLong(0)}
            val restoredPart=c.contentResolver.query(android.net.Uri.parse("content://mms/"+restoredMid+"/part"),arrayOf("_id"),"ct=?",arrayOf("image/png"),null)!!.use{it.moveToFirst();it.getLong(0)}
            check(c.contentResolver.openInputStream(android.net.Uri.parse("content://mms/part/"+restoredPart))!!.use{it.readBytes()}.contentEquals(image)){"MMS image changed"}
            result.putString("encrypted_safe","PASS: SMS duplicates + MMS image, interruption before/after deletion, corrupt data rejection, locked incoming, interrupted restore retry")
            verifyMainKeyboard(c,restoredThread,result)
            // The inherited normal-screen startup may leave the emulator's
            // default-app chooser open. Sensitive system dialogs intentionally
            // block overlay focus, so dismiss it before measuring the overlay.
            if(uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.google.android.permissioncontroller") {
                uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                SystemClock.sleep(400);waitForIdleSync()
            }
            verifyKeyboard(c,restoredThread,result)
        } catch(error:Throwable) {
            code=Activity.RESULT_CANCELED;result.putString("failure",error.stackTraceToString())
        } finally {
            SafeVault.testFault=null;SafeStore.lock()
            // Only synthetic records for the test-owned address are removed.
            if(created) {
            val id=android.provider.Telephony.Threads.getOrCreateThreadId(c,address)
            c.contentResolver.delete(android.provider.Telephony.Sms.CONTENT_URI,"thread_id=?",arrayOf(id.toString()))
            c.contentResolver.delete(android.provider.Telephony.Mms.CONTENT_URI,"thread_id=?",arrayOf(id.toString()))
            if(thread>0)java.io.File(c.noBackupFilesDir,"safe-vault-v2/"+thread).deleteRecursively()
            }
            val edit=prefs.edit().clear()
            snapshot.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}}
            edit.commit()
        }
        finish(code,result)
    }
}
