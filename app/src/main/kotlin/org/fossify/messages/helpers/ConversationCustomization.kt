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

    private const val MAX_GIF_BYTES = 8 * 1024 * 1024L
    private const val MAX_GIF_DIMENSION = 2048

    /** Existing pictures remain PNG. Animated pictures are stored as real GIFs
     * instead of flattening the first frame to a PNG. */
    fun photoFile(context: Context, id: Long): File? {
        val dir = File(context.filesDir, "conversation_icons")
        return File(dir, "$id.gif").takeIf { it.isFile }
            ?: File(dir, "$id.png").takeIf { it.isFile }
    }
    fun isAnimatedPhoto(file: File): Boolean = file.extension.equals("gif", ignoreCase = true)
    fun hasPhoto(context: Context, id: Long) = photoFile(context, id) != null
    // Android notifications can only display a bitmap, so they use the first
    // frame of an animated profile image. In-app avatars remain animated.
    fun photoBitmap(context: Context, id: Long): Bitmap? =
        photoFile(context,id)?.let { BitmapFactory.decodeFile(it.path) }

    fun validGifBytes(data: ByteArray): Boolean =
        data.size in 10..MAX_GIF_BYTES.toInt() && validGifHeader(data.copyOfRange(0,10))

    private fun validGifHeader(header: ByteArray): Boolean {
        if(header.size < 10) return false
        val magic = String(header, 0, 6, Charsets.US_ASCII)
        if(magic != "GIF87a" && magic != "GIF89a") return false
        val width = (header[6].toInt() and 255) or ((header[7].toInt() and 255) shl 8)
        val height = (header[8].toInt() and 255) or ((header[9].toInt() and 255) shl 8)
        return width in 1..MAX_GIF_DIMENSION && height in 1..MAX_GIF_DIMENSION
    }

    /** Keep the selected GIF's original frames/loop timing in private storage.
     * Static pictures retain the existing centered-square PNG normalization. */
    fun savePhoto(context: Context, id: Long, source: Uri): Boolean {
        val dir = File(context.filesDir, "conversation_icons").apply { mkdirs() }
        val tmp = File(dir, "$id.import")
        return try {
            val input = context.contentResolver.openInputStream(source) ?: return false
            var count = 0L
            input.use { stream ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while(true) {
                        val read = stream.read(buffer)
                        if(read < 0) break
                        count += read
                        require(count <= MAX_GIF_BYTES) { "Picture exceeds 8 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if(count < 10L) return false
            val header = ByteArray(10)
            tmp.inputStream().use { stream ->
                if(stream.read(header) != header.size) return false
            }
            val isGif = validGifHeader(header)
            // Reject malformed or oversized GIF canvases rather than trying
            // to allocate unbounded frame buffers on older phones.
            val gifMagic = String(header, 0, 3, Charsets.US_ASCII) == "GIF"
            if(gifMagic && !isGif) return false
            val chosen = if(isGif) tmp else {
                val original = BitmapFactory.decodeFile(tmp.path) ?: return false
                try {
                    val size = 512
                    val side = minOf(original.width, original.height)
                    if(side <= 0) return false
                    val square = Bitmap.createBitmap(original, (original.width-side)/2,
                        (original.height-side)/2, side, side)
                    try {
                        val scaled = Bitmap.createScaledBitmap(square,size,size,true)
                        try {
                            FileOutputStream(File(dir,"$id.processed")).use {
                                if(!scaled.compress(Bitmap.CompressFormat.PNG,95,it)) return false
                            }
                        } finally { if(scaled !== square) scaled.recycle() }
                    } finally { if(square !== original) square.recycle() }
                } finally { original.recycle() }
                File(dir,"$id.processed")
            }
            val destination=File(dir,if(isGif) "$id.gif" else "$id.png")
            if(!chosen.renameTo(destination)) {
                chosen.copyTo(destination,overwrite=true)
                chosen.delete()
            }
            File(dir,if(isGif) "$id.png" else "$id.gif").delete()
            prefs(context).edit().putLong(key(id,"photo_updated"),System.currentTimeMillis()).apply()
            true
        } catch (_: Exception) { false }
        finally {
            tmp.delete()
            File(dir,"$id.processed").delete()
        }
    }

    fun removePhoto(context: Context,id:Long) {
        val dir=File(context.filesDir,"conversation_icons")
        File(dir,"$id.png").delete()
        File(dir,"$id.gif").delete()
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
        prefs(context).edit().putBoolean(key(id,"enabled"),value).commit()
        if (!value && id > 0L) {
            val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(id.hashCode())
            val command=android.content.Intent(context,
                org.fossify.messages.chatheads.FloatingChatHeadsService::class.java).apply {
                action=org.fossify.messages.chatheads.FloatingChatHeadsService.ACTION_REMOVE_MUTED
                putExtra(org.fossify.messages.chatheads.FloatingChatHeadsService.EXTRA_THREAD_ID,id)
            }
            // Only act on a running foreground service. Do not start another
            // persistent overlay just to remove a disabled conversation.
            org.fossify.messages.chatheads.FloatingChatHeadsService.dispatchToRunning(command)
        }
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
