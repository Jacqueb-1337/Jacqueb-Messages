package org.fossify.messages.search

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.models.Message
import org.fossify.messages.safe.SafeStore
import java.util.Locale

/** Private provider-backed catalog. Both SMS and MMS IDs are distinct keys. */
object SearchCatalog {
    private class Store(ctx:Context):SQLiteOpenHelper(ctx.applicationContext,
        java.io.File(ctx.noBackupFilesDir,"complete_search_v1.db").absolutePath,null,1) {
        override fun onConfigure(db:SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }
        override fun onCreate(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE docs(k INTEGER PRIMARY KEY, id INTEGER NOT NULL, mms INTEGER NOT NULL, thread INTEGER NOT NULL, date INTEGER NOT NULL, body TEXT NOT NULL, sender TEXT NOT NULL, kind INTEGER NOT NULL, generation INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX idx_doc_thread ON docs(thread,date)")
            db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
            db.execSQL("DROP TABLE IF EXISTS docs")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }
    private const val columns="k,id,mms,thread,date,body,sender,kind"
    data class Plan(val text:String,val threads:Set<Long>) {
        val contactFilter:Boolean get()=threads.isNotEmpty()
    }
    fun key(id:Long,mms:Boolean)=(id shl 1) or if(mms)1L else 0L
    private val token=Regex("""[\p{L}\p{N}#]+""")

    /** Recognize contact names as structured filters, not as message text. */
    fun plan(ctx:Context,query:String):Plan {
        val words=token.findAll(query.lowercase(Locale.ROOT)).map{it.value}.toList()
        if(words.isEmpty())return Plan("",emptySet())
        val all=runCatching{ctx.conversationsDB.getAllForSearch()}
            .getOrDefault(emptyList())
            .filterNot{SafeStore.isSafe(ctx,it.threadId)}
        val matchingWords=words.filter{w->
            w.length>=2 && all.any{c->
                token.findAll(c.title.lowercase(Locale.ROOT)).any{it.value==w} ||
                (w.length>=4 && c.phoneNumber.contains(w))
            }
        }.distinct()
        if(matchingWords.isEmpty())return Plan(query.trim(),emptySet())
        val ids=all.filter{c->matchingWords.any{w->
            token.findAll(c.title.lowercase(Locale.ROOT)).any{it.value==w} ||
            (w.length>=4 && c.phoneNumber.contains(w))
        }}.map{it.threadId}.toSet()
        val remaining=words.filterNot{it in matchingWords}
            .filterNot{it in setOf("from","to","with","by","about","contact","texts","messages")}
        return Plan(remaining.joinToString(" "),ids)
    }

    /**
     * Extract the core subject from a conversational query. Details such as
     * "for her kid" may be context that was NEVER said in the conversation,
     * and must not become hard requirements. The model embeds the subject.
     * The caller separately filters contact/conversation names.
     */
    fun semanticTopic(query:String,unparsed:String):String {
        val afterAbout=Regex("""(?i)\\b(?:about|regarding|concerning)\\s+(.+)$""")
            .find(query)?.groupValues?.getOrNull(1)
        val starting=afterAbout ?: unparsed
        val withoutIntent=starting.replace(
            Regex("""(?i)^\\s*(?:(?:getting|buying|finding|choosing|picking|purchasing)\\s+)(?:(?:a|an|the)\\s+)?"""),
            "")
        val withoutRecipient=withoutIntent.replace(
            Regex("""(?i)\\s+for\\s+(?:(?:my|your|his|her|their|our)\\s+)?(?:kid|kids|child|children|son|daughter|friend|wife|husband)\\b.*$"""),
            "")
        return withoutRecipient.trim().ifBlank { unparsed.trim() }
    }

    fun ready(ctx:Context)=Store(ctx).use{store->
        store.readableDatabase.rawQuery("SELECT v FROM meta WHERE k='ready'",null).use{
            it.moveToFirst() && it.getString(0)=="1"
        }
    }
    fun count(ctx:Context):Int=Store(ctx).use{store->
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM docs",null).use{
            if(it.moveToFirst())it.getInt(0) else 0
        }
    }
    fun countText(ctx:Context):Int=Store(ctx).use{store->
        store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM docs WHERE length(trim(body))>0",null).use{
            if(it.moveToFirst())it.getInt(0) else 0
        }
    }
    fun lastScan(ctx:Context):Long=Store(ctx).use{store->
        store.readableDatabase.rawQuery("SELECT v FROM meta WHERE k='last'",null).use{
            if(it.moveToFirst())it.getString(0).toLongOrNull()?:0L else 0L
        }
    }
    private fun message(c:Cursor)=Message(
        id=c.getLong(1),body=c.getString(5),type=c.getInt(7),status=0,
        participants=ArrayList(),date=c.getInt(4),read=true,
        threadId=c.getLong(3),isMMS=c.getInt(2)==1,attachment=null,
        senderPhoneNumber=c.getString(6),senderName="",senderPhotoUri="",
        subscriptionId=-1
    )
    fun all(ctx:Context):List<Message> = Store(ctx).use{store->
        val results=ArrayList<Message>()
        store.readableDatabase.rawQuery("SELECT "+columns+" FROM docs WHERE length(trim(body))>0",null).use{c->
            while(c.moveToNext())if(!SafeStore.isSafe(ctx,c.getLong(3)))results.add(message(c))
        }
        results
    }
    fun byKeys(ctx:Context,keys:Collection<Long>):List<Message> {
        if(keys.isEmpty())return emptyList()
        return Store(ctx).use{store->
            val results=ArrayList<Message>()
            for(chunk in keys.chunked(300)){
                val binds=chunk.joinToString(","){"?"}
                store.readableDatabase.rawQuery("SELECT "+columns+" FROM docs WHERE k IN ("+binds+")",
                    chunk.map{it.toString()}.toTypedArray()).use{c->
                    while(c.moveToNext())if(!SafeStore.isSafe(ctx,c.getLong(3)))results.add(message(c))
                }
            }
            results
        }
    }
    fun threadKeys(ctx:Context,threads:Set<Long>):Set<Long> {
        if(threads.isEmpty())return emptySet()
        return Store(ctx).use{store->
            val result=HashSet<Long>()
            val binds=threads.joinToString(","){"?"}
            store.readableDatabase.rawQuery(
                "SELECT k,thread FROM docs WHERE thread IN ("+binds+")",
                threads.map{it.toString()}.toTypedArray()).use{c->
                while(c.moveToNext())if(!SafeStore.isSafe(ctx,c.getLong(1)))
                    result.add(c.getLong(0))
            }
            result
        }
    }
    fun search(ctx:Context,plan:Plan,limit:Int=120):List<Message> {
        if(plan.text.isBlank() && plan.threads.isEmpty())return emptyList()
        val clauses=ArrayList<String>()
        val args=ArrayList<String>()
        if(plan.threads.isNotEmpty()){
            clauses.add("thread IN ("+plan.threads.joinToString(","){"?"}+")")
            args.addAll(plan.threads.map{it.toString()})
        }
        val words=plan.text.split(' ').filter{it.isNotBlank()}
        for(word in words){
            clauses.add("body LIKE ?")
            args.add("%"+word+"%")
        }
        val where=if(clauses.isEmpty())"1=1" else clauses.joinToString(" AND ")
        return Store(ctx).use{store->
            val results=ArrayList<Message>()
            store.readableDatabase.rawQuery(
                "SELECT "+columns+" FROM docs WHERE "+where+" ORDER BY date DESC LIMIT "+limit,
                args.toTypedArray()).use{c->
                while(c.moveToNext())if(!SafeStore.isSafe(ctx,c.getLong(3)))
                    results.add(message(c))
            }
            results
        }
    }

