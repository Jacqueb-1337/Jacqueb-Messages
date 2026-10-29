package org.fossify.messages.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Message
import org.fossify.messages.safe.SafeStore
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Basic BERT uncased WordPiece tokenizer using the downloaded model's vocab. */
internal class WordPiece(private val words:Map<String,Long>) {
    constructor(vocab:java.io.File):this(vocab.useLines { lines ->
        lines.mapIndexed {index,word->word to index.toLong()}.toMap()
    })
    private val unk=words["[UNK]"] ?: 100L
    fun encode(text:String,maxLength:Int=128):LongArray {
        val clean=Normalizer.normalize(text.lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""),"")
        val basic=Regex("""[\p{L}\p{N}]+|[^\s\p{L}\p{N}]""")
            .findAll(clean).map{it.value}.toList()
        val result=ArrayList<Long>(maxLength)
        result.add(words["[CLS]"] ?: 101L)
        for(token in basic) {
            if(result.size>=maxLength-1)break
            if(token.length>100){result.add(unk);continue}
            var start=0
            val pieces=ArrayList<Long>()
            var failed=false
            while(start<token.length){
                var end=token.length
                var found:Long?=null
                while(start<end) {
                    val piece=token.substring(start,end)
                    found=words[if(start==0)piece else "##"+piece]
                    if(found!=null)break
                    end--
                }
                if(found==null){failed=true;break}
                pieces.add(found);start=end
            }
            val next=if(failed)listOf(unk) else pieces
            for(id in next) {
                if(result.size>=maxLength-1)break
                result.add(id)
            }
        }
        result.add(words["[SEP]"] ?: 102L)
        return result.toLongArray()
    }
}

