package org.fossify.messages.safe

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Durable retries are independent of the successful SMS/MMS receipt path. */
class SafeTransferWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val complete=runCatching {SafeVault.retryPending(applicationContext)}.getOrDefault(false)
        runCatching {SafeTransferWork.changed(applicationContext)}
        return if(complete)Result.success() else Result.retry()
    }
}
object SafeTransferWork {
    private val activeReceipts=java.util.concurrent.atomic.AtomicInteger(0)
    fun beginReceipt(){activeReceipts.incrementAndGet()}
    fun endReceipt(){activeReceipts.decrementAndGet()}
    fun receiving()=activeReceipts.get()>0
    private fun readyKey(kind:String,id:Long,date:Long)="ready:"+kind+":"+id+":"+date
    fun ready(c:Context,kind:String,id:Long,date:Long) {
        runCatching {if(SafeVault.ids(c).isNotEmpty())prefs(c).edit().putLong(readyKey(kind,id,date),System.currentTimeMillis()).commit()}
    }
    fun canMove(c:Context,kind:String,row:org.json.JSONObject):Boolean {
        val date=row.optLong("date")
        if(prefs(c).getLong(readyKey(kind,row.optLong("_id"),date),0)>0)return true
        // Measure recovery grace from first observation, not the sender's MMS
        // timestamp, which can be old even while download is still finishing.
        val key="seen:"+kind+":"+row.optLong("_id")+":"+date
        val first=prefs(c).getLong(key,0)
        if(first==0L){prefs(c).edit().putLong(key,System.currentTimeMillis()).commit();return false}
        return System.currentTimeMillis()-first>5*60_000L
    }
    private val liveRevision=java.util.concurrent.atomic.AtomicLong(0)
    private fun prefs(c:Context)=c.getSharedPreferences("safe_transfer_retry",0)
    fun revision(c:Context)=maxOf(liveRevision.get(),prefs(c).getLong("revision",0))
    @Synchronized fun changed(c:Context){val next=maxOf(System.currentTimeMillis(),revision(c)+1);liveRevision.set(next);prefs(c).edit().putLong("revision",next).commit()}
    fun enqueue(c:Context) {
        runCatching {
            if(SafeVault.ids(c).isEmpty())return
            changed(c)
            // A fresh receipt can attempt promptly even if an earlier attempt is in backoff.
            // Vault mutation is synchronized and idempotent across these jobs.
            WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<SafeTransferWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("safe-transfer").build())
        }.onFailure{android.util.Log.w("SafeTransfer","Could not schedule now; periodic/startup recovery remains",it)}
    }
    fun start(c:Context) {
        runCatching {
            val stale=prefs(c).all.filter { (key,value)->(key.startsWith("ready:") || key.startsWith("seen:")) && value is Long && value<System.currentTimeMillis()-24*60*60_000L }.keys
            prefs(c).edit().also{edit->stale.forEach{edit.remove(it)}}.apply()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("safe-transfer-recovery",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SafeTransferWorker>(15,TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
            enqueue(c)
        }.onFailure{android.util.Log.w("SafeTransfer","Recovery scheduling unavailable",it)}
    }
}
