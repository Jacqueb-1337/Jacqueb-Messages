package org.fossify.messages.safe

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Private UI partition of the Android SMS provider, not encrypted SMS storage.
 * No message bodies, credentials or unlock secrets are exported in backups. */
object SafeStore {
    private const val PREF = "messages_private_safe_v1"
    const val LOCK_DEVICE = "device"
    const val LOCK_PIN = "pin"
    const val LOCK_PATTERN = "pattern"
    const val ALERT_OFF = "off"
    const val ALERT_REDACTED = "redacted"
    @Volatile private var activeUntil = 0L
    @Volatile private var failed = 0
    @Volatile private var retryAfter = 0L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun configured(ctx: Context) = prefs(ctx).contains("backup_hash") && prefs(ctx).contains("primary_kind")
    fun primary(ctx: Context) = prefs(ctx).getString("primary_kind",LOCK_DEVICE) ?: LOCK_DEVICE
    fun backup(ctx: Context) = prefs(ctx).getString("backup_kind",LOCK_PIN) ?: LOCK_PIN
    fun notificationMode(ctx: Context) = prefs(ctx).getString("alerts",ALERT_OFF) ?: ALERT_OFF
    fun setNotificationMode(ctx: Context,mode:String) {
        require(mode==ALERT_OFF || mode==ALERT_REDACTED)
        prefs(ctx).edit().putString("alerts",mode).apply()
    }
    fun isSafe(ctx: Context,threadId: Long):Boolean =
        threadId>0 && prefs(ctx).getStringSet("threads",emptySet())?.contains(threadId.toString())==true
    fun safeThreads(ctx: Context):Set<Long> =
        prefs(ctx).getStringSet("threads",emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    @Synchronized fun move(ctx: Context,id:Long,into:Boolean) {
        require(id>0 && configured(ctx) && unlocked())
        val current=prefs(ctx).getStringSet("threads",emptySet()).orEmpty().toMutableSet()
        if(into) current.add(id.toString()) else current.remove(id.toString())
        check(prefs(ctx).edit().putStringSet("threads",current).commit())
        if(into) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(id.hashCode())
            org.fossify.messages.chatheads.FloatingChatHeadsService.dispatchToRunning(
                android.content.Intent(ctx,org.fossify.messages.chatheads.FloatingChatHeadsService::class.java)
                    .apply {
                        action=org.fossify.messages.chatheads.FloatingChatHeadsService.ACTION_REMOVE_SAFE
                        putExtra(org.fossify.messages.chatheads.FloatingChatHeadsService.EXTRA_THREAD_ID,id)
                    }
            )
        }
    }
    fun unlocked()=SystemClock.elapsedRealtime()<activeUntil
    fun unlock() { activeUntil=SystemClock.elapsedRealtime()+2*60_000L;failed=0;retryAfter=0 }
    fun lock() { activeUntil=0L }
    fun limited()=SystemClock.elapsedRealtime()<retryAfter
    fun recordFailure() {
        failed++
        if(failed>=5) retryAfter=SystemClock.elapsedRealtime() + 30_000L * (1L shl (failed-5).coerceIn(0,4))
    }
    private fun hash(secret: String,salt:ByteArray):String {
        val chars=secret.toCharArray()
        val spec=PBEKeySpec(chars,salt,180_000,256)
        return try {
            Base64.encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded,Base64.NO_WRAP)
        } finally { spec.clearPassword();chars.fill('\u0000') }
    }
    private fun newSalt():String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it,Base64.NO_WRAP) }
    fun validFormat(kind:String,secret:String):Boolean = when(kind) {
        LOCK_PIN -> secret.matches(Regex("[0-9]{6,12}"))
        LOCK_PATTERN -> secret.length in 4..9 && secret.all { it in '1'..'9' } && secret.toSet().size==secret.length
        else -> false
    }
    fun configure(ctx:Context,primaryKind:String,primarySecret:String?,backupKind:String,backupSecret:String) {
        require(!configured(ctx))
        require(primaryKind in listOf(LOCK_DEVICE,LOCK_PIN,LOCK_PATTERN))
        require(backupKind in listOf(LOCK_PIN,LOCK_PATTERN))
        require(backupKind != primaryKind)
        require(validFormat(backupKind,backupSecret))
        if(primaryKind!=LOCK_DEVICE) require(primarySecret!=null && validFormat(primaryKind,primarySecret))
        val backupSalt=newSalt()
        val editor=prefs(ctx).edit().putString("primary_kind",primaryKind)
            .putString("backup_kind",backupKind)
            .putString("backup_salt",backupSalt)
            .putString("backup_hash",hash(backupSecret,Base64.decode(backupSalt,Base64.NO_WRAP)))
            .putString("alerts",ALERT_OFF)
        if(primaryKind!=LOCK_DEVICE) {
            val salt=newSalt()
            editor.putString("primary_salt",salt).putString("primary_hash",
                hash(primarySecret!!,Base64.decode(salt,Base64.NO_WRAP)))
        }
        check(editor.commit())
        unlock()
    }
    fun verify(ctx:Context,kind:String,value:String):Boolean {
        if(limited() || !configured(ctx)) return false
        val prefix=if(kind==backup(ctx)) "backup" else if(kind==primary(ctx)) "primary" else return false
        val salt=prefs(ctx).getString("${prefix}_salt",null) ?: return false
        val expected=prefs(ctx).getString("${prefix}_hash",null) ?: return false
        val actual=hash(value,Base64.decode(salt,Base64.NO_WRAP))
        val valid=MessageDigest.isEqual(expected.toByteArray(),actual.toByteArray())
        if(valid) unlock() else recordFailure()
        return valid
    }
}