/** One local ORT session reused for an indexing/search batch; CPU-only. */
internal class SemanticEmbedder(ctx:Context,engine:SemanticEngine):Closeable {
    private val env=OrtEnvironment.getEnvironment()
    private val options=OrtSession.SessionOptions().apply{
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val session=env.createSession(SemanticModelManager.model(ctx,engine).absolutePath,options)
    private val tokenizer=WordPiece(SemanticModelManager.vocab(ctx,engine))
    fun embed(text:String):FloatArray {
        val ids=tokenizer.encode(text)
        val mask=LongArray(ids.size){1L}
        val segment=LongArray(ids.size)
        val inputs=linkedMapOf<String,OnnxTensor>()
        try {
            if("input_ids" in session.inputNames)
                inputs["input_ids"]=OnnxTensor.createTensor(env,arrayOf(ids))
            if("attention_mask" in session.inputNames)
                inputs["attention_mask"]=OnnxTensor.createTensor(env,arrayOf(mask))
            if("token_type_ids" in session.inputNames)
                inputs["token_type_ids"]=OnnxTensor.createTensor(env,arrayOf(segment))
            check("input_ids" in inputs){"Unsupported ONNX model inputs"}
            session.run(inputs).use {result->
                val value=result[0].value
                val floats=when(value){
                    is Array<*> -> {
                        val first=value.firstOrNull()
                        if(first is FloatArray)first
                        else {
                            @Suppress("UNCHECKED_CAST")
                            val rows=first as Array<FloatArray>
                            val dim=rows.first().size
                            FloatArray(dim){d->
                                var sum=0f
                                for(i in rows.indices) if(mask[i]==1L)sum+=rows[i][d]
                                sum/mask.size.coerceAtLeast(1)
                            }
                        }
                    }
                    else->error("Unexpected embedding output type")
                }
                var squared=0.0
                floats.forEach{squared+=it.toDouble()*it.toDouble()}
                val norm=sqrt(squared).toFloat().coerceAtLeast(1e-8f)
                return FloatArray(floats.size){floats[it]/norm}
            }
        }finally {
            inputs.values.forEach{it.close()}
        }
    }
    override fun close() {session.close();options.close()}
}

/** Private cache of normalized quantized embeddings. It never stores message text. */
object SemanticIndex {
    // Warm the selected engine across successive queries, avoiding a costly
    // model file load on every keystroke. All inference is off the UI thread.
    private val inferenceLock=Any()
    private var warmedEngine:String?=null
    private var warmedModel:SemanticEmbedder?=null
    private fun queryVector(ctx:Context,engine:SemanticEngine,query:String):FloatArray =
        synchronized(inferenceLock) {
            if(warmedEngine!=engine.id || warmedModel==null) {
                warmedModel?.close()
                warmedEngine=null
                warmedModel=SemanticEmbedder(ctx,engine)
                warmedEngine=engine.id
            }
            warmedModel!!.embed(query)
        }
    fun unload()=synchronized(inferenceLock) {
        warmedModel?.close();warmedModel=null;warmedEngine=null
    }
    private class Store(ctx:Context):SQLiteOpenHelper(ctx,"semantic_vectors_v1.db",null,1) {
        override fun onCreate(db:SQLiteDatabase) {
            db.execSQL("""CREATE TABLE vectors(
                key INTEGER PRIMARY KEY, thread INTEGER NOT NULL,
                model TEXT NOT NULL, hash TEXT NOT NULL, vec BLOB NOT NULL)""")
            db.execSQL("CREATE INDEX idx_semantic_model ON vectors(model)")
            db.execSQL("CREATE INDEX idx_semantic_thread ON vectors(thread)")
        }
        override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) {
            db.execSQL("DROP TABLE IF EXISTS vectors");onCreate(db)
        }
    }
    private fun key(m:Message)=(m.id shl 1) or (if(m.isMMS)1L else 0L)
    private fun hash(body:String):String {
        val md=MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        return md.joinToString(""){"%02x".format(it)}
    }
    private fun quantize(vector:FloatArray):ByteArray =
        ByteArray(vector.size){(vector[it]*127).toInt().coerceIn(-127,127).toByte()}
    private fun similarity(query:FloatArray,bytes:ByteArray):Float {
        if(query.size!=bytes.size)return -1f
        var dot=0f
        var norm=0f
        for(i in query.indices){val v=bytes[i].toInt()/127f;dot+=query[i]*v;norm+=v*v}
        return dot/sqrt(norm).coerceAtLeast(0.00001f)
    }
    @Synchronized fun clear(ctx:Context) {
        Store(ctx).use{it.writableDatabase.delete("vectors",null,null)}
    }
    @Synchronized fun removeThread(ctx:Context,thread:Long){
        Store(ctx).use{it.writableDatabase.delete("vectors","thread=?",arrayOf(thread.toString()))}
    }
    @Synchronized fun count(ctx:Context,engine:SemanticEngine):Int = Store(ctx).use { store ->
        store.readableDatabase.rawQuery("SELECT count(*) FROM vectors WHERE model=?",arrayOf(engine.id))
            .use{c->if(c.moveToFirst())c.getInt(0) else 0}
    }
    /** Incremental: skip unchanged messages and remove disappeared/Safe entries.
     * Always background thread; caller controls scheduling and cancellation. */
    fun index(ctx:Context,stopped:()->Boolean={false},progress:(Int,Int)->Unit={_,_->}) {
        if(!SemanticSearchPrefs.optedIn(ctx) || !SemanticSearchPrefs.engineInstalled(ctx))return
        val engine=SemanticSearchPrefs.selected(ctx)
        if(!SearchCatalog.ready(ctx))return
        val candidates=SearchCatalog.all(ctx)
            .filter{it.body.isNotBlank() && !SafeStore.isSafe(ctx,it.threadId)}
        val db=Store(ctx)
        db.use {store ->
            val sql=store.writableDatabase
            sql.delete("vectors","model<>?",arrayOf(engine.id))
            val seen=HashSet<Long>()
            val previous=HashMap<Long,String>()
            sql.rawQuery("SELECT key,hash FROM vectors WHERE model=?",arrayOf(engine.id)).use {cursor->
                while(cursor.moveToNext())previous[cursor.getLong(0)]=cursor.getString(1)
            }
            SemanticEmbedder(ctx,engine).use{embedder->
                candidates.forEachIndexed{idx,message->
                    if(stopped())return
                    val k=key(message)
                    // A conversation may enter Safe after the candidate list
                    // is collected. Never write its embeddings in that case.
                    if(SafeStore.isSafe(ctx,message.threadId)) {
                        sql.delete("vectors","key=?",arrayOf(k.toString()))
                        return@forEachIndexed
                    }
                    seen.add(k)
                    val bodyHash=hash(message.body)
                    if(previous[k]!=bodyHash){
                        val vector=quantize(embedder.embed(message.body.take(1400)))
                        val values=android.content.ContentValues().apply{
                            put("key",k);put("thread",message.threadId)
                            put("model",engine.id);put("hash",bodyHash);put("vec",vector)
                        }
                        sql.insertWithOnConflict("vectors",null,values,SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    if(idx%20==0 || idx==candidates.size-1)progress(idx+1,candidates.size)
                }
            }
            // Remove cached vectors from deleted, recycled, or moved-to-Safe messages.
            previous.keys.minus(seen).forEach{k->
                sql.delete("vectors","key=?",arrayOf(k.toString()))
            }
            SafeStore.safeThreads(ctx).forEach{threadId ->
                sql.delete("vectors","thread=?",arrayOf(threadId.toString()))
            }
        }
    }
    /** Query only one model embedding, score precomputed vectors. */
    fun search(ctx:Context,query:String,limit:Int=35):List<Message> {
        if(!SemanticSearchPrefs.optedIn(ctx) || !SemanticSearchPrefs.engineInstalled(ctx) || query.length<3)
            return emptyList()
        val engine=SemanticSearchPrefs.selected(ctx)
        val plan=SearchCatalog.plan(ctx,query)
        if(plan.text.isBlank())return SearchCatalog.search(ctx,plan,limit)
        // Natural-language queries can include facts not mentioned in SMS.
        // Embed the topic rather than requiring incidental recipient details.
        val topic=SearchCatalog.semanticTopic(query,plan.text)
        val vector=queryVector(ctx,engine,topic.take(512))
        val allowedKeys=if(plan.contactFilter)SearchCatalog.threadKeys(ctx,plan.threads)
                        else emptySet()
        val scored=ArrayList<Pair<Long,Float>>()
        Store(ctx).use {store->
            store.readableDatabase.rawQuery("SELECT key,vec FROM vectors WHERE model=?",
                arrayOf(engine.id)).use {cursor->
                while(cursor.moveToNext()){
                    val candidateKey=cursor.getLong(0)
                    if(plan.contactFilter && candidateKey !in allowedKeys)continue
                    val score=similarity(vector,cursor.getBlob(1))
                    if(score>=.3f)scored.add(candidateKey to score)
                }
            }
        }
        val best=scored.sortedByDescending{it.second}.take(limit).map{it.first}
        if(best.isEmpty())return emptyList()
        val possible=SearchCatalog.byKeys(ctx,best)
            .filterNot{SafeStore.isSafe(ctx,it.threadId)}
            .associateBy{key(it)}
        return best.mapNotNull{possible[it]}
            .filter{!plan.threads.any() || it.threadId in plan.threads}
    }
}
