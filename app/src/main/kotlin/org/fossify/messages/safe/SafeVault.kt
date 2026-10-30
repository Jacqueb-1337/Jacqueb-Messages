package org.fossify.messages.safe

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import org.fossify.messages.extensions.*
import org.fossify.messages.models.Conversation
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Crash-recoverable cross-store transfer. Encrypted manifest and every binary
 * part are durably saved and verified before a public provider row is removed.
 * A restore is a multiset reconciliation, including identical SMS duplicates;
 * retry repairs an existing incomplete MMS rather than creating another one. */
object SafeVault {
    private const val ALIAS="messages_safe_aes_v2"
    private const val LIMIT=32*1024*1024
    private val restoredThreads=java.util.concurrent.ConcurrentHashMap<Long,Long>()
    private val sms=Telephony.Sms.CONTENT_URI
    private val mms=Telephony.Mms.CONTENT_URI
    private val ignored=setOf("_id","thread_id","_data","creator","mid","msg_id")
    @Volatile internal var testFault: String? = null
    private fun checkpoint(name:String) {
        if(org.fossify.messages.BuildConfig.DEBUG && testFault==name) throw java.io.IOException("Injected transfer interruption: "+name)
    }
    private fun folder(c:Context,id:Long)=File(File(c.noBackupFilesDir,"safe-vault-v2"),id.toString())
    fun exists(c:Context,id:Long)=File(folder(c,id),"manifest.aes").exists()
    fun ids(c:Context):Set<Long> = File(c.noBackupFilesDir,"safe-vault-v2").listFiles().orEmpty()
        .filter{File(it,"manifest.aes").exists()}.mapNotNull{it.name.toLongOrNull()}.toSet()
    fun modified(c:Context,id:Long)=File(folder(c,id),"manifest.aes").lastModified()
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        check(create){"Safe encryption key is unavailable. Encrypted files have been retained."}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun readFile(c:Context,id:Long,name:String):ByteArray {
        val file=File(folder(c,id),name)
        check(file.length() in 30..(LIMIT+64).toLong()){"Safe file missing or too large"}
        val data=AtomicFile(file).openRead().use{it.readBytes()}
        check(data[0]==2.toByte()){"Unsupported Safe format"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,data.copyOfRange(1,13)))
        cipher.updateAAD((id.toString()+"/"+name).toByteArray())
        return cipher.doFinal(data.copyOfRange(13,data.size))
    }
    private fun writeFile(c:Context,id:Long,name:String,plain:ByteArray) {
        check(plain.size<=LIMIT){"Safe file exceeds 32 MB; original messages have been retained"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore must generate the random GCM IV itself.
        cipher.init(Cipher.ENCRYPT_MODE,key(true))
        cipher.updateAAD((id.toString()+"/"+name).toByteArray())
        val atomic=AtomicFile(File(folder(c,id).apply{mkdirs()},name))
        val out=atomic.startWrite()
        try {out.write(byteArrayOf(2));out.write(cipher.iv);out.write(cipher.doFinal(plain));atomic.finishWrite(out)}
        catch(e:Exception){atomic.failWrite(out);throw e}
        check(readFile(c,id,name).contentEquals(plain)){"Encrypted Safe verification failed"}
    }
    private fun load(c:Context,id:Long)=JSONObject(readFile(c,id,"manifest.aes").toString(Charsets.UTF_8))
    private fun save(c:Context,id:Long,a:JSONObject)=writeFile(c,id,"manifest.aes",a.toString().toByteArray(Charsets.UTF_8))
    @Synchronized fun read(c:Context,id:Long):JSONObject {check(SafeStore.unlocked()){"Unlock Safe first"};return load(c,id)}
    fun isDefault(c:Context):Boolean = if(android.os.Build.VERSION.SDK_INT>=29)
        c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS)==true
        else Telephony.Sms.getDefaultSmsPackage(c)==c.packageName
    private fun defaultApp(c:Context){check(isDefault(c)){"Set Messages as your default SMS app first"}}
    private fun row(cursor:Cursor):JSONObject = JSONObject().apply {
        cursor.columnNames.forEachIndexed { i,name ->
            put(name,when(cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> JSONObject().put("blob64",android.util.Base64.encodeToString(cursor.getBlob(i),2))
                else -> cursor.getString(i)
            })
        }
    }
    private fun rows(c:Context,uri:Uri,selection:String?,args:Array<String>?):List<JSONObject> =
        c.contentResolver.query(uri,null,selection,args,"_id ASC")?.use{cur->buildList{while(cur.moveToNext())add(row(cur))}}
            ?: error("Cannot read Android message database")
    private fun values(row:JSONObject,extra:Map<String,Long> = emptyMap())=ContentValues().apply {
        row.keys().forEach { key -> if(key !in ignored) {
            val v=row.get(key)
            when(v) {
                JSONObject.NULL -> putNull(key)
                is Long -> put(key,v)
                is Int -> put(key,v)
                is Double -> put(key,v)
                is JSONObject -> put(key,android.util.Base64.decode(v.getString("blob64"),2))
                else -> put(key,v.toString())
            }
        }}
        extra.forEach{(key,v)->put(key,v)}
    }
    private fun same(a:JSONObject,b:JSONObject,skip:Set<String> = ignored):Boolean =
        a.keys().asSequence().filter{it !in skip}.all { k ->
            val av=a.opt(k);val bv=b.opt(k)
            if(av==JSONObject.NULL) bv==JSONObject.NULL else av.toString()==bv?.toString()
        }
    private fun array(a:JSONArray)= (0 until a.length()).map{a.getJSONObject(it)}
    private fun publicRows(c:Context,thread:Long,kind:String)=rows(c,if(kind=="sms")sms else mms,"thread_id=?",arrayOf(thread.toString()))
    private fun bytes(c:Context,uri:Uri):ByteArray = c.contentResolver.openInputStream(uri)?.use{input ->
        val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var n:Int
        while(input.read(chunk).also{n=it}!=-1){check(out.size()+n<=LIMIT){"MMS attachment exceeds Safe's 32 MB limit"};out.write(chunk,0,n)}
        out.toByteArray()
    } ?: error("Cannot read MMS attachment")
    private fun captureRecord(c:Context,id:Long,kind:String,data:JSONObject):JSONObject {
        check(kind!="sms" || data.optInt("type") in listOf(1,2,5)){"Finish sending queued messages before moving this conversation"}
        check(kind!="mms" || data.optInt("msg_box") in listOf(1,2,5)){"Wait for queued MMS to finish before moving it"}
        check(kind!="mms" || data.optInt("m_type")==132 || data.optInt("m_type")==128){"Download pending MMS before moving this conversation"}
        val record=JSONObject().put("key",UUID.randomUUID().toString()).put("kind",kind).put("row",data)
        if(kind=="mms") {
            val mid=data.getLong("_id")
            record.put("addresses",JSONArray(rows(c,Uri.parse("content://mms/"+mid+"/addr"),null,null)))
            val parts=rows(c,Uri.parse("content://mms/"+mid+"/part"),null,null)
            for(part in parts) {
                val ct=part.optString("ct")
                if(ct!="text/plain" && ct!="application/smil") {
                    val name=UUID.randomUUID().toString()+".aes"
                    writeFile(c,id,name,bytes(c,Uri.parse("content://mms/part/"+part.getLong("_id"))))
                    part.put("safe_blob",name)
                }
            }
            record.put("parts",JSONArray(parts))
        }
        return record
    }
    private fun create(c:Context,id:Long):JSONObject {
        val conv=c.conversationsDB.getConversationWithThreadId(id)
        val recipients=c.getThreadParticipants(id,null).flatMap{it.phoneNumbers.map{it.value}}.distinct()
        check(recipients.isNotEmpty()){"Cannot resolve the conversation's recipients"}
        return JSONObject().put("schema",2).put("thread",id).put("title",conv?.title ?: recipients.joinToString(", "))
            .put("custom_title",conv?.usesCustomTitle ?: false).put("archived",conv?.isArchived ?: false)
            .put("draft",org.fossify.messages.messaging.ConversationDrafts.read(c,id).orEmpty())
            .put("recipients",JSONArray(recipients)).put("phase","capturing").put("records",JSONArray())
    }
    private fun scrub(c:Context,thread:Long,clearDraft:Boolean=true) {
        c.messagesDB.deleteSafeCachedThreadMessages(thread);c.conversationsDB.deleteThreadId(thread)
        org.fossify.messages.search.SearchCatalog.removeThread(c,thread)
        org.fossify.messages.search.SemanticIndex.removeThread(c,thread)
        org.fossify.messages.chatheads.OverlayConversationPanel.forgetCached(thread)
        com.bumptech.glide.Glide.get(c).clearDiskCache()
        android.os.Handler(android.os.Looper.getMainLooper()).post { com.bumptech.glide.Glide.get(c).clearMemory() }
        if(clearDraft) {
            c.getSharedPreferences("conversation_drafts_shared_v1",0).edit().putString("draft_"+thread,"").commit()
            c.deleteSmsDraft(thread)
        }
    }
    @Synchronized fun capture(c:Context,id:Long) {
        check(SafeStore.unlocked()){"Unlock Safe first"};defaultApp(c)
        captureInternal(c,id,if(exists(c,id))load(c,id) else create(c,id),id)
    }
    private fun captureInternal(c:Context,id:Long,a:JSONObject,providerThread:Long,bestEffort:Boolean=false) {
        check(a.optString("phase")!="restoring"){"Finish restoring this conversation first"}
        val records=a.getJSONArray("records")
        val pending= mutableListOf<Pair<String,JSONObject>>()
        for(kind in listOf("sms","mms")) for(data in publicRows(c,providerThread,kind)) {
            if(bestEffort && !SafeTransferWork.canMove(c,kind,data))continue
            val oldIndex=(0 until records.length()).firstOrNull { index ->
                val old=records.getJSONObject(index)
                val oldRow=old.getJSONObject("row")
                old.getString("kind")==kind && oldRow.optLong("_id")==data.getLong("_id") &&
                    oldRow.optLong("thread_id")==data.optLong("thread_id") && oldRow.optLong("date")==data.optLong("date")
            }
            val rec=try {captureRecord(c,id,kind,data)} catch(error:Exception) {
                if(!bestEffort)throw error
                android.util.Log.w("SafeTransfer","A message remains in the provider for retry",error)
                continue
            }
            if(oldIndex==null)records.put(rec) else {
                rec.put("key",records.getJSONObject(oldIndex).getString("key"));records.put(oldIndex,rec)
            }
            pending.add(kind to rec)
        }
        check(records.length()>0){"No messages to move"}
        a.put("phase","capturing");save(c,id,a)
        checkpoint("after_encrypted_write")
        check(!SafeTransferWork.receiving()){"Message receipt is still finishing; transfer will retry"}
        // Disk read and authenticated decryption have completed. Only now delete
        // the exact captured rows, after checking they have not changed.
        for((kind,rec) in pending) {
            val data=rec.getJSONObject("row");val rowId=data.getLong("_id");val uri=if(kind=="sms")sms else mms
            val current=rows(c,uri,"_id=? AND thread_id=?",arrayOf(rowId.toString(),providerThread.toString())).singleOrNull() ?: continue
            check(same(data,current)){"Message changed during encryption. Recovery copy retained; retry the move."}
            if(kind=="mms") {
                for((name,path) in listOf("parts" to "part","addresses" to "addr")) {
                    val expected=array(rec.getJSONArray(name))
                    val actual=rows(c,Uri.parse("content://mms/"+rowId+"/"+path),null,null)
                    check(expected.size==actual.size && expected.zip(actual).all { (x,y)->same(x,y,ignored+"safe_blob") }) {"MMS changed while encrypting; recovery copy retained"}
                    if(name=="parts") expected.zip(actual).forEach { (x,y)->
                        if(x.has("safe_blob"))check(readFile(c,id,x.getString("safe_blob")).contentEquals(bytes(c,Uri.parse("content://mms/part/"+y.getLong("_id"))))) {"MMS attachment changed while encrypting"}
                    }
                }
            }
            check(c.contentResolver.delete(uri,"_id=? AND thread_id=?",arrayOf(rowId.toString(),providerThread.toString()))==1){"Could not remove public message; encrypted recovery retained"}
            checkpoint("after_provider_delete")
        }
        val providerEmpty=publicRows(c,providerThread,"sms").isEmpty() && publicRows(c,providerThread,"mms").isEmpty()
        scrub(c,providerThread,clearDraft=providerEmpty)
        check(providerEmpty){"New messages arrived during transfer. Retry to finish encryption."}
        a.put("phase","sealed");save(c,id,a)
        val referenced=array(records).flatMap { rec ->
            rec.optJSONArray("parts")?.let { array(it).mapNotNull { part -> part.optString("safe_blob").takeIf { name -> name.isNotBlank() } } }.orEmpty()
        }.toSet()+"manifest.aes"
        folder(c,id).listFiles().orEmpty().filter{it.name.endsWith(".aes") && it.name !in referenced}.forEach{it.delete()}
    }
    private fun providerThread(c:Context,a:JSONObject):Long {
        val recipients=a.getJSONArray("recipients")
        return Telephony.Threads.getOrCreateThreadId(c,(0 until recipients.length()).map{recipients.getString(it)}.toSet())
    }
    // Android may allocate a new thread ID after the last public row is removed.
    // Map IDs only, keeping recipients encrypted. This never intercepts receipt.
    fun associatePublicThread(c:Context,thread:Long) {
        runCatching {
            if(ids(c).isEmpty())return
            val addresses=c.getThreadParticipants(thread,null).flatMap{it.phoneNumbers.map{it.value}}.distinct()
            if(addresses.isEmpty())return
            ids(c).firstOrNull { id ->
                val a=load(c,id)
                a.optString("phase") !in listOf("restoring","restored") && recipientMatch(a,addresses)
            }?.let { id -> c.getSharedPreferences("safe_provider_aliases",0).edit().putLong(thread.toString(),id).commit() }
        }.onFailure{android.util.Log.w("SafeTransfer","Could not associate provider thread; receipt continues",it)}
    }
    fun isPublicAlias(c:Context,thread:Long):Boolean {
        val id=c.getSharedPreferences("safe_provider_aliases",0).getLong(thread.toString(),0)
        return id>0 && exists(c,id)
    }
    /** Background recovery never depends on a UI unlock and never deletes before verification. */
    @Synchronized fun retryPending(c:Context):Boolean {
        val privateIds=ids(c);if(privateIds.isEmpty())return true
        if(!isDefault(c) || SafeTransferWork.receiving())return false
        var complete=true
        privateIds.forEach { id ->
            try {
                val a=load(c,id)
                if(a.optString("phase") !in listOf("restoring","restored")) {
                    val thread=providerThread(c,a)
                    c.getSharedPreferences("safe_provider_aliases",0).edit().putLong(thread.toString(),id).commit()
                    if(a.optString("phase")=="capturing" || publicRows(c,thread,"sms").isNotEmpty() || publicRows(c,thread,"mms").isNotEmpty())
                        captureInternal(c,id,a,thread,true)
                }
            } catch(error:Exception) {complete=false;android.util.Log.w("SafeTransfer","Transfer incomplete; originals or recovery retained",error)}
        }
        return complete
    }
    /** The icon describes actual storage state, including copies still awaiting removal. */
    @Synchronized fun readVisible(c:Context,id:Long):JSONObject {
        val a=read(c,id);val records=a.getJSONArray("records")
        val pending=mutableListOf<JSONObject>()
        var verified=true
        try {
            val thread=providerThread(c,a)
            for(kind in listOf("sms","mms"))for(data in publicRows(c,thread,kind)) {
                val rec=JSONObject().put("kind",kind).put("row",data).put("key","pending-"+kind+"-"+data.getLong("_id")).put("_encrypted",false)
                if(kind=="mms") {
                    val parts=rows(c,Uri.parse("content://mms/"+data.getLong("_id")+"/part"),null,null)
                    parts.forEach { part -> if(part.optString("ct").startsWith("image/")) part.put("provider_uri","content://mms/part/"+part.getLong("_id")) }
                    rec.put("parts",JSONArray(parts))
                }
                pending.add(rec)
            }
        } catch(error:Exception) {verified=false;android.util.Log.w("SafeTransfer","Cannot verify provider removal",error)}
        for(rec in array(records)) {
            val row=rec.getJSONObject("row")
            val match=pending.firstOrNull{it.getString("kind")==rec.getString("kind") && row.optLong("_id",-1)>0 &&
                it.getJSONObject("row").optLong("_id")==row.optLong("_id") && it.getJSONObject("row").optLong("date")==row.optLong("date")}
            rec.put("_encrypted",verified && match==null && a.optString("phase") !in listOf("restoring","restored"))
            if(match!=null)pending.remove(match)
        }
        pending.forEach{records.put(it)}
        return a
    }
    /** Acknowledge only the snapshot actually rendered, never a later arrival. */
    @Synchronized fun markViewed(c:Context,id:Long,visible:JSONObject) {
        if(!SafeStore.unlocked() || !exists(c,id))return
        val seen=array(visible.getJSONArray("records"))
        val a=load(c,id);var changed=false
        for(rec in array(a.getJSONArray("records"))) {
            val row=rec.getJSONObject("row")
            val shown=seen.any { old ->
                val source=old.getJSONObject("row")
                old.optString("key")==rec.optString("key") || (old.getString("kind")==rec.getString("kind") &&
                    row.optLong("_id",-1)>0 && source.optLong("_id")==row.optLong("_id") && source.optLong("date")==row.optLong("date"))
            }
            if(shown && (row.optInt("read")!=1 || row.optInt("seen")!=1)){row.put("read",1).put("seen",1);changed=true}
        }
        if(changed)save(c,id,a)
        // Pending originals are updated by exact identity only; they remain intact.
        if(isDefault(c))for(rec in seen) {
            val row=rec.getJSONObject("row");val sourceId=row.optLong("_id",-1)
            if(sourceId>0)runCatching {
                c.contentResolver.update(if(rec.getString("kind")=="sms")sms else mms,
                    ContentValues().apply{put("read",1);put("seen",1)},"_id=? AND thread_id=? AND date=?",
                    arrayOf(sourceId.toString(),row.optLong("thread_id").toString(),row.optLong("date").toString()))
            }
        }
    }
    @Synchronized fun restore(c:Context,id:Long):Long {
        check(SafeStore.unlocked()){"Unlock Safe first"};defaultApp(c)
        val a=load(c,id);val recipients=a.getJSONArray("recipients")
        check(array(a.getJSONArray("records")).none { it.getString("kind")=="sms" && (it.getJSONObject("row").optInt("type") in listOf(4,6) ||
            it.optInt("pending_parts") > (it.optJSONArray("completed_parts")?.length() ?: 0)) }) {"Wait for the private message's send result before restoring"}
        val thread=Telephony.Threads.getOrCreateThreadId(c,(0 until recipients.length()).map{recipients.getString(it)}.toSet())
        restoredThreads[id]=thread
        a.put("phase","restoring").put("restored_thread",thread);save(c,id,a)
        val used= mutableSetOf<String>()
        for(rec in array(a.getJSONArray("records"))) {
            val kind=rec.getString("kind");val data=rec.getJSONObject("row");val uri=if(kind=="sms")sms else mms
            val stable=if(kind=="sms")ignored else ignored+setOf("m_size","text_only","read","seen")
            val found=publicRows(c,thread,kind).firstOrNull{kind+it.getLong("_id") !in used && same(data,it,stable)}
            val restored=found?.getLong("_id") ?: c.contentResolver.insert(uri,values(data,mapOf("thread_id" to thread)))?.lastPathSegment?.toLong()
                ?: error("Android refused the restored message. Encrypted copy retained.")
            checkpoint("after_restore_insert")
            used.add(kind+restored)
            if(kind=="mms") {
                reconcileChildren(c,id,rec,restored,"addresses","addr","msg_id")
                reconcileChildren(c,id,rec,restored,"parts","part","mid")
            }
            val restoredRow=rows(c,uri,"_id=?",arrayOf(restored.toString())).single()
            check(same(data,restoredRow)){"Restored message fields differ; encrypted copy retained"}
        }
        a.put("phase","restored");save(c,id,a)
        c.updateLastConversationMessage(listOf(thread))
        c.getConversations(thread).firstOrNull()?.let { conv ->
            c.conversationsDB.insertOrUpdate(conv.copy(title=if(a.optBoolean("custom_title"))a.getString("title") else conv.title,
                usesCustomTitle=a.optBoolean("custom_title"),isArchived=a.optBoolean("archived")))
        }
        val draft=a.optString("draft")
        c.getSharedPreferences("conversation_drafts_shared_v1",0).edit().putString("draft_"+thread,draft).commit()
        if(draft.isNotBlank())c.saveSmsDraft(draft,thread)
        org.fossify.messages.helpers.refreshConversations();org.fossify.messages.helpers.refreshMessages()
        return thread
    }
    private fun reconcileChildren(c:Context,id:Long,rec:JSONObject,mid:Long,name:String,path:String,parent:String) {
        val uri=Uri.parse("content://mms/"+mid+"/"+path);val used=mutableSetOf<Long>()
        for(child in array(rec.getJSONArray(name))) {
            val skip=ignored+"safe_blob"
            val existing=rows(c,uri,null,null).firstOrNull{it.getLong("_id") !in used && same(child,it,skip)}
            val childId=existing?.getLong("_id") ?: c.contentResolver.insert(uri,values(JSONObject(child.toString()).apply{remove("safe_blob")},mapOf(parent to mid)))?.lastPathSegment?.toLong()
                ?: error("Cannot restore MMS child; encrypted copy retained")
            used.add(childId)
            if(child.has("safe_blob")) {
                val blob=readFile(c,id,child.getString("safe_blob"))
                val target=Uri.parse("content://mms/part/"+childId)
                c.contentResolver.openOutputStream(target,"w")?.use{it.write(blob)} ?: error("Cannot write MMS attachment")
                check(bytes(c,target).contentEquals(blob)){"MMS attachment verification failed"}
            }
            val actual=rows(c,uri,"_id=?",arrayOf(childId.toString())).single()
            check(same(child,actual,skip)){"MMS metadata verification failed"}
        }
    }
    @Synchronized fun finalizeRestore(c:Context,id:Long) {
        check(load(c,id).getString("phase")=="restored")
        check(folder(c,id).deleteRecursively()){"Messages restored; encrypted cleanup pending. Retry to finish."}
    }
    fun conversation(c:Context,id:Long):Conversation {
        val a=read(c,id);val records=array(a.getJSONArray("records"))
        val last=records.maxByOrNull{date(it)}
        return Conversation(id,last?.let{body(it)}.orEmpty(),((last?.let{date(it)} ?: 0L)/1000).toInt(),true,
            a.getString("title"),"",a.getJSONArray("recipients").length()>1,"",unreadCount=0)
    }
    fun date(rec:JSONObject):Long = rec.getJSONObject("row").optLong("date") * if(rec.getString("kind")=="mms")1000L else 1L
    fun body(rec:JSONObject):String = if(rec.getString("kind")=="sms")rec.getJSONObject("row").optString("body").let{if(it=="null")"" else it}
        else array(rec.getJSONArray("parts")).filter{it.optString("ct")=="text/plain"}.joinToString("\n"){it.optString("text")} .ifBlank{"Attachment"}
    fun attachment(c:Context,id:Long,part:JSONObject):ByteArray {check(SafeStore.unlocked());return if(part.has("provider_uri"))bytes(c,Uri.parse(part.getString("provider_uri"))) else readFile(c,id,part.getString("safe_blob"))}
    private fun normalize(a:String)=android.telephony.PhoneNumberUtils.normalizeNumber(a).let{if(it.startsWith("+1")&&it.length==12)it.drop(2) else it}.ifBlank{a.trim().lowercase()}
    private fun recipientMatch(a:JSONObject,addresses:List<String>):Boolean {
        val rs=a.getJSONArray("recipients");val expected=(0 until rs.length()).map{normalize(rs.getString(it))}.toSet()
        return expected==addresses.map{normalize(it)}.toSet()
    }
    @Synchronized fun appendIncoming(c:Context,address:String,body:String,date:Long,subscription:Int,status:Int):Boolean {
        val match=ids(c).map{it to load(c,it)}.filter{recipientMatch(it.second,listOf(address))}
        if(match.isEmpty())return false
        check(match.size==1){"Ambiguous Safe recipient"}
        val (id,a)=match.single()
        // Once a restore has started, new delivery belongs to the public inbox.
        // Never append after the restore snapshot and then delete that arrival.
        if(a.optString("phase") in listOf("restoring","restored"))return false
        val data=JSONObject().put("address",address).put("body",body).put("date",date).put("date_sent",0).put("read",0)
            .put("seen",0).put("type",1).put("status",status).put("sub_id",subscription)
        a.getJSONArray("records").put(JSONObject().put("key",UUID.randomUUID().toString()).put("kind","sms").put("row",data))
        save(c,id,a);notifyPrivate(c);return true
    }
    @Synchronized fun absorbMms(c:Context,thread:Long):Boolean {
        val addresses=c.getThreadParticipants(thread,null).flatMap{it.phoneNumbers.map{it.value}}.distinct()
        val match=ids(c).map{it to load(c,it)}.filter{recipientMatch(it.second,addresses)}
        if(match.isEmpty())return false
        check(match.size==1);val(id,a)=match.single()
        if(a.optString("phase") in listOf("restoring","restored"))return false
        captureInternal(c,id,a,thread);notifyPrivate(c);return true
    }
    private fun notifyPrivate(c:Context) {
        if(SafeStore.notificationMode(c)!=SafeStore.ALERT_REDACTED)return
        val nm=c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel="safe_private"
        nm.createNotificationChannel(android.app.NotificationChannel(channel,"Private messages",3))
        val open=android.app.PendingIntent.getActivity(c,0,android.content.Intent(c,SafeActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {nm.notify(19000,android.app.Notification.Builder(c,channel).setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("New private message").setContentText("Unlock Safe to view").setContentIntent(open).setVisibility(-1).setAutoCancel(true).build())}
    }
    @Synchronized fun saveDraft(c:Context,id:Long,text:String) {
        fun publicDraft(thread:Long) {
            c.getSharedPreferences("conversation_drafts_shared_v1",0).edit().putString("draft_"+thread,text).commit()
            if(text.isBlank())c.deleteSmsDraft(thread) else c.saveSmsDraft(text,thread)
        }
        if(!exists(c,id)){restoredThreads[id]?.let{publicDraft(it)};return}
        val a=load(c,id);a.put("draft",text);save(c,id,a)
        if(a.optString("phase")=="restored")publicDraft(a.getLong("restored_thread"))
    }
    @Synchronized fun prepareSms(c:Context,id:Long,text:String,parts:Int=1):Pair<String,String> {
        val a=read(c,id);val recipients=a.getJSONArray("recipients");check(recipients.length()==1){"Restore group conversations to the inbox to reply"}
        val address=recipients.getString(0);val key=UUID.randomUUID().toString()
        a.getJSONArray("records").put(JSONObject().put("key",key).put("kind","sms").put("pending_parts",parts).put("send_result_version",2).put("completed_parts",JSONArray()).put("row",JSONObject()
            .put("address",address).put("body",text).put("date",System.currentTimeMillis()).put("date_sent",0).put("type",4)
            .put("read",1).put("seen",1).put("status",-1).put("sub_id",-1)))
        a.put("draft","");save(c,id,a);return key to address
    }
    fun sendLabel(record:JSONObject):String {
        if(record.getString("kind")!="sms" || record.getJSONObject("row").optInt("type")!=5)return ""
        // Older callback code read resultCode after goAsync detached the result.
        // Those saved failures are untrustworthy, not evidence of failed delivery.
        return if(record.has("completed_parts") && record.optInt("send_result_version")<2)
            " - Status unavailable" else " - Failed"
    }
    @Synchronized fun sentResult(c:Context,id:Long,key:String,success:Boolean,part:Int=0) {
        if(!exists(c,id))return
        val a=load(c,id);val record=array(a.getJSONArray("records")).firstOrNull{it.optString("key")==key} ?:return
        val completed=record.optJSONArray("completed_parts") ?: JSONArray()
        if((0 until completed.length()).any{completed.getInt(it)==part})return
        completed.put(part);record.put("completed_parts",completed)
        if(!success)record.put("send_failed",true)
        val finished=completed.length()>=record.optInt("pending_parts",1)
        record.getJSONObject("row").put("type",if(record.optBoolean("send_failed"))5 else if(finished)2 else 4)
            .put("status",if(record.optBoolean("send_failed"))64 else -1)
        save(c,id,a)
    }
}
