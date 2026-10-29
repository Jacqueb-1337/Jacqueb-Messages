package org.fossify.messages
import android.app.*
import android.os.*
import android.content.*
import androidx.core.content.FileProvider
import org.fossify.messages.messaging.MmsAttachmentPreparer
import org.fossify.messages.extensions.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
open class MmsPreparationInstrumentation: Instrumentation() {
    override fun onCreate(args:Bundle?){super.onCreate(args);start()}
    private fun click(text:String) {
        val deadline=SystemClock.uptimeMillis()+5000
        while(SystemClock.uptimeMillis()<deadline) {
            val nodes=uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            if(nodes.any{it.text?.toString()==text && it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)})return
            SystemClock.sleep(50)
        }
        error("Missing dialog button: "+text)
    }
    override fun onStart() {
        val result=Bundle();var code=Activity.RESULT_CANCELED
        val c=targetContext
        val old=c.config.mmsFileSizeLimit
        var activity:Activity?=null
        var original:android.net.Uri?=null;var replacement:android.net.Uri?=null
        try {
            activity=startActivitySync(Intent(c,MmsPreparationTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val a=activity!!
            c.config.mmsFileSizeLimit=100_000L
            val dir=File(c.cacheDir,"attachments").apply{mkdirs()}
            val input=File(dir,"synthetic-mms-test.mp4")
            context.assets.open("mms-test.mp4").use{source->input.outputStream().use{source.copyTo(it)}}
            val uri=FileProvider.getUriForFile(c,c.packageName+".provider",input)
            val target=MmsAttachmentPreparer.limit(a,-1)
            check(input.length()>target)
            var called=false
            runOnMainSync { MmsAttachmentPreparer.prepare(a,uri,-1){called=true} }
            click("Cancel");SystemClock.sleep(200);check(!called);check(input.exists())
            val latch=CountDownLatch(1);var output:android.net.Uri?=null
            runOnMainSync{MmsAttachmentPreparer.prepare(a,uri,-1){output=it;latch.countDown()}}
            click("Compress")
            check(latch.await(90,TimeUnit.SECONDS)){"Compression did not produce a file"}
            check(c.getFileSizeFromUri(output!!) in 1..target)
            check(input.exists())
            val extractor=android.media.MediaExtractor()
            try {
                extractor.setDataSource(c,output!!,null)
                val formats=(0 until extractor.trackCount).map{extractor.getTrackFormat(it).getString("mime").orEmpty()}
                check(formats.any{it.startsWith("video/")} && formats.any{it.startsWith("audio/")}){"Video or audio lost: "+formats}
            } finally{extractor.release()}
            c.config.mmsFileSizeLimit=0
            check(MmsAttachmentPreparer.limit(a,-1)>0 && MmsAttachmentPreparer.limit(a,-1)<Long.MAX_VALUE)
            result.putString("compression","PASS: cancel preserves original; oversized video compressed under target with video+audio; no-limit retains carrier ceiling")
            fun fixture()=c.contentResolver.insert(android.provider.Telephony.Mms.CONTENT_URI,android.content.ContentValues().apply{put("msg_box",4);put("m_type",128);put("date",System.currentTimeMillis()/1000)})!!
            original=fixture();replacement=fixture()
            val address="+15550134456"
            val thread=android.provider.Telephony.Threads.getOrCreateThreadId(c,address)
            c.contentResolver.update(original!!,android.content.ContentValues().apply{put("thread_id",thread);put("msg_box",5)},null,null)
            val originalId=original!!.lastPathSegment!!.toLong()
            c.contentResolver.insert(android.net.Uri.parse("content://mms/"+originalId+"/addr"),android.content.ContentValues().apply{put("address",address);put("type",151);put("charset",106)})
            val part=c.contentResolver.insert(android.net.Uri.parse("content://mms/"+originalId+"/part"),android.content.ContentValues().apply{put("mid",originalId);put("ct","video/mp4");put("name","synthetic-original.mp4");put("cl","synthetic-original.mp4");put("seq",0)})!!
            val partUri=android.net.Uri.parse("content://mms/part/"+part.lastPathSegment)
            c.contentResolver.openOutputStream(partUri)!!.use{sink->input.inputStream().use{it.copyTo(sink)}}
            check(MmsAttachmentPreparer.size(c,partUri,100000)==input.length()){"MMS-provider sizing failed"}
            val chat=startActivitySync(Intent(c,org.fossify.messages.activities.ThreadActivity::class.java)
                .putExtra(org.fossify.messages.helpers.THREAD_ID,thread).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                val deadline=SystemClock.uptimeMillis()+10000
                var tapped=false
                while(!tapped && SystemClock.uptimeMillis()<deadline) {
                    val nodes=uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Not sent").orEmpty()
                    tapped=nodes.any{it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)}
                    if(!tapped)SystemClock.sleep(100)
                }
                check(tapped){"Retry label was not available"}
                SystemClock.sleep(400);waitForIdleSync()
                var selected:List<org.fossify.messages.models.AttachmentSelection> = emptyList()
                runOnMainSync {
                    @Suppress("UNCHECKED_CAST")
                    val list=chat.javaClass.getDeclaredMethod("getAttachmentSelections").apply{isAccessible=true}.invoke(chat) as List<org.fossify.messages.models.AttachmentSelection>
                    selected=list
                }
                check(selected.size==1 && selected[0].uri==partUri && !selected[0].isPending){"Retry did not restore original attachment: "+selected}
                check(uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Attachment too large").orEmpty().isEmpty()){"Retry restoration compressed before Send"}
                check(MmsAttachmentPreparer.size(c,partUri,100000)==input.length()){"Stored original changed"}
                result.putString("retry_ui","PASS: actual failed-video label tap restores oversized original without compression or a size prompt")
            } finally{runOnMainSync{chat.finish()}}
            val sent=Intent().putExtra("content_uri",replacement.toString()).putExtra("original_message_id",original!!.lastPathSegment!!.toLong())
            org.fossify.messages.receivers.MmsSentReceiver().updateAndroidDatabase(c,sent,5)
            val count=c.contentResolver.query(original!!,arrayOf("_id"),null,null,null)!!.use{it.count}
            check(count==1){"Failed retry deleted original"}
            result.putString("retry_retention","PASS: failed retry retains original provider message")
            code=Activity.RESULT_OK
        } catch(t:Throwable){result.putString("failure",t.stackTraceToString())}
        finally{
            c.config.mmsFileSizeLimit=old
            original?.let{c.contentResolver.delete(it,null,null)};replacement?.let{c.contentResolver.delete(it,null,null)}
            activity?.let{runOnMainSync{it.finish()}}
        }
        finish(code,result)
    }
}
