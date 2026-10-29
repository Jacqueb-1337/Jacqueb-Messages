package org.fossify.messages.search

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Optional locally installed ONNX embeddings. No personal data is uploaded. */
enum class SemanticEngine(
    val id:String,val label:String,val description:String,val sizeMb:Int,
    val source:String,val modelHash:String
) {
    FAST("fast","Fast","3-layer MiniLM, prioritizes speed",18,
        "sentence-transformers/paraphrase-MiniLM-L3-v2",
        "44a2d6852c28e7a4cff4d77a8bc8d5ca2a8b38884c1fa746e6652d830109e0c2"),
    ACCURATE("accurate","Accurate","6-layer MiniLM, prioritizes relevance",23,
        "sentence-transformers/all-MiniLM-L6-v2",
        "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474");
    val modelUrl get()="https://huggingface.co/"+source+"/resolve/main/onnx/model_qint8_arm64.onnx"
    val vocabUrl get()="https://huggingface.co/"+source+"/resolve/main/vocab.txt"
    companion object {fun byId(id:String)=entries.firstOrNull{it.id==id}?:FAST}
}
object SemanticModelManager {
    fun directory(ctx:Context,engine:SemanticEngine)=
        File(ctx.noBackupFilesDir,"semantic-search/"+engine.id).apply{mkdirs()}
    fun model(ctx:Context,e:SemanticEngine)=File(directory(ctx,e),"model.onnx")
    fun vocab(ctx:Context,e:SemanticEngine)=File(directory(ctx,e),"vocab.txt")
    private fun marker(ctx:Context,e:SemanticEngine)=File(directory(ctx,e),"verified.sha256")
    fun installed(ctx:Context,e:SemanticEngine):Boolean =
        model(ctx,e).isFile && model(ctx,e).length()>10_000_000 &&
        vocab(ctx,e).isFile && vocab(ctx,e).length()>100_000 &&
        runCatching{marker(ctx,e).readText().trim()==e.modelHash}.getOrDefault(false)
    fun delete(ctx:Context,e:SemanticEngine) {
        SemanticIndex.unload()
        directory(ctx,e).deleteRecursively()
        if(SemanticSearchPrefs.selected(ctx)==e)SemanticIndex.clear(ctx)
    }
    fun install(ctx:Context,e:SemanticEngine,cancelled:AtomicBoolean,
                progress:(Int,String)->Unit) {
        if(installed(ctx,e))return
        marker(ctx,e).delete()
        download(e.modelUrl,model(ctx,e),cancelled,e.modelHash,40_000_000L){
            progress(it*96/100,"Downloading "+e.label+" model")
        }
        download(e.vocabUrl,vocab(ctx,e),cancelled,null,700_000L){
            progress(96+it*4/100,"Downloading tokenizer")
        }
        check(vocab(ctx,e).useLines{it.take(110).contains("[UNK]")}){"Invalid tokenizer"}
        check(sha256(model(ctx,e))==e.modelHash){"Model checksum mismatch"}
        marker(ctx,e).writeText(e.modelHash)
        progress(100,"Verified and installed")
    }
    private fun sha256(file:File):String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val block=ByteArray(65536)
            while(true){val n=input.read(block);if(n<0)break;md.update(block,0,n)}
        }
        return md.digest().joinToString(""){"%02x".format(it)}
    }
    private fun download(url:String,file:File,cancelled:AtomicBoolean,sha:String?,
                         maxBytes:Long,progress:(Int)->Unit) {
        val temp=File(file.parentFile,file.name+".partial")
        val digest=MessageDigest.getInstance("SHA-256")
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=18000;connection.readTimeout=28000
        connection.instanceFollowRedirects=true
        try {
            check(connection.responseCode in 200..299){"Download HTTP "+connection.responseCode}
            val length=connection.contentLengthLong
            check(length<0 || length<=maxBytes)
            var total=0L
            connection.inputStream.use {input->
                temp.outputStream().use {output->
                    val buffer=ByteArray(65536)
                    while(true) {
                        if(cancelled.get())error("Download cancelled")
                        val n=input.read(buffer)
                        if(n<0)break
                        total+=n
                        check(total<=maxBytes){"Download size exceeded"}
                        digest.update(buffer,0,n)
                        output.write(buffer,0,n)
                        if(length>0)progress((total*100/length).toInt().coerceIn(0,100))
                    }
                    output.flush()
                }
            }
            check(total>100_000L && (length<0 || total==length)){"Incomplete file"}
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            if(sha!=null)check(hash==sha){"SHA-256 verification failed"}
            if(file.exists())check(file.delete())
            check(temp.renameTo(file)){"Cannot install downloaded model"}
        } finally {connection.disconnect();temp.delete()}
    }
}
