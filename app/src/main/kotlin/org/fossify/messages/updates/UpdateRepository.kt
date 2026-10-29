package org.fossify.messages.updates

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.text.format.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.view.View
import com.google.android.material.snackbar.Snackbar
import org.fossify.messages.BuildConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Public GitHub Releases act as the update feed. Stable accepts plain
 * semantic versions, Beta accepts beta+stable, Alpha accepts every channel.
 * Nothing is installed without a separate Android package installer prompt. */
object UpdateRepository {
    const val PREF = "jacqueb_update_preferences_v1"
    private const val API = "https://api.github.com/repos/Jacqueb-1337/Jacqueb-Messages/releases"
    const val SIX_HOURS = 6L * 60 * 60 * 1000L
    private const val RETRY_DELAY = 20L * 60 * 1000L
    private const val JOB_ID = 210061
    private const val NOTIFICATION_ID = 210062
    private const val NOTIFICATION_CHANNEL = "messages_software_updates_v1"
    private val checking = AtomicBoolean(false)
    private val VERSION = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta)[.-]?(\\d+)?)?$", RegexOption.IGNORE_CASE)

    data class Version(val major: Int, val minor: Int, val patch: Int,
        val channel: String = "stable", val sequence: Int = 0) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val m = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
            if (m != 0) return m
            val rank = mapOf("alpha" to 0, "beta" to 1, "stable" to 2)
            val c = (rank[channel] ?: 2).compareTo(rank[other.channel] ?: 2)
            return if (c != 0) c else sequence.compareTo(other.sequence)
        }
        override fun toString(): String = "$major.$minor.$patch" +
            (if (channel == "stable") "" else "-$channel.$sequence")
    }

    data class Release(val tag: String, val version: Version, val title: String,
        val notes: String, val download: String?, val size: Long, val published: String)

    fun parse(tag: String): Version? {
        val m = VERSION.matchEntire(tag) ?: return null
        return Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),m.groupValues[4].ifEmpty { "stable" }.lowercase(),
            m.groupValues[5].toIntOrNull() ?: 0)
    }

    fun installed(): Version = parse(BuildConfig.VERSION_NAME) ?: Version(0, 0, 0)
    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getBoolean("auto_check", true)
    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREF, 0).edit().putBoolean("auto_check", value).apply()
        schedule(ctx)
    }
    fun channel(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getString("channel", "stable") ?: "stable"
    fun setChannel(ctx: Context, value: String) {
        require(value in listOf("stable", "beta", "alpha"))
        ctx.getSharedPreferences(PREF, 0).edit().putString("channel", value)
            .remove("notified_tag").remove("last_auto_success").apply()
    }
    fun accepts(version: Version, channel: String) = when(channel) {
        "alpha" -> true
        "beta" -> version.channel != "alpha"
        else -> version.channel == "stable"
    }

    /** Called only off the main thread. Includes release notes for every
     * eligible version in the gap, not just the newest release. */
    fun fetch(): List<Release> {
        val all = ArrayList<Release>()
        for (page in 1..4) {
            val conn = (URL("$API?per_page=100&page=$page").openConnection() as HttpURLConnection)
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "JacquebMessages/${BuildConfig.VERSION_NAME}")
            try {
                val code=conn.responseCode
                if (code != 200) error("Update server returned HTTP $code")
                val data=conn.inputStream.bufferedReader().use { it.readText() }
                val arr=JSONArray(data)
                for(i in 0 until arr.length()) {
                    val item=arr.getJSONObject(i)
                    if(item.optBoolean("draft", false)) continue
                    val tag=item.optString("tag_name")
                    val version=parse(tag) ?: continue
                    val assets=item.optJSONArray("assets") ?: JSONArray()
                    var download: String? = null
                    var size=0L
                    for(j in 0 until assets.length()) {
                        val a=assets.getJSONObject(j)
                        val uri=a.optString("browser_download_url")
                        val name=a.optString("name")
                        if(name.endsWith("-release.apk",ignoreCase=true) && name.startsWith("Jacqueb-Messages-",true)
                            && uri.startsWith("https://github.com/Jacqueb-1337/Jacqueb-Messages/releases/download/")) {
                            download=uri;size=a.optLong("size",0L);break
                        }
                    }
                    all.add(Release(tag,version,item.optString("name",tag),
                        item.optString("body",""),download,size,item.optString("published_at","")))
                }
                if(arr.length()<100) break
            } finally { conn.disconnect() }
        }
        return all.distinctBy { it.tag }.sortedByDescending { it.version }
    }

    fun pending(releases: List<Release>, channel: String): List<Release> {
        val current=installed()
        val eligible=releases.filter { accepts(it.version,channel) && it.version > current }
        val newest=eligible.firstOrNull { it.download != null } ?: return emptyList()
        return eligible.filter { it.version <= newest.version }.sortedByDescending { it.version }
    }

    /** Android owns the six-hour cadence while the app is backgrounded;
     * Doze and battery optimization may postpone individual executions. */
    fun schedule(context: Context) {
        val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!enabled(context)) {
            scheduler.cancel(JOB_ID)
            return
        }
        if(scheduler.allPendingJobs.any { it.id == JOB_ID }) return
        val job=JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(SIX_HOURS)
            .setPersisted(true)
            .build()
        try { scheduler.schedule(job) }
        catch (e:Exception) {
            context.getSharedPreferences(PREF,0).edit()
                .putString("last_error","Could not schedule automatic checks: ${e.message?.take(80)}").apply()
        }
    }

    fun lastCheckText(context: Context): String {
        val pref=context.getSharedPreferences(PREF,0)
        val time=pref.getLong("last_success",0L)
        val error=pref.getString("last_error",null)
        if(time==0L && error.isNullOrBlank()) return "Not checked yet"
        val date=if(time>0L) DateFormat.format("MMM d, h:mm a",Date(time)).toString() else "never"
        val lastStatus=pref.getString("last_status","Unknown") ?: "Unknown"
        return "Last successful check: $date | $lastStatus" +
            if(!error.isNullOrBlank()) "\nLast attempt failed: $error" else ""
    }

    private fun notifyAvailable(context: Context, release: Release) {
        if (Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) {
            manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL,
                "Software updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="New Messages app versions available on your selected channel"
            })
        }
        val intent=android.content.Intent(context,UpdateActivity::class.java).apply {
            flags=android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending=PendingIntent.getActivity(context,0,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=NotificationCompat.Builder(context,NOTIFICATION_CHANNEL)
            .setSmallIcon(org.fossify.messages.R.drawable.ic_launcher_foreground)
            .setContentTitle("Messages ${release.version} available")
            .setContentText("Tap to review changes and download the update")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "A new version of Messages is available. Review the full release notes before installing."))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIFICATION_ID,note)
    }

    /** Runs on a background thread in both foreground and scheduled paths.
     * Unlike the old implementation, failed requests never start the six-hour
     * *success* cooldown, and the last check result remains visible in Settings. */
    fun performCheck(context: Context): Release? {
        if(!enabled(context) || !checking.compareAndSet(false,true)) return null
        val prefs=context.getSharedPreferences(PREF,0)
        try {
            prefs.edit().putLong("last_attempt",System.currentTimeMillis()).apply()
            val available=pending(fetch(),channel(context)).firstOrNull()
            val result=if(available==null) "Up to date" else "Update ${available.version} available"
            prefs.edit().putLong("last_success",System.currentTimeMillis())
                .putString("last_status",result).remove("last_error").apply()
            if(available!=null && available.tag!=prefs.getString("notified_tag",null)) {
                notifyAvailable(context,available)
                // Even when OS notifications are blocked, show the status in
                // Settings. A later check may notify once permission is given.
                if(Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(context,
                        Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                    prefs.edit().putString("notified_tag",available.tag).apply()
            }
            return available
        } catch(e:Exception) {
            prefs.edit().putString("last_error",e.message?.take(100) ?: "Network unavailable").apply()
            return null
        } finally { checking.set(false) }
    }

    fun checkOnLaunch(activity: Activity, root: View) {
        if(!enabled(activity))return
        val prefs=activity.getSharedPreferences(PREF,0)
        val now=System.currentTimeMillis()
        val afterSuccess=now-prefs.getLong("last_success",0L)
        val afterAttempt=now-prefs.getLong("last_attempt",0L)
        if(afterSuccess<SIX_HOURS || afterAttempt<RETRY_DELAY || checking.get()) return
        Thread({
            val update=performCheck(activity.applicationContext)
            activity.runOnUiThread {
                if(activity.isFinishing || activity.isDestroyed || !enabled(activity)) return@runOnUiThread
                val error=activity.getSharedPreferences(PREF,0).getString("last_error",null)
                val msg=when {
                    update!=null -> "Messages ${update.version} is available"
                    !error.isNullOrBlank() -> "Update check failed • See Settings"
                    else -> "Updates checked • You're up to date"
                }
                Snackbar.make(root,msg,Snackbar.LENGTH_LONG).apply {
                    if(update!=null) setAction("Review") {
                        activity.startActivity(android.content.Intent(activity,UpdateActivity::class.java))
                    }
                }.show()
            }
        },"messages-update-check").start()
    }
}
