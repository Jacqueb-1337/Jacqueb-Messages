package org.fossify.messages.messaging

import android.app.Activity
import android.net.Uri
import android.telephony.CarrierConfigManager
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import org.fossify.messages.extensions.*
import org.fossify.messages.helpers.ImageCompressor
import java.io.File
import java.util.UUID

/** Carrier limits are enforced even when the user's optional limit is disabled. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object MmsAttachmentPreparer {
    fun size(context: android.content.Context, uri: Uri, ceiling: Long): Long {
        runCatching { context.getFileSizeFromUri(uri) }.getOrNull()?.takeIf { it>0 }?.let{return it}
        runCatching { context.contentResolver.openFileDescriptor(uri,"r")?.use { it.statSize } }
            .getOrNull()?.takeIf{it>0}?.let{return it}
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer=ByteArray(8192);var count=0L
            while(count<=ceiling){val n=stream.read(buffer);if(n<0)break;count+=n}
            count
        } ?: error("Cannot read attachment")
    }
    fun limit(activity: Activity, sub: Int): Long {
        val carrier = runCatching {
            activity.getSystemService(CarrierConfigManager::class.java)
                .getConfigForSubId(sub)?.getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT)
                ?.toLong()?.takeIf { it > 0 }
        }.getOrNull() ?: 300L * 1024
        // Keep space for addresses, text, SMIL and multipart PDU headers.
        val payload = (carrier - maxOf(16L * 1024, carrier / 20)).coerceAtLeast(1024)
        val user = activity.config.mmsFileSizeLimit
        return if (user > 0) minOf(user, payload) else payload
    }
    fun prepare(activity: Activity, uri: Uri, sub: Int, ready: (Uri)->Unit) {
        val target = limit(activity, sub)
        val size = runCatching { size(activity,uri,target) }.getOrElse {
            Toast.makeText(activity, "Cannot read this attachment. Please choose it again.", 1).show(); return
        }
        if(size<=0) { Toast.makeText(activity,"Cannot determine this attachment's size. Please choose it again.",1).show();return }
        if (size in 1..target) { ready(uri); return }
        val mime = activity.contentResolver.getType(uri).orEmpty()
        val supported = mime.startsWith("image/") && mime != "image/gif" ||
            mime.startsWith("video/") || mime.startsWith("audio/")
        val description = "This attachment is ${Formatter.formatShortFileSize(activity, size)}. " +
            "The send limit is ${Formatter.formatShortFileSize(activity, target)}."
        val dialog = AlertDialog.Builder(activity).setTitle("Attachment too large")
            .setMessage(description + if(supported) " Compress a copy to fit? Quality may be reduced." else " Choose a smaller file; this file type cannot be compressed for MMS.")
            .setNegativeButton("Cancel", null)
        if(supported) dialog.setPositiveButton("Compress") { _,_ ->
            compress(activity, uri, mime, target, ready)
        }
        dialog.show()
    }
    private fun compress(activity: Activity, uri: Uri, mime: String, target: Long, ready: (Uri)->Unit) {
        var transformer: Transformer? = null
        var output: File? = null
        var cancelled = false
        var finished = false
        val progress = AlertDialog.Builder(activity).setTitle("Compressing attachment")
            .setMessage("Preparing a smaller copy. Your original will be kept.")
            .setNegativeButton("Cancel") { _,_ -> cancelled=true; transformer?.cancel(); output?.delete() }
            .setCancelable(false).show()
        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(object: androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                if(!finished){cancelled=true;transformer?.cancel();output?.delete();progress.dismiss()}
                owner.lifecycle.removeObserver(this)
            }
        })
        fun complete(result: Uri?) {
            if(cancelled) return
            finished=true
            progress.dismiss()
            if(activity.isFinishing || activity.isDestroyed) return
            val valid = result != null && runCatching { activity.getFileSizeFromUri(result) in 1..target }.getOrDefault(false)
            if(valid) ready(result!!) else {
                output?.delete()
                Toast.makeText(activity, "Couldn't compress this attachment to the send limit. Choose a shorter or smaller file.", 1).show()
            }
        }
        if(mime.startsWith("image/")) {
            ImageCompressor(activity).compressImage(uri, target, true) { result ->
                activity.runOnUiThread { complete(result) }
            }
            return
        }
        val duration = runCatching {
            android.media.MediaMetadataRetriever().let { retriever ->
                try { retriever.setDataSource(activity,uri); retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
                finally { retriever.release() }
            }
        }.getOrDefault(0L)
        if(duration<=0) { complete(null); return }
        var attempt = 0
        fun encode() {
            if(cancelled) return
            output?.delete()
            val file=File(File(activity.cacheDir,"compressed").apply{mkdirs()},UUID.randomUUID().toString()+(if(mime.startsWith("audio/"))".m4a" else ".mp4"))
            output=file
            val total=(target*8_000.0/duration*.80*Math.pow(.65,attempt.toDouble())).toInt()
            val audio=if(mime.startsWith("audio/")) total else minOf(48000,total/4)
            if(audio<12000 || mime.startsWith("video/") && total-audio<24000) { complete(null);return }
            val factory=DefaultEncoderFactory.Builder(activity)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(audio).build())
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(maxOf(24000,total-audio)).setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR).build()).build()
            val edited=EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), if(mime.startsWith("video/")) listOf(Presentation.createForHeight(when(attempt){0->480;1->240;else->144})) else emptyList())).build()
            transformer=Transformer.Builder(activity).setEncoderFactory(factory)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object: Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if(cancelled){file.delete();return}
                        if(file.length()>target && ++attempt<3) encode()
                        else complete(androidx.core.content.FileProvider.getUriForFile(activity,activity.packageName+".provider",file))
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) { complete(null) }
                }).build()
            runCatching { transformer!!.start(edited,file.absolutePath) }.onFailure { complete(null) }
        }
        encode()
    }
}
