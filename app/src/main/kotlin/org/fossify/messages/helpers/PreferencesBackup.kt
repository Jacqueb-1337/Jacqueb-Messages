package org.fossify.messages.helpers

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Portable Jacqueb-only preferences backup, deliberately separate from the
 * existing Fossify-compatible SMS/MMS JSON array export. No credentials or
 * app-lock secrets are exported. */
object PreferencesBackup {
    const val FORMAT = "com.jacqueb.messages.preferences"
    private const val LIMIT = 64 * 1024 * 1024
    private val forbidden = Regex("password|passcode|secret|token|oauth|api.?key|credential|private.?key|pin_code|fingerprint|biometric|session|cookie", RegexOption.IGNORE_CASE)
    private fun eligible(name: String): Boolean = name.matches(Regex("[A-Za-z0-9_.-]{1,120}")) && !forbidden.containsMatchIn(name)
    // Safe unlock credentials AND thread membership are security metadata.
    // Never include them in portable preferences exports or imports.
    private fun includeName(name: String): Boolean = eligible(name) &&
        !name.startsWith("messages_private_safe", ignoreCase=true) && (
        name.contains("message",true) || name.contains("theme",true) ||
        name.contains("config",true) || name.contains("head",true) ||
        name.contains("convers",true) || name.contains("overlay",true) ||
        name.contains("fossify",true) || name.contains("setting",true) ||
        name.contains("pref",true))

    fun export(context: Context, uri: Uri) {
        val root = JSONObject().put("format", FORMAT).put("schema", 1)
        val group = JSONObject()
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            if (!includeName(name)) return@forEach
            val values = JSONObject()
            context.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (forbidden.containsMatchIn(key)) return@forEach
                val obj = JSONObject()
                when (value) {
                    is String -> obj.put("type","string").put("value",value)
                    is Boolean -> obj.put("type","bool").put("value",value)
                    is Int -> obj.put("type","int").put("value",value)
                    is Long -> obj.put("type","long").put("value",value)
                    is Float -> obj.put("type","float").put("value",value.toDouble())
                    is Set<*> -> obj.put("type","strings").put("value",JSONArray(value.filterIsInstance<String>()))
                    else -> return@forEach
                }
                values.put(key,obj)
            }
            group.put(name,values)
        }
        root.put("preferences",group)
        val images = JSONObject()
        File(context.filesDir,"conversation_icons").listFiles()?.forEach { file ->
            val isGif=file.extension.equals("gif",ignoreCase=true)
            val max=if(isGif) 8_388_608L else 2_000_000L
            if (file.isFile && file.name.matches(Regex("[0-9]+[.](png|gif)")) && file.length() in 1..max)
                images.put(file.name,Base64.encodeToString(file.readBytes(),Base64.NO_WRAP))
        }
        root.put("conversation_icons",images)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT) { "Preferences backup exceeds 64 MB" }
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to write backup")
    }

    fun isPreferences(text: String): Boolean = try {
        JSONObject(text).optString("format") == FORMAT
    } catch (_: Exception) { false }

    fun import(context: Context, text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= LIMIT) { "Preferences file too large" }
        val root = JSONObject(text)
        require(root.getString("format") == FORMAT && root.getInt("schema") == 1) { "Unsupported backup format" }
        val all = root.getJSONObject("preferences")
        val names = all.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (!includeName(name)) continue
            val values = all.getJSONObject(name)
            val editor = context.getSharedPreferences(name,Context.MODE_PRIVATE).edit()
            val keys = values.keys()
            while(keys.hasNext()) {
                val key=keys.next()
                if (forbidden.containsMatchIn(key)) continue
                val entry=values.getJSONObject(key)
                when(entry.optString("type")) {
                    "string" -> editor.putString(key,entry.getString("value"))
                    "bool" -> editor.putBoolean(key,entry.getBoolean("value"))
                    "int" -> editor.putInt(key,entry.getInt("value"))
                    "long" -> editor.putLong(key,entry.getLong("value"))
                    "float" -> editor.putFloat(key,entry.getDouble("value").toFloat())
                    "strings" -> {
                        val a=entry.getJSONArray("value")
                        editor.putStringSet(key,(0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            check(editor.commit()) { "Could not restore preferences $name" }
        }
        val images = root.optJSONObject("conversation_icons")
        if (images != null) {
            val dir=File(context.filesDir,"conversation_icons").apply { mkdirs() }
            val keys=images.keys()
            while(keys.hasNext()) {
                val name=keys.next()
                if (!name.matches(Regex("[0-9]+[.](png|gif)"))) continue
                val data=Base64.decode(images.getString(name),Base64.DEFAULT)
                val isGif=name.endsWith(".gif")
                val valid=if(isGif) ConversationCustomization.validGifBytes(data)
                          else data.size in 1..2_000_000
                if (valid) {
                    val id=name.substringBefore('.').toLongOrNull() ?: continue
                    val destination=File(dir,name)
                    val tmp=File(dir,"$id.restore")
                    try {
                        tmp.writeBytes(data)
                        if(!tmp.renameTo(destination)) {
                            tmp.copyTo(destination,overwrite=true)
                            tmp.delete()
                        }
                        File(dir,"$id.${if(isGif) "png" else "gif"}").delete()
                        context.getSharedPreferences("conversation_personalization_v1",Context.MODE_PRIVATE)
                            .edit().putLong("t_${id}_photo_updated",System.currentTimeMillis()).apply()
                    } finally { tmp.delete() }
                }
            }
        }
    }
}
