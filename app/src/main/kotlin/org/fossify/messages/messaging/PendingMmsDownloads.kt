package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.mms.transaction.DownloadManager
import com.google.android.mms.pdu_alt.PduHeaders
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.helpers.refreshConversations
import java.util.concurrent.ConcurrentHashMap

/** Carrier progress is not exposed. UI animates 0..10% while waiting,
 * then 10..100% only after a retrieved PDU exists in the provider. */
object PendingMmsDownloads {
    enum class Phase { CONNECTING, DOWNLOADING, PROCESSING, FINISHING, FAILED }
    data class Display(val phase:Phase,val startedAt:Long,val finishedAt:Long=0L,
        val error:String?=null)
    private data class Entry(var phase:Phase,val startedAt:Long,
        var finishedAt:Long=0L,var retrievedId:Long=-1L,var error:String?=null)
    private val entries=ConcurrentHashMap<Long,Entry>()
    private val handler=Handler(Looper.getMainLooper())
    private const val TIMEOUT_MS=120_000L
    const val FINISH_ANIMATION_MS=1650L
    private const val TAG="MessagesMMS"

    private fun prefs(ctx:Context)=ctx.applicationContext.getSharedPreferences(
        "mms_download_completed_v1",Context.MODE_PRIVATE)
    private fun completedId(ctx:Context,id:Long)=
        prefs(ctx).getLong("completed_"+id,-1L)
    private fun retrieved(ctx:Context,id:Long):Boolean {
        if(id<=0)return false
        val uri=Uri.withAppendedPath(Telephony.Mms.CONTENT_URI,id.toString())
        return runCatching{
            ctx.contentResolver.query(uri,arrayOf(Telephony.Mms.MESSAGE_TYPE),
                null,null,null)?.use{
                    it.moveToFirst() &&
                    it.getInt(0)==PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF
                }==true
        }.getOrDefault(false)
    }
    fun isResolved(ctx:Context,id:Long)=retrieved(ctx,completedId(ctx,id))
    fun display(id:Long):Display? {
        val state=entries[id]?:return null
        if(state.phase!=Phase.FINISHING && state.phase!=Phase.FAILED &&
            SystemClock.elapsedRealtime()-state.startedAt>TIMEOUT_MS) {
            state.phase=Phase.FAILED
            state.error="Download timed out. Tap to retry."
        }
        return Display(state.phase,state.startedAt,state.finishedAt,state.error)
    }
    fun isDownloading(id:Long)=display(id)?.phase in setOf(
        Phase.CONNECTING,Phase.DOWNLOADING,Phase.PROCESSING,Phase.FINISHING)
    fun shouldHoldRetrieved(id:Long)=entries.values.any{
        it.retrievedId==id && it.phase==Phase.FINISHING &&
            SystemClock.elapsedRealtime()-it.finishedAt<FINISH_ANIMATION_MS
    }
    fun shouldHideNotification(ctx:Context,id:Long):Boolean {
        if(!isResolved(ctx,id))return false
        val state=entries[id]
        return state==null || state.phase!=Phase.FINISHING ||
            SystemClock.elapsedRealtime()-state.finishedAt>=FINISH_ANIMATION_MS
    }
    fun processing(id:Long) {
        entries[id]?.let {it.phase=Phase.PROCESSING;refreshMessages()}
    }
    fun fail(id:Long?,message:String) {
        id ?: return
        entries[id]?.let{it.phase=Phase.FAILED;it.error=message;refreshMessages()}
    }
    fun clear() {
        // Never reset unrelated concurrent MMS requests here.
    }
    fun complete(ctx:Context,originalId:Long,downloadedUri:Uri):Boolean {
        val retrievedId=downloadedUri.lastPathSegment?.toLongOrNull()?:return false
        if(originalId==retrievedId || !retrieved(ctx,retrievedId))return false
        if(!prefs(ctx).edit().putLong("completed_"+originalId,retrievedId).commit())
            return false
        val state=entries.computeIfAbsent(originalId){
            Entry(Phase.FINISHING,SystemClock.elapsedRealtime())
        }
        state.phase=Phase.FINISHING
        state.finishedAt=SystemClock.elapsedRealtime()
        state.retrievedId=retrievedId
        refreshMessages()
        handler.postDelayed({
            if(isResolved(ctx,originalId)){
                val uri=Uri.withAppendedPath(Telephony.Mms.CONTENT_URI,originalId.toString())
                runCatching{
                    ctx.contentResolver.query(uri,arrayOf(Telephony.Mms.MESSAGE_TYPE),
                        null,null,null)?.use{c->
                        if(c.moveToFirst() &&
                           c.getInt(0)==PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                            ctx.contentResolver.delete(uri,null,null)
                    }
                }.onFailure{Log.w(TAG,"Unable to remove retrieved notification",it)}
            }
            entries.remove(originalId,state)
            refreshMessages()
            refreshConversations()
        },FINISH_ANIMATION_MS)
        return true
    }
    fun request(ctx:Context,id:Long):String? {
        if(isResolved(ctx,id))return "This MMS has already downloaded."
        if(isDownloading(id))return null
        val uri=Uri.withAppendedPath(Telephony.Mms.CONTENT_URI,id.toString())
        try {
            val columns=arrayOf(Telephony.Mms.MESSAGE_TYPE,Telephony.Mms.CONTENT_LOCATION,
                Telephony.Mms.TRANSACTION_ID,Telephony.Mms.SUBSCRIPTION_ID)
            val m=ctx.contentResolver.query(uri,columns,null,null,null)?.use{c->
                if(!c.moveToFirst())return@use null
                Triple(c.getInt(0),Pair(c.getString(1)?.trim().orEmpty(),
                    c.getString(2)?.takeIf{it.isNotBlank()}),c.getInt(3))
            }?:return "MMS notification no longer exists."
            if(m.first!=PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND)
                return "This MMS has already been retrieved."
            val location=m.second.first
            if(location.isBlank() || Uri.parse(location).scheme !in setOf("http","https"))
                return "Carrier MMS download address is unavailable."
            val sub=m.third.takeIf{it!=SubscriptionManager.INVALID_SUBSCRIPTION_ID}
                ?:SmsManager.getDefaultSmsSubscriptionId()
            var reserved=false
            val state=entries.compute(id){_,previous->
                if(previous==null || previous.phase==Phase.FAILED) {
                    reserved=true
                    Entry(Phase.CONNECTING,SystemClock.elapsedRealtime())
                } else previous
            } ?: return "Unable to start MMS download."
            if(!reserved)return null
            // The carrier may never call back (including during network loss).
            // Show Retry once the attempt actually times out.
            handler.postDelayed({
                if(entries[id]===state && state.phase !in setOf(Phase.FINISHING,Phase.FAILED)){
                    state.phase=Phase.FAILED
                    state.error="MMS download timed out. Tap to retry."
                    refreshMessages()
                }
            },TIMEOUT_MS)
            refreshMessages()
            try {
                DownloadManager.getInstance().downloadMultimediaMessage(
                    ctx.applicationContext,location,m.second.second,uri,false,sub)
                if(state.phase==Phase.CONNECTING)state.phase=Phase.DOWNLOADING
                refreshMessages()
                return null
            }catch(e:Exception){
                entries.remove(id,state)
                return "MMS download could not start: "+(e.localizedMessage?:"Network unavailable")
            }
        }catch(e:Exception) {
            entries.remove(id)
            Log.w(TAG,"MMS download start failed",e)
            return "MMS download failed: "+(e.localizedMessage?:"Unknown error")
        }
    }
}
