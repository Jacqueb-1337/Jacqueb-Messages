package org.fossify.messages.updates

import android.app.Activity
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
    private const val SIX_HOURS = 6 * 60 * 60 * 1000L
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
    fun setEnabled(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(PREF, 0).edit().putBoolean("auto_check", value).apply()
    fun channel(ctx: Context) = ctx.getSharedPreferences(PREF, 0).getString("channel", "stable") ?: "stable"
    fun setChannel(ctx: Context, value: String) {
        require(value in listOf("stable", "beta", "alpha"))
        ctx.getSharedPreferences(PREF, 0).edit().putString("channel", value).apply()
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
                        if(name.endsWith(".apk",ignoreCase=true) && name.startsWith("Jacqueb-Messages-",true)
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

    fun checkOnLaunch(activity: Activity, root: View) {
        if (!enabled(activity)) return
        val prefs=activity.getSharedPreferences(PREF,0)
        val now=System.currentTimeMillis()
        if(now-prefs.getLong("last_auto_check",0L)<SIX_HOURS) return
        prefs.edit().putLong("last_auto_check",now).apply()
        val channel=channel(activity)
        Thread({
            try {
                val updates=pending(fetch(),channel)
                if(updates.isEmpty()) return@Thread
                activity.runOnUiThread {
                    if(activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    Snackbar.make(root,"Jacqueb Messages ${updates.first().version} is available",Snackbar.LENGTH_LONG)
                        .setAction("Review") {
                            activity.startActivity(android.content.Intent(activity, UpdateActivity::class.java))
                        }.show()
                }
            } catch (_: Exception) { /* Offline: manual checking remains available. */ }
        },"jacqueb-update-check").start()
    }
}