    /** Full provider import, with a generation sweep to reconcile deletion. */
    fun sync(ctx:Context,stopped:()->Boolean) {
        val generation=System.currentTimeMillis()
        val sms=ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf("_id","thread_id","date","body","type","address"),
            null,null,"date DESC") ?: error("SMS provider inaccessible")
        val mms=ctx.contentResolver.query(Telephony.Mms.CONTENT_URI,
            arrayOf("_id","thread_id","date","msg_box"),
            null,null,"date DESC") ?: run{sms.close();error("MMS provider inaccessible")}
        try {
            val mmsText=HashMap<Long,StringBuilder>()
            ctx.contentResolver.query(Uri.parse("content://mms/part"),
                arrayOf("mid","ct","text"),"ct=?",arrayOf("text/plain"),null)?.use{parts->
                val mid=parts.getColumnIndexOrThrow("mid")
                val valCol=parts.getColumnIndexOrThrow("text")
                while(parts.moveToNext()) {
                    if(stopped())return
                    val txt=parts.getString(valCol).orEmpty()
                    if(txt.isNotBlank())
                        mmsText.getOrPut(parts.getLong(mid)){StringBuilder()}.append(txt).append(' ')
                }
            }
            Store(ctx).use{store->
                val db=store.writableDatabase
                db.beginTransaction()
                try {
                    val statement=db.compileStatement("INSERT OR REPLACE INTO docs(k,id,mms,thread,date,body,sender,kind,generation) VALUES(?,?,?,?,?,?,?,?,?)")
                    fun insert(id:Long,isMms:Boolean,thread:Long,date:Int,body:String,sender:String,kind:Int){
                        if(thread<=0 || SafeStore.isSafe(ctx,thread))return
                        statement.clearBindings()
                        statement.bindLong(1,key(id,isMms));statement.bindLong(2,id)
                        statement.bindLong(3,if(isMms)1L else 0L)
                        statement.bindLong(4,thread);statement.bindLong(5,date.toLong())
                        statement.bindString(6,body);statement.bindString(7,sender)
                        statement.bindLong(8,kind.toLong());statement.bindLong(9,generation)
                        statement.executeInsert()
                    }
                    val si=sms.getColumnIndexOrThrow("_id")
                    val st=sms.getColumnIndexOrThrow("thread_id")
                    val sd=sms.getColumnIndexOrThrow("date")
                    val sb=sms.getColumnIndexOrThrow("body")
                    val sk=sms.getColumnIndexOrThrow("type")
                    val sa=sms.getColumnIndexOrThrow("address")
                    while(sms.moveToNext()){
                        if(stopped())return
                        insert(sms.getLong(si),false,sms.getLong(st),
                            (sms.getLong(sd)/1000L).toInt(),sms.getString(sb).orEmpty(),
                            sms.getString(sa).orEmpty(),sms.getInt(sk))
                    }
                    val mi=mms.getColumnIndexOrThrow("_id")
                    val mt=mms.getColumnIndexOrThrow("thread_id")
                    val md=mms.getColumnIndexOrThrow("date")
                    val mk=mms.getColumnIndexOrThrow("msg_box")
                    while(mms.moveToNext()) {
                        if(stopped())return
                        val id=mms.getLong(mi)
                        insert(id,true,mms.getLong(mt),mms.getLong(md).toInt(),
                            mmsText[id]?.toString()?.trim().orEmpty(),"",mms.getInt(mk))
                    }
                    db.delete("docs","generation<>?",arrayOf(generation.toString()))
                    db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('ready','1')")
                    db.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('last',?)",
                        arrayOf<Any>(generation.toString()))
                    db.setTransactionSuccessful()
                }finally{db.endTransaction()}
            }
        }catch(e:Exception) {
            Log.e("SearchCatalog","Could not reconcile provider messages",e)
            throw e
        }finally{sms.close();mms.close()}
    }
}
