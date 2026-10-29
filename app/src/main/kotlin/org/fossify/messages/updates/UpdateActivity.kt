package org.fossify.messages.updates

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.fossify.messages.BuildConfig
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.theming.UiTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated ROM-like software update screen. Header and actions stay fixed;
 * all release notes between the installed version and target scroll centrally. */
class UpdateActivity : SimpleActivity() {
    private val stop = AtomicBoolean(false)
    private var worker: Thread? = null
    private var target: UpdateRepository.Release? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var history: LinearLayout
    private lateinit var progress: LinearProgressIndicator
    private lateinit var transfer: TextView
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private lateinit var channelLabel: TextView
    private val colors by lazy { intArrayOf(
        UiTheme.color(this,"surface"), UiTheme.color(this,"card"),
        UiTheme.color(this,"title"),UiTheme.color(this,"preview"),UiTheme.color(this,"accent")) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Int=18): GradientDrawable = GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat()
    }
    private fun text(value: String,size: Float=15f, bold: Boolean=false,muted:Boolean=false): TextView =
        TextView(this).apply {
            this.text=value
            textSize=size
            setTextColor(if(muted)colors[3] else colors[2])
            if(bold) setTypeface(null,Typeface.BOLD)
            includeFontPadding=true
        }
    private fun spacer(height: Int) = View(this).apply { layoutParams=LinearLayout.LayoutParams(1,dp(height)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(colors[0]) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(16),dp(22),dp(20))
        }
        val top=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        top.addView(text("\u2039",36f).apply {
            contentDescription="Back";setPadding(0,0,dp(19),0)
            setOnClickListener { finish() }
        })
        top.addView(text("Software update",24f,true),LinearLayout.LayoutParams(0,-2,1f))
        top.addView(text("\u21bb",26f).apply {
            contentDescription="Check for updates";setPadding(dp(12),0,0,0)
            setOnClickListener { load() }
        })
        header.addView(top)
        header.addView(spacer(20))
        header.addView(text("MESSAGES",12f,true,true))
        header.addView(spacer(7))
        summary=text("Installed version ${BuildConfig.VERSION_NAME}",18f,true)
        header.addView(summary)
        status=text("Checking GitHub releases...",13f,muted=true)
        header.addView(spacer(7));header.addView(status)
        header.addView(spacer(7))
        header.addView(text(UpdateRepository.lastCheckText(this),12f,muted=true))
        channelLabel=text("Update channel: ${UpdateRepository.channel(this).replaceFirstChar { it.uppercase() }}",13f,true)
        channelLabel.setPadding(0,dp(17),0,0)
        channelLabel.setOnClickListener { chooseChannel() }
        header.addView(channelLabel)
        root.addView(header)
        val line=View(this).apply { setBackgroundColor(colors[3]); alpha=.16f }
        root.addView(line,LinearLayout.LayoutParams(-1,dp(1)))
        val scroll=ScrollView(this).apply {
            clipToPadding=false;isFillViewport=true
            isVerticalScrollBarEnabled=true
        }
        history=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(22),dp(18),dp(24))
        }
        scroll.addView(history)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(colors[1]);setPadding(dp(18),dp(16),dp(18),dp(20))
        }
        progress=LinearProgressIndicator(this).apply { max=100;isIndeterminate=false;progress=0;visibility=View.GONE }
        footer.addView(progress,LinearLayout.LayoutParams(-1,dp(5)))
        transfer=text("",13f,muted=true).apply { visibility=View.GONE }
        footer.addView(transfer)
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        secondary=MaterialButton(this).apply {
            text="Pause";visibility=View.GONE
            setOnClickListener { stop.set(true);isEnabled=false }
        }
        buttons.addView(secondary,LinearLayout.LayoutParams(0,dp(54),.37f))
        primary=MaterialButton(this).apply {
            text="Checking...";isEnabled=false
            setOnClickListener { onPrimary() }
        }
        buttons.addView(primary,LinearLayout.LayoutParams(0,dp(54),1f))
        footer.addView(buttons)
        root.addView(footer)
        load()
    }

    private fun chooseChannel() {
        val options=arrayOf("Stable", "Beta (includes stable)", "Alpha (all builds)")
        val values=listOf("stable","beta","alpha")
        val selected=values.indexOf(UpdateRepository.channel(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Update channel")
            .setSingleChoiceItems(options,selected) { dialog,index ->
                UpdateRepository.setChannel(this,values[index])
                channelLabel.text="Update channel: ${values[index].replaceFirstChar { it.uppercase() }}"
                dialog.dismiss();load()
            }.setNegativeButton("Cancel",null).show()
    }

    private fun load() {
        if(worker?.isAlive==true) { status.text="Pause the download before checking again";return }
        status.text="Checking releases..."
        summary.text="Installed version ${BuildConfig.VERSION_NAME}"
        primary.isEnabled=false;primary.text="Checking..."
        history.removeAllViews()
        history.addView(text("Checking for updates",19f,true))
        Thread({
            try {
                val all=UpdateRepository.fetch()
                val checked=System.currentTimeMillis()
                val next=UpdateRepository.pending(all,UpdateRepository.channel(this))
                getSharedPreferences(UpdateRepository.PREF,0).edit().putLong("last_success",checked)
                    .putString("last_status", if(next.isEmpty()) "Up to date" else "Update ${next.first().version} available")
                    .remove("last_error").apply()
                val current=all.firstOrNull { it.version == UpdateRepository.installed() }
                runOnUiThread { if(!isFinishing && !isDestroyed) render(next,current) }
            } catch(e:Exception) {
                getSharedPreferences(UpdateRepository.PREF,0).edit()
                    .putString("last_error",e.message?.take(100) ?: "Network unavailable").apply()
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    status.text="Unable to check for updates"
                    history.removeAllViews()
                    history.addView(text("Could not load releases. Check your internet connection and retry.",16f))
                    history.addView(spacer(8));history.addView(text(e.message.orEmpty(),12f,muted=true))
                    primary.text="Retry";primary.isEnabled=true;target=null
                }
            }
        },"jacqueb-release-history").start()
    }

    private fun render(releases: List<UpdateRepository.Release>, installedRelease: UpdateRepository.Release? = null) {
        history.removeAllViews()
        target=releases.firstOrNull()
        if(releases.isEmpty()) {
            summary.text="You're up to date"
            status.text="Version ${BuildConfig.VERSION_NAME}  |  ${UpdateRepository.channel(this)} channel"
            history.addView(text("You're running the latest available version on your channel.",18f))
            if (installedRelease != null) {
                history.addView(spacer(24))
                history.addView(text("What's new in your installed version",20f,true))
                history.addView(spacer(13))
                val card=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    background=bg(colors[1]);setPadding(dp(18),dp(17),dp(18),dp(19))
                }
                card.addView(text(installedRelease.title.ifBlank { "Version ${installedRelease.version}" },18f,true))
                card.addView(spacer(12))
                card.addView(text(installedRelease.notes.trim().ifBlank { "Release notes weren't provided." },14f).apply {
                    setLineSpacing(dp(3).toFloat(),1f)
                })
                history.addView(card,LinearLayout.LayoutParams(-1,-2))
            }
            primary.text="Check again";primary.isEnabled=true;return
        }
        val latest=releases.first()
        summary.text="Version ${BuildConfig.VERSION_NAME}  ->  ${latest.version}"
        status.text="${releases.size} release${if(releases.size==1) "" else "s"} of changes  |  ${UpdateRepository.channel(this)} channel"
        history.addView(text("What's new",23f,true))
        history.addView(spacer(5))
        history.addView(text("Complete changes since your installed version",13f,muted=true))
        history.addView(spacer(16))
        releases.forEachIndexed { index,release ->
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                background=bg(colors[1]);setPadding(dp(18),dp(17),dp(18),dp(19))
            }
            card.addView(text("VERSION ${release.version}",12f,true,true))
            card.addView(spacer(7))
            card.addView(text(release.title.ifBlank { "Jacqueb Messages ${release.version}" },18f,true))
            card.addView(spacer(13))
            val content=release.notes.trim().ifBlank { "Release notes were not provided for this version." }
            card.addView(text(content,14f).apply { setLineSpacing(dp(3).toFloat(),1f) })
            history.addView(card,LinearLayout.LayoutParams(-1,-2))
            if(index != releases.lastIndex)history.addView(spacer(13))
        }
        primary.isEnabled=true
        val file=apkFile(latest)
        primary.text=if(file.exists() && file.length()==latest.size && latest.size>0) "Install update" else "Download update"
    }

    private fun apkFile(release: UpdateRepository.Release): File =
        File(File(cacheDir,"updates").apply { mkdirs() },"${release.tag.replace(Regex("[^A-Za-z0-9_.-]"),"_")}.apk")
    private fun onPrimary() {
        val release=target ?: run { load();return }
        if(worker?.isAlive==true)return
        val file=apkFile(release)
        if(file.exists() && file.length()>0 && release.size>0 && file.length()==release.size) {
            try { verifyAndInstall(file) } catch(e:Exception) {
                status.text="Update cannot be installed: "+(e.message ?: "APK is incompatible")
                transfer.visibility=View.VISIBLE
                transfer.text="The download completed. Your existing app and data are unchanged."
                primary.text="Check for compatible update"
                target=null
            }
        } else startDownload(release)
    }

    private fun startDownload(release: UpdateRepository.Release) {
        val uri=release.download ?: run { status.text="No APK attached to this release";return }
        stop.set(false)
        secondary.visibility=View.VISIBLE;secondary.isEnabled=true;secondary.text="Pause"
        progress.visibility=View.VISIBLE;transfer.visibility=View.VISIBLE
        primary.isEnabled=false;primary.text="Downloading..."
        worker=Thread({
            val file=apkFile(release)
            var connection: HttpURLConnection?=null
            var verifyingDownloadedApk=false
            try {
                require(uri.startsWith("https://github.com/Jacqueb-1337/Jacqueb-Messages/releases/download/"))
                val previous=file.length().takeIf { it > 0 && (release.size==0L || it<release.size) } ?: 0L
                connection=(URL(uri).openConnection() as HttpURLConnection).apply {
                    connectTimeout=20000;readTimeout=20000
                    setRequestProperty("User-Agent","JacquebMessages-Updater/${BuildConfig.VERSION_NAME}")
                    if(previous>0L)setRequestProperty("Range","bytes=$previous-")
                }
                val response=connection.responseCode
                require(response==200 || response==206) { "Download returned HTTP $response" }
                val append=response==206 && previous>0
                var completed=if(append)previous else 0L
                var total=if(release.size>0)release.size else connection.contentLengthLong.takeIf { it>0 }?.plus(completed) ?: 0L
                if(!append)file.delete()
                var lastUi=0L
                connection.inputStream.buffered().use { input ->
                    file.outputStreamForAppend(append).buffered().use { output ->
                        val buffer=ByteArray(64*1024)
                        while(true) {
                            if(stop.get())break
                            val n=input.read(buffer)
                            if(n<0)break
                            output.write(buffer,0,n);completed+=n
                            val now=System.currentTimeMillis()
                            if(now-lastUi>115L) {
                                lastUi=now
                                val current=completed
                                val expected=total
                                runOnUiThread {
                                    if(isFinishing || isDestroyed)return@runOnUiThread
                                    if(expected>0) { progress.isIndeterminate=false;progress.setProgressCompat((current*100/expected).toInt().coerceIn(0,100),true) }
                                    else progress.isIndeterminate=true
                                    transfer.text=if(expected>0) "${current/1048576} / ${expected/1048576} MB  |  ${(current*100/expected).toInt()}%"
                                        else "${current/1048576} MB downloaded"
                                }
                            }
                        }
                        output.flush()
                    }
                }
                val wasPaused=stop.get()
                if(!wasPaused) {
                    require(file.length()>0L && (release.size==0L || file.length()==release.size)) { "Incomplete download" }
                    verifyingDownloadedApk=true
                    verifyPackage(file)
                }
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    secondary.visibility=View.GONE
                    primary.isEnabled=true
                    if(wasPaused) {
                        primary.text="Resume download"
                        status.text="Paused. Your progress is saved."
                    } else {
                        primary.text="Install update"
                        status.text="Download verified and ready to install"
                        progress.isIndeterminate=false;progress.setProgressCompat(100,true)
                    }
                }
            } catch(e:Exception) {
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    secondary.visibility=View.GONE
                    if(verifyingDownloadedApk) {
                        primary.isEnabled=true
                        primary.text="Check for compatible update"
                        target=null
                        status.text="Download finished but APK cannot be installed: "+(e.message ?: "Incompatible APK")
                        transfer.visibility=View.VISIBLE
                        transfer.text="The existing installation and messages were not changed."
                        progress.isIndeterminate=false
                        progress.setProgressCompat(100,true)
                    } else {
                        primary.isEnabled=true
                        primary.text="Retry download"
                        status.text="Download interrupted: "+(e.message ?: "Network failure")
                    }
                }
            } finally { connection?.disconnect() }
        },"jacqueb-update-download").also { it.start() }
    }

    private fun File.outputStreamForAppend(append: Boolean): java.io.FileOutputStream = java.io.FileOutputStream(this,append)

    /** Reject any mismatched package or signature before Android receives it. */
    private fun verifyPackage(apk: File) {
        val flags=PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val candidate=packageManager.getPackageArchiveInfo(apk.absolutePath,flags)
            ?: error("Downloaded file is not a valid APK")
        require(candidate.packageName==packageName) { "This update targets another Android package" }
        val code=if(Build.VERSION.SDK_INT>=28)candidate.longVersionCode else @Suppress("DEPRECATION") candidate.versionCode.toLong()
        require(code>BuildConfig.VERSION_CODE.toLong()) { "APK version is not newer than installed version" }
        @Suppress("DEPRECATION")
        val current=packageManager.getPackageInfo(packageName, flags)
        val expected=current.signingInfo?.apkContentsSigners ?: error("Missing installed signing certificate")
        val actual=candidate.signingInfo?.apkContentsSigners ?: error("Missing APK signing certificate")
        require(expected.size==actual.size && expected.zip(actual).all { (a,b) -> a.toByteArray().contentEquals(b.toByteArray()) }) {
            "Update signature does not match the installed application"
        }
    }

    private fun verifyAndInstall(file:File) {
        verifyPackage(file)
        if(Build.VERSION.SDK_INT>=26 && !packageManager.canRequestPackageInstalls()) {
            status.text="Allow Jacqueb Messages to install updates in Android settings, then tap Install again."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
            return
        }
        val uri=FileProvider.getUriForFile(this,"$packageName.provider",file)
        val intent=Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri,"application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        // Partial download survives and can resume from cache on reopening.
        stop.set(true)
        super.onDestroy()
    }
}
