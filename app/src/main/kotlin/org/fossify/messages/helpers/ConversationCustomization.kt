package org.fossify.messages.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/** Thread-scoped personalization, independent of Contacts and the synced SMS provider. */
object ConversationCustomization {
    private const val PREFS = "conversation_personalization_v1"
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(id: Long, part: String) = "t_${id}_${part}"

    fun photoFile(context: Context, id: Long): File? =
        File(context.filesDir, "conversation_icons/$id.png").takeIf { it.isFile }
    fun hasPhoto(context: Context, id: Long) = photoFile(context,id) != null
    fun photoBitmap(context: Context, id: Long): Bitmap? =
        photoFile(context,id)?.let { BitmapFactory.decodeFile(it.path) }

    /** Copy from the document provider into private app storage; do not store
     * a temporary picker URI that could lose access after the process exits. */
    fun savePhoto(context: Context, id: Long, source: Uri): Boolean {
        return try {
        val bitmap = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return false
        val size = 512
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) return false
        val square = Bitmap.createBitmap(bitmap, (bitmap.width-side)/2,
            (bitmap.height-side)/2, side, side)
        val scaled = Bitmap.createScaledBitmap(square,size,size,true)
        val dir=File(context.filesDir,"conversation_icons").apply { mkdirs() }
        val tmp=File(dir,"$id.tmp")
        FileOutputStream(tmp).use {
            if(!scaled.compress(Bitmap.CompressFormat.PNG,95,it)) return false
        }
        val target=File(dir,"$id.png")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite=true)
            tmp.delete()
        }
        if (scaled !== square) scaled.recycle()
        if (square !== bitmap) square.recycle()
        bitmap.recycle()
        prefs(context).edit().putLong(key(id,"photo_updated"),System.currentTimeMillis()).apply()
        true
        } catch (_: Exception) { false }
    }

    fun removePhoto(context: Context,id:Long) {
        photoFile(context,id)?.delete()
        prefs(context).edit().putLong(key(id,"photo_updated"),System.currentTimeMillis()).apply()
    }

    /** "default": inherit default sound; "": explicitly silent. */
    fun sound(context: Context,id:Long): String =
        prefs(context).getString(key(id,"sound"), "default") ?: "default"
    fun hasSoundPreference(context: Context,id:Long): Boolean =
        prefs(context).contains(key(id,"sound"))
    fun soundUri(context: Context,id:Long): Uri? = when(val sound=sound(context,id)){
        "default" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        "" -> null
        else -> try { Uri.parse(sound) } catch (_:Exception) { null }
    }

    fun vibration(context: Context,id:Long): String =
        prefs(context).getString(key(id,"vibration"),"default") ?: "default"
    fun hasVibrationPreference(context: Context,id:Long) =
        prefs(context).contains(key(id,"vibration"))
    fun vibrationPattern(context: Context,id:Long):LongArray? = when(vibration(context,id)) {
        "none" -> longArrayOf(0L)
        "short" -> longArrayOf(0,100)
        "double" -> longArrayOf(0,95,95,95)
        "heartbeat" -> longArrayOf(0,65,90,150,180,65)
        "long" -> longArrayOf(0,340)
        else -> null // system/default vibration
    }

    fun notificationsEnabled(context:Context,id:Long):Boolean =
        prefs(context).getBoolean(key(id,"enabled"),true)

    fun setNotificationsEnabled(context:Context,id:Long,value:Boolean) {
        prefs(context).edit().putBoolean(key(id,"enabled"),value).apply()
    }

    fun hasCustomAlert(context: Context,id:Long):Boolean =
        hasSoundPreference(context,id) || hasVibrationPreference(context,id)

    private fun version(context:Context,id:Long) =
        prefs(context).getInt(key(id,"channel_version"),1)
    fun channelId(context:Context,id:Long) = "thread_custom_${id}_v${version(context,id)}"

    fun setSound(context:Context,id:Long,value:String,title:String) {
        prefs(context).edit().putString(key(id,"sound"),value).apply()
        refreshChannel(context,id,title)
    }
    fun setVibration(context:Context,id:Long,value:String,title:String) {
        prefs(context).edit().putString(key(id,"vibration"),value).apply()
        refreshChannel(context,id,title)
    }

    /** Android freezes channel sound and vibration settings after first
     * registration; changing either needs a new channel identity. */
    private fun refreshChannel(context:Context,id:Long,title:String) {
        val old=channelId(context,id)
        val next=version(context,id)+1
        prefs(context).edit().putInt(key(id,"channel_version"),next).apply()
        ensureChannel(context,id,title)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .deleteNotificationChannel(old)
    }

    fun ensureChannel(context:Context,id:Long,title:String):String {
        val channelId=channelId(context,id)
        val nm=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(nm.getNotificationChannel(channelId)!=null) return channelId
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val pattern=vibrationPattern(context,id)
        nm.createNotificationChannel(NotificationChannel(channelId,title.ifBlank { "Conversation $id" },
            NotificationManager.IMPORTANCE_HIGH).apply {
            description="Personalized message alerts"
            setBypassDnd(false)
            setSound(soundUri(context,id),attrs)
            if(vibration(context,id)=="none") enableVibration(false)
            else if(pattern!=null){ enableVibration(true);vibrationPattern=pattern }
            else enableVibration(true)
        })
        return channelId
    }
}
