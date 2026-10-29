package org.fossify.messages.updates

import android.app.job.JobParameters
import android.app.job.JobService

/** Scheduled by Android around every six hours, including after a reboot.
 * Never launches an installer. Only records the check and alerts if newer. */
class UpdateCheckJob : JobService() {
    @Volatile private var cancelled=false
    override fun onStartJob(params: JobParameters): Boolean {
        cancelled=false
        Thread({
            if (!cancelled && UpdateRepository.enabled(this)) UpdateRepository.performCheck(this)
            jobFinished(params,false)
        },"messages-periodic-updates").start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        cancelled=true
        return true
    }
}
