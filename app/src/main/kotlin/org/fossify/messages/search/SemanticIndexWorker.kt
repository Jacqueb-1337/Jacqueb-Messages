package org.fossify.messages.search

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Deferrable local indexing; batched index updates skip unchanged messages. */
class SemanticIndexWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        if(!SemanticSearchPrefs.optedIn(applicationContext) ||
            !SemanticSearchPrefs.engineInstalled(applicationContext))return Result.success()
        return try {
            SemanticIndex.index(applicationContext,{isStopped}) { _,_-> }
            if(isStopped)Result.retry() else Result.success()
        }catch(e:Exception) {
            android.util.Log.w("SemanticIndexWorker","Local indexing failed",e)
            if(runAttemptCount<3)Result.retry() else Result.failure()
        }
    }
    companion object {
        fun schedule(ctx:Context) {
            if(!SemanticSearchPrefs.optedIn(ctx)||!SemanticSearchPrefs.engineInstalled(ctx))return
            val constraints=Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request=OneTimeWorkRequestBuilder<SemanticIndexWorker>()
                .setConstraints(constraints).build()
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
                "messages-semantic-reindex",ExistingWorkPolicy.KEEP,request)
        }
    }
}
