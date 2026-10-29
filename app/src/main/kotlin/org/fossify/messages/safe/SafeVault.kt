package org.fossify.messages.safe

import android.content.Context
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.fossify.messages.helpers.MessagesReader
import org.fossify.messages.helpers.MessagesWriter
import org.fossify.messages.models.SmsBackup
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encrypted SMS vault. Provider deletion happens ONLY after
 * complete row export, durable encrypted write, AES-GCM round-trip and row
 * count verification. MMS (including attachments) is deliberately rejected
 * until a separately verified, lossless MMS restore exists. Fail closed. */
object SafeVault {
    private const val KEY_ALIAS = "messages_safe_aes_v1"
    private const val MAX_BYTES = 64 * 1024 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    data class Archive(val originalThread: Long, val messages: List<SmsBackup>)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }
    private fun file(context:Context,thread:Long):File {
        require(thread>0)
        return File(File(context.noBackupFilesDir,"safe-vault").apply { mkdirs() },"$thread.aes")
    }
    fun exists(context:Context,thread:Long) = file(context,thread).isFile
    fun archiveIds(context:Context):Set<Long> =
        File(context.noBackupFilesDir,"safe-vault").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".aes") }
            .mapNotNull { it.name.removeSuffix(".aes").toLongOrNull() }.toSet()
    private fun encrypt(plaintext:ByteArray):ByteArray {
        val iv=ByteArray(12).also {SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(),GCMParameterSpec(128,iv))
        return byteArrayOf(1)+iv+cipher.doFinal(plaintext)
    }
    private fun decrypt(contents:ByteArray):ByteArray {
        require(contents.size>=30 && contents[0]==1.toByte()) {"Invalid Safe archive"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,contents.copyOfRange(1,13)))
        return cipher.doFinal(contents.copyOfRange(13,contents.size))
    }
    private fun serialize(thread:Long,sms:List<SmsBackup>):ByteArray {
        val envelope = """{"schema":1,"thread":$thread,"sms":${json.encodeToString(sms)}}"""
        return envelope.toByteArray(Charsets.UTF_8)
    }
    private fun parse(bytes:ByteArray):Archive {
        val root=org.json.JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schema")==1)
        val messages=json.decodeFromString<List<SmsBackup>>(root.getJSONArray("sms").toString())
        return Archive(root.getLong("thread"),messages)
    }
    fun read(context:Context,thread:Long):Archive {
        check(SafeStore.unlocked()) {"Unlock Safe first"}
        return readInternal(context,thread)
    }
    private fun readInternal(context:Context,thread:Long):Archive {
        val f=file(context,thread)
        check(f.isFile && f.length() in 30..MAX_BYTES.toLong()) {"Encrypted archive missing or oversized"}
        return parse(decrypt(f.readBytes())).also {check(it.originalThread==thread)}
    }
    /** Called before SmsReceiver writes an incoming SMS into Telephony.
     * An atomic archive update is verified before the provider path is skipped.
     * On I/O failure, the caller must preserve the SMS by using normal delivery.
     */
    @Synchronized fun appendIncoming(ctx:Context,address:String,body:String,date:Long,
        subscriptionId:Int,status:Int):Boolean {
        val normalized = normalizeAddress(address)
        if(normalized.isBlank()) return false
        val matching = archiveIds(ctx).mapNotNull { id ->
            val archive = readInternal(ctx,id)
            val existing = archive.messages.firstOrNull()?.address ?: return@mapNotNull null
            if(normalizeAddress(existing)==normalized) id to archive else null
        }
        if(matching.isEmpty()) return false
        check(matching.size==1) {"Ambiguous Safe recipient; preserve SMS in provider"}
        val (id,archive)=matching.single()
        val newMessage=SmsBackup(
            subscriptionId=subscriptionId.toLong(),address=address,body=body,date=date,
            dateSent=0L,locked=0,protocol=null,read=0,status=status,
            type=Telephony.Sms.MESSAGE_TYPE_INBOX,serviceCenter=null
        )
        val list=archive.messages+newMessage
        val plaintext=serialize(id,list)
        require(plaintext.size in 1..MAX_BYTES)
        val encrypted=encrypt(plaintext)
        val atomic=AtomicFile(file(ctx,id))
        val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)}
        catch(e:Exception){atomic.failWrite(stream);throw e}
        check(readInternal(ctx,id).messages==list) {"New private SMS archive verification failed"}
        if(SafeStore.notificationMode(ctx)==SafeStore.ALERT_REDACTED) {
            val manager=ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channel="messages_safe_redacted"
            if(android.os.Build.VERSION.SDK_INT>=26)
                manager.createNotificationChannel(android.app.NotificationChannel(
                    channel,"Safe message alerts",android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val alert=android.app.Notification.Builder(ctx,channel)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("New private message")
                .setContentText("Unlock Safe to view")
                .setAutoCancel(true)
                .setVisibility(android.app.Notification.VISIBILITY_SECRET)
                .build()
            try {manager.notify(19000,alert)} catch(_:SecurityException) {}
        }
        return true
    }

    private fun normalizeAddress(value:String):String {
        val normalized=android.telephony.PhoneNumberUtils.normalizeNumber(value)
        return if(normalized.startsWith("+1") && normalized.length==12) normalized.drop(2)
            else normalized
    }

    private fun count(ctx:Context,uri:android.net.Uri,id:Long):Int {
        return ctx.contentResolver.query(uri,arrayOf("_id"),"thread_id=?",arrayOf(id.toString()),null)
            ?.use { it.count } ?: error("Cannot read shared message provider")
    }
    private fun smsIds(ctx:Context,id:Long):List<Long> {
        val ids=ArrayList<Long>()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID}=?",arrayOf(id.toString()),null)?.use { cursor ->
            val col=cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            while(cursor.moveToNext())ids.add(cursor.getLong(col))
        } ?: error("Cannot read SMS rows")
        return ids
    }

    /** Must execute off the main thread with this app as default SMS handler. */
    @Synchronized fun capture(ctx:Context,thread:Long) {
        check(SafeStore.unlocked()) {"Unlock Safe first"}
        check(Telephony.Sms.getDefaultSmsPackage(ctx)==ctx.packageName) {"Set Messages as default SMS app first"}
        check(!exists(ctx,thread)) {"Already archived"}
        // Never discard MMS while its attachments and delivery metadata are
        // not covered by a tested lossless round-trip.
        check(count(ctx,Telephony.Mms.CONTENT_URI,thread)==0) {
            "This conversation includes MMS. Safe cannot remove it safely yet; nothing was deleted."
        }
        val ids=smsIds(ctx,thread)
        check(ids.isNotEmpty()) {"No SMS messages to move"}
        val backups=MessagesReader(ctx).getSmsForSafe(thread)
        check(backups.size==ids.size && smsIds(ctx,thread)==ids) {"SMS changed while preparing archive"}
        val data=serialize(thread,backups)
        require(data.size in 1..MAX_BYTES) {"Encrypted Safe archive exceeds 64 MB"}
        val dest=file(ctx,thread)
        val encrypted=encrypt(data)
        val atomic=AtomicFile(dest)
        val stream=atomic.startWrite()
        try {stream.write(encrypted);atomic.finishWrite(stream)}
        catch(e:Exception){ atomic.failWrite(stream);throw e }
        // Read from disk, decrypt, and compare every serialized SMS field
        // BEFORE destructive provider operations begin.
        check(read(ctx,thread).messages==backups) {"Safe archive verification failed"}
        check(smsIds(ctx,thread)==ids) {"SMS changed during archival; provider left intact"}
        for (id in ids) {
            val removed=ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms._ID}=? AND ${Telephony.Sms.THREAD_ID}=?",
                arrayOf(id.toString(),thread.toString()))
            check(removed==1) {"Could not remove all provider rows. Encrypted archive retained for recovery."}
        }
        check(count(ctx,Telephony.Sms.CONTENT_URI,thread)==0) {"Some SMS rows remain; encrypted archive retained"}
    }

    /** Restore keeps the archive intact until all SMS reappear in Android. */
    @Synchronized fun restore(ctx:Context,thread:Long) {
        check(Telephony.Sms.getDefaultSmsPackage(ctx)==ctx.packageName) {"Set Messages as default SMS app first"}
        val archive=read(ctx,thread)
        val writer=MessagesWriter(ctx)
        archive.messages.forEach { writer.writeSmsMessage(it) }
        writer.fixConversationDates()
        // The built-in writer deduplicates using date/address/type. Verify all
        // expected rows exist before considering restored SMS complete.
        archive.messages.forEach { message ->
            val count=ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.DATE}=? AND ${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.TYPE}=?",
                arrayOf(message.date.toString(),message.address,message.type.toString()),null)
                ?.use {it.count} ?: 0
            check(count>0){"Restore incomplete. Encrypted archive retained."}
        }
        // Keep archive until SafeStore moves the conversation out of its UI
        // partition successfully, then delete it explicitly.
    }
    fun finalizeRestore(ctx:Context,thread:Long) {check(SafeStore.unlocked());file(ctx,thread).delete()}
}
