package org.fossify.messages.safe

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.theming.UiTheme
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.commons.helpers.ensureBackgroundThread
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** No public provider reads, public attachment files, disk image cache or shared drafts. */
class SafeConversationActivity:SimpleActivity() {
    private var id=0L
    private lateinit var root:LinearLayout
    private lateinit var rows:LinearLayout
    private lateinit var input:EditText
    private lateinit var scroll:ScrollView
    private lateinit var toolbar:com.google.android.material.appbar.MaterialToolbar
    private var stamp=-1L
    private var loading=false
    private var draftLoaded=false
    private val handler=Handler(Looper.getMainLooper())
    private var physics:org.fossify.messages.theming.ReactiveKeyboardMotion?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private val poll=object:Runnable {
        override fun run() {
            if (!SafeStore.unlocked()) { finish();return }
            refresh();handler.postDelayed(this,800)
        }
    }
    override fun onCreate(state:Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(state)
        id=intent.getLongExtra(THREAD_ID,0)
        if(!SafeStore.unlocked()||!SafeVault.exists(this,id)){finish();return}
        root=LinearLayout(this).apply{orientation=1;setBackgroundColor(UiTheme.color(this@SafeConversationActivity,"surface"))}
        toolbar=com.google.android.material.appbar.MaterialToolbar(this).apply{
            title="Safe";setTitleTextColor(UiTheme.color(this@SafeConversationActivity,"title"))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);setNavigationOnClickListener{finish()}
        }
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        rows=LinearLayout(this).apply{orientation=1;setPadding(dp(12),dp(10),dp(12),dp(10))}
        scroll=ScrollView(this).apply{isFillViewport=true;addView(rows)}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val composer=LinearLayout(this).apply{gravity=Gravity.BOTTOM;setPadding(dp(12),dp(8),dp(12),dp(8))}
        input=EditText(this).apply{hint="Private SMS";maxLines=4;setTextColor(UiTheme.color(this@SafeConversationActivity,"title"))}
        composer.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        composer.addView(Button(this).apply{text="Send";setOnClickListener{sendPrivate()}})
        root.addView(composer)
        setContentView(root)
        physics=org.fossify.messages.theming.ReactiveKeyboardMotion(rows){listOf(composer)}
        var animating=false
        fun apply(insets:WindowInsetsCompat) {
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            root.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime));physics?.frame(ime)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root){_,i->if(!animating)apply(i);i}
        ViewCompat.setWindowInsetsAnimationCallback(root,object:androidx.core.view.WindowInsetsAnimationCompat.Callback(1){
            override fun onPrepare(a:androidx.core.view.WindowInsetsAnimationCompat){if(a.typeMask and WindowInsetsCompat.Type.ime()!=0)animating=true}
            override fun onProgress(i:WindowInsetsCompat,a:MutableList<androidx.core.view.WindowInsetsAnimationCompat>):WindowInsetsCompat{apply(i);return i}
            override fun onEnd(a:androidx.core.view.WindowInsetsAnimationCompat){animating=false;physics?.finish()}
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
    override fun onResume(){super.onResume();if(::root.isInitialized)handler.post(poll)}
    override fun onStop(){
        handler.removeCallbacksAndMessages(null);physics?.reset()
        if(::input.isInitialized) {
            val draft=input.text.toString()
            runCatching{SafeVault.saveDraft(this,id,draft)}
                .onFailure{android.util.Log.e("Safe","Could not flush encrypted draft",it)}
            input.text.clear();draftLoaded=false
        }
        if(::rows.isInitialized)rows.removeAllViews()
        stamp=-1;super.onStop()
        if(!isFinishing)SafeStore.lock()
    }
    private fun refresh() {
        val modified=SafeVault.modified(this,id)
        if(loading||modified==stamp)return
        loading=true
        ensureBackgroundThread {
            val result=runCatching{SafeVault.read(this,id)}
            runOnUiThread{
                loading=false
                if(isFinishing||!SafeStore.unlocked())return@runOnUiThread
                result.onSuccess{a->stamp=modified;render(a)}.onFailure{Toast.makeText(this,it.message,1).show()}
            }
        }
    }
    private fun render(a:JSONObject) {
        val wasBottom=rows.height-scroll.height-scroll.scrollY<dp(40)
        toolbar.title=a.getString("title");rows.removeAllViews()
        if(!draftLoaded){input.setText(a.optString("draft"));draftLoaded=true}
        val data=a.getJSONArray("records")
        (0 until data.length()).map{data.getJSONObject(it)}.sortedBy{SafeVault.date(it)}.forEach{record->
            val sent=record.getJSONObject("row").optInt(if(record.getString("kind")=="sms")"type" else "msg_box")!=1
            val bubble=LinearLayout(this).apply{
                orientation=1;setPadding(dp(14),dp(10),dp(14),dp(8))
                background=GradientDrawable().apply{cornerRadius=dp(18).toFloat();setColor(UiTheme.color(this@SafeConversationActivity,if(sent)"accent" else "card"))}
            }
            bubble.addView(TextView(this).apply{text=SafeVault.body(record);textSize=16f;setTextColor(if(sent)Color.WHITE else UiTheme.color(this@SafeConversationActivity,"title"))})
            if(record.has("parts")) {
                val parts=record.getJSONArray("parts")
                for(i in 0 until parts.length()) {
                    val part=parts.getJSONObject(i)
                    if(part.optString("ct").startsWith("image/")&&part.has("safe_blob")) {
                        val image=ImageView(this);bubble.addView(image,LinearLayout.LayoutParams(dp(220),dp(160)))
                        com.bumptech.glide.Glide.with(this).load(SafeVault.attachment(this,id,part))
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE).skipMemoryCache(true).fitCenter().into(image)
                    }
                }
            }
            bubble.addView(TextView(this).apply{
                text=java.text.DateFormat.getDateTimeInstance(3,3).format(java.util.Date(SafeVault.date(record)))+
                    if(record.getJSONObject("row").optInt("type")==5)" - Failed" else ""
                textSize=10f;alpha=.65f;setTextColor(if(sent)Color.WHITE else UiTheme.color(this@SafeConversationActivity,"title"))
            })
            rows.addView(bubble,LinearLayout.LayoutParams(-2,-2).apply{gravity=if(sent)Gravity.END else Gravity.START;bottomMargin=dp(8);if(sent)leftMargin=dp(42) else rightMargin=dp(42)})
        }
        if(a.getJSONArray("recipients").length()>1){input.isEnabled=false;input.hint="Restore this group to the inbox to reply"}
        if(wasBottom)scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}
    }
    private fun sendPrivate() {
        val text=input.text.toString();if(text.isBlank()||!SafeStore.unlocked())return
        ensureBackgroundThread {
            var key:String?=null
            var partCount=1
            val result=runCatching {
                val manager=getSystemService(android.telephony.SmsManager::class.java)
                val parts=manager.divideMessage(text)
                partCount=parts.size
                val prepared=SafeVault.prepareSms(this,id,text,parts.size);key=prepared.first
                val callbacks=ArrayList<android.app.PendingIntent>()
                parts.indices.forEach { part ->
                    callbacks.add(android.app.PendingIntent.getBroadcast(this,part,
                        Intent(this,SafeSmsSentReceiver::class.java).setData(android.net.Uri.parse("safe-sms:"+prepared.first+":"+part))
                            .putExtra("archive",id).putExtra("entry",prepared.first).putExtra("part",part),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                }
                manager.sendMultipartTextMessage(prepared.second,null,parts,callbacks,null)
            }
            result.exceptionOrNull()?.let{if(key!=null)repeat(partCount){part->SafeVault.sentResult(this,id,key!!,false,part)}}
            runOnUiThread{if(result.isSuccess)input.text.clear() else Toast.makeText(this,result.exceptionOrNull()?.message,1).show();refresh()}
        }
    }
}
class SafeSmsSentReceiver:android.content.BroadcastReceiver() {
    override fun onReceive(context:android.content.Context,intent:Intent) {
        val pending=goAsync();val ok=resultCode==android.app.Activity.RESULT_OK
        org.fossify.commons.helpers.ensureBackgroundThread {
            try{runCatching { SafeVault.sentResult(context,intent.getLongExtra("archive",0),intent.getStringExtra("entry").orEmpty(),ok,intent.getIntExtra("part",0)) }
                .onFailure{android.util.Log.e("Safe","Could not save private send result",it)}}finally{pending.finish()}
        }
    }
}
