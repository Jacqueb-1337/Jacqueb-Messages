package org.fossify.messages.search

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Imports every provider SMS/MMS independently of message-view lifecycle. */
class SearchCatalogWorker(ctx:Context,params:WorkerParameters):Worker(ctx,params) {
    override fun doWork():Result {
        return try {
            SearchCatalog.sync(applicationContext){isStopped}
            if(isStopped)Result.retry() else {
                // Semantic vectors must be built against the complete catalog.
                SemanticIndexWorker.schedule(applicationContext)
                applicationContext.sendBroadcast(
                    android.content.Intent(ACTION_UPDATED)
                        .setPackage(applicationContext.packageName))
                Result.success()
            }
        }catch(e:Exception){
            Log.w("SearchCatalogWorker","Provider index unavailable",e)
            if(runAttemptCount<5)Result.retry() else Result.failure()
        }
    }
    companion object {
        const val ACTION_UPDATED="org.fossify.messages.SEARCH_CATALOG_UPDATED"
        fun schedule(ctx:Context,force:Boolean=false) {
            if(!force && SearchCatalog.ready(ctx) &&
                System.currentTimeMillis()-SearchCatalog.lastScan(ctx)<180_000L)return
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                "messages-full-provider-search-index",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SearchCatalogWorker>().build())
        }
    }
}
