package org.fossify.messages.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.fossify.messages.R
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.theming.UiTheme
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Messenger-style split contact portraits for group conversations.
 * All provider lookups and bitmap decoding happen outside the UI thread.
 * Explicit per-conversation pictures always take precedence at call sites.
 */
object GroupAvatarArt {
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, Bitmap>(64)
    private val waiting = HashMap<Long, MutableList<WeakReference<ImageView>>>()
    private val lock = Any()

    fun cached(threadId: Long): Bitmap? = synchronized(lock) { cache.get(threadId) }

    /** For shortcut creation and other background-only call sites. */
    fun bitmapForThread(context: Context, threadId: Long): Bitmap? {
        if (threadId <= 0L) return null
        cached(threadId)?.let { return it }
        val application = context.applicationContext
        val result = runCatching {
            val people = application.getThreadParticipants(threadId, null)
            if (people.size < 2) null else {
                val sources = people.take(4).map { contact ->
                    val picture = try {
                        contact.photoUri.takeIf { it.isNotBlank() }?.let { uri ->
                            application.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                                BitmapFactory.decodeStream(stream, null,
                                    BitmapFactory.Options().apply { inSampleSize = 2 })
                            }
                        }
                    } catch (_: Exception) { null }
                    picture ?: UiTheme.avatarBitmap(application, contact.name, false)
                }
                compose(sources)
            }
        }.getOrNull()
        if (result != null) synchronized(lock) { cache.put(threadId, result) }
        // Do not permanently cache failed/empty lookups: MMS participant
        // records and contact permissions can become available later.
        return result
    }

    /** Deliver a composed group icon on the UI thread without blocking it. */
    fun load(context: Context, threadId: Long, callback: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        cached(threadId)?.let { main.post { callback(it) }; return }
        executor.execute {
            val result = bitmapForThread(app, threadId)
            main.post { callback(result) }
        }
    }

    fun bind(context: Context, threadId: Long, view: ImageView) {
        view.setTag(R.id.group_avatar_thread_id, threadId)
        synchronized(lock) {
            cache.get(threadId)?.let { view.setImageBitmap(it); return }
            waiting[threadId]?.let {
                it.add(WeakReference(view))
                return
            }
            waiting[threadId] = mutableListOf(WeakReference(view))
        }
        val application = context.applicationContext
        executor.execute {
            val result = bitmapForThread(application, threadId)
            val targets: List<WeakReference<ImageView>>
            synchronized(lock) {
                targets = waiting.remove(threadId).orEmpty()
            }
            if (result != null) main.post {
                targets.forEach { ref ->
                    ref.get()?.let { image ->
                        if (image.getTag(R.id.group_avatar_thread_id) == threadId)
                            image.setImageBitmap(result)
                    }
                }
            }
        }
    }

    private fun compose(images: List<Bitmap>): Bitmap {
        val result = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val clip = Path().apply { addCircle(64f, 64f, 64f, Path.Direction.CW) }
        canvas.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val panels = when (images.size) {
            2 -> listOf(RectF(0f, 0f, 63f, 128f), RectF(65f, 0f, 128f, 128f))
            3 -> listOf(RectF(0f, 0f, 63f, 63f), RectF(65f, 0f, 128f, 63f),
                RectF(0f, 65f, 128f, 128f))
            else -> listOf(RectF(0f, 0f, 63f, 63f), RectF(65f, 0f, 128f, 63f),
                RectF(0f, 65f, 63f, 128f), RectF(65f, 65f, 128f, 128f))
        }
        images.zip(panels).forEach { (bitmap, dest) ->
            val ratio = dest.width() / dest.height()
            val sourceWidth: Int
            val sourceHeight: Int
            if (bitmap.width.toFloat() / bitmap.height > ratio) {
                sourceHeight = bitmap.height
                sourceWidth = (sourceHeight * ratio).toInt().coerceAtLeast(1)
            } else {
                sourceWidth = bitmap.width
                sourceHeight = (sourceWidth / ratio).toInt().coerceAtLeast(1)
            }
            val left = (bitmap.width - sourceWidth) / 2
            val top = (bitmap.height - sourceHeight) / 2
            canvas.drawBitmap(bitmap, Rect(left, top, left + sourceWidth, top + sourceHeight),
                dest, paint)
        }
        return result
    }
}
