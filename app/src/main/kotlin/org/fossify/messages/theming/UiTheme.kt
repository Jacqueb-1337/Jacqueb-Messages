package org.fossify.messages.theming

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

/** Shared palette, shape, and density-aware geometry for the inbox and conversations.
 * Built-in themes are immutable; editing one automatically creates an unsaved draft.
 * Named themes persist as JSON snapshots; stored values are local, never message data.
 */
object UiTheme {
    private const val PREF = "messages_visual_theme_v2"
    private const val ACTIVE = "active"
    private const val NAMES = "named"
    private const val DRAFT = "draft"
    val presets=linkedMapOf(
        "oneui" to "One UI",
        "material" to "Material You",
        "amoled" to "AMOLED",
        "classic" to "Classic",
        "default" to "Original"
    )
    val colors=linkedMapOf(
        "surface" to "Screen background",
        "card" to "Conversation list background",
        "search" to "Search bar",
        "title" to "Contact names / primary text",
        "preview" to "Message previews",
        "draft_text" to "Draft indicator text",
        "date" to "Timestamps",
        "divider" to "Conversation separators",
        "accent" to "Active tabs / highlights",
        "badge" to "Unread badges",
        "badge_text" to "Badge number",
        "avatar" to "Contact avatar",
        "group" to "Group avatar",
        "selected" to "Selected row",
        "incoming" to "Received bubble",
        "outgoing" to "Sent bubble",
        "incoming_text" to "Received text",
        "outgoing_text" to "Sent text",
        "composer" to "Composer background",
        "composer_text" to "Composer text",
        "send" to "Send button",
        "send_icon" to "Send icon",
        "fab" to "New-message + button",
        "fab_icon" to "New-message + icon"
    )
    val shapes=linkedMapOf(
        "card_radius" to "Conversation list corners",
        "badge_radius" to "Unread badge corners",
        "avatar_radius" to "Contact avatar corners",
        "group_radius" to "Group avatar corners",
        "bubble_radius" to "Chat bubble corners",
        "composer_radius" to "Input corners",
        "send_radius" to "Send button corners",
        "fab_radius" to "New-message button corners"
    )
    val spacing=linkedMapOf(
        "row_height" to "Conversation row height",
        "row_padding" to "Row vertical padding",
        "inbox_inset" to "List horizontal inset",
        "avatar_size" to "Avatar size",
        "divider_inset" to "Divider start inset",
        "divider_thickness" to "Divider thickness",
        "bubble_padding" to "Bubble text padding",
        "bubble_gap" to "Message separation",
        "composer_height" to "Composer height",
        "fab_size" to "New-message button size",
        "fab_offset" to "New-message button upward fine adjustment",
        "fab_x_percent" to "New-message button horizontal position",
        "fab_y_percent" to "New-message button vertical position",
        "bubble_side_incoming" to "Received bubble distance from screen edge",
        "bubble_side_outgoing" to "Sent bubble distance from screen edge",
        "avatar_bubble_gap" to "Gap between avatar and received bubble",
        "bubble_width_percent" to "Maximum message width",
        "row_text_gap" to "Gap between inbox avatar and message text"
    )
    private val metrics=mapOf(
        "row_height" to 82, "row_padding" to 14, "inbox_inset" to 6,
        "avatar_size" to 42, "divider_inset" to 75, "divider_thickness" to 1,
        "bubble_padding" to 14,"bubble_gap" to 6, "composer_height" to 52,
        "fab_size" to 56,"fab_offset" to 0,"fab_x_percent" to 88,
        "fab_y_percent" to 88,
        "bubble_side_incoming" to 20,"bubble_side_outgoing" to 20,
        "avatar_bubble_gap" to 8,"bubble_width_percent" to 83,
        "row_text_gap" to 14
    )
    private val radii=mapOf(
        "card_radius" to 27,"badge_radius" to 100,"avatar_radius" to 100,
        "group_radius" to 100,"bubble_radius" to 16,"composer_radius" to 28,
        "send_radius" to 100,"fab_radius" to 22
    )
    private val refs=mapOf(
        "surface" to R.color.samsung_surface,
        "card" to R.color.samsung_card,
        "search" to R.color.samsung_search_toolbar,
        "title" to R.color.samsung_text_primary,
        "preview" to R.color.samsung_text_secondary,
        "date" to R.color.samsung_text_secondary,
        "divider" to R.color.samsung_line,
        "accent" to R.color.samsung_accent,
        "badge" to R.color.samsung_accent,
        "badge_text" to android.R.color.white,
        "avatar" to R.color.samsung_accent,
        "group" to R.color.samsung_send,
        "selected" to R.color.samsung_soft_surface,
        "incoming" to R.color.samsung_received,
        "outgoing" to R.color.samsung_sent,
        "incoming_text" to R.color.samsung_text_primary,
        "outgoing_text" to android.R.color.white,
        "composer" to R.color.samsung_composer_surface,
        "composer_text" to R.color.samsung_text_primary,
        "send" to R.color.samsung_send,
        "send_icon" to android.R.color.white,
        "fab" to R.color.samsung_send,
        "fab_icon" to android.R.color.white
    )
    /** In-memory theme transaction scoped to Theme Studio. No preference writes
     * take place during editing, including switching or naming themes. */
    private data class StudioSession(
        var activeId:String,
        val working:MutableMap<String,JSONObject> = linkedMapOf(),
        val saved:MutableMap<String,JSONObject> = linkedMapOf(),
        val names:MutableList<Pair<String,String>> = mutableListOf(),
        val deleted:MutableSet<String> = mutableSetOf()
    )
    private var studio:StudioSession?=null
    private fun session(ctx:Context):StudioSession? =
        if(ctx is ThemeEditorActivity) studio else null
    private fun persistedActive(ctx:Context)=prefs(ctx).getString(ACTIVE,"oneui") ?: "oneui"
    private fun copy(obj:JSONObject)=JSONObject(obj.toString())
    private fun savedTheme(ctx:Context,id:String):JSONObject =
        if(id in presets) JSONObject().put("base",id) else stored(ctx,id)
    private fun loadSessionTheme(ctx:Context,id:String):JSONObject {
        val s=session(ctx) ?: return savedTheme(ctx,id)
        if(id !in s.working) {
            val data=savedTheme(ctx,id)
            s.saved[id]=copy(data)
            s.working[id]=copy(data)
        }
        return s.working.getValue(id)
    }
    fun beginStudio(ctx:Context,restored:String?=null) {
        val recovered=runCatching {
            if(restored.isNullOrBlank())null else {
                val j=JSONObject(restored)
                val result=StudioSession(j.getString("active"))
                fun readObjects(key:String,target:MutableMap<String,JSONObject>) {
                    val data=j.getJSONObject(key)
                    for(k in data.keys())target[k]=copy(data.getJSONObject(k))
                }
                readObjects("working",result.working)
                readObjects("saved",result.saved)
                val labels=j.getJSONArray("names")
                for(i in 0 until labels.length()) {
                    val pair=labels.getJSONObject(i)
                    result.names.add(pair.getString("id") to pair.getString("name"))
                }
                val deleted=j.getJSONArray("deleted")
                for(i in 0 until deleted.length())result.deleted.add(deleted.getString(i))
                result
            }
        }.getOrNull()
        studio=recovered ?: StudioSession(persistedActive(ctx),names=persistedNamed(ctx).toMutableList())
    }
    fun studioState(ctx:Context):String {
        val s=session(ctx) ?: return ""
        val j=JSONObject().put("active",s.activeId)
        for((label,values) in listOf("working" to s.working,"saved" to s.saved)) {
            val o=JSONObject()
            values.forEach{(key,value)->o.put(key,copy(value))}
            j.put(label,o)
        }
        val names=JSONArray()
        s.names.forEach{(id,name)->names.put(JSONObject().put("id",id).put("name",name))}
        val deleted=JSONArray()
        s.deleted.forEach{deleted.put(it)}
        return j.put("names",names).put("deleted",deleted).toString()
    }
    fun commitStudio(ctx:Context) {
        val s=studio ?: return
        val editor=prefs(ctx).edit().putString(ACTIVE,s.activeId)
        s.working.forEach { (id,value) ->
            if(id !in presets && id !in s.deleted && (value.toString()!=s.saved[id]?.toString() || !prefs(ctx).contains("data_$id")))
                editor.putString("data_$id",value.toString())
        }
        s.deleted.forEach { editor.remove("data_$it") }
        val names=JSONArray()
        s.names.forEach{(id,name)->names.put(JSONObject().put("id",id).put("name",name))}
        editor.putString(NAMES,names.toString())
        check(editor.commit()) {"Could not save Theme Studio edits"}
        studio=null
    }
    /** Reset to the saved property value, not the built-in preset value. */
    private fun baseline(ctx:Context):JSONObject {
        val s=session(ctx) ?: return savedTheme(ctx,active(ctx))
        loadSessionTheme(ctx,s.activeId)
        return s.saved.getValue(s.activeId)
    }
    private fun baselineMode(ctx:Context):JSONObject =
        baseline(ctx).optJSONObject(if(isDark(ctx))"dark" else "light") ?: JSONObject()
    private fun baselineBase(ctx:Context):String =
        baseline(ctx).optString("base","oneui").takeIf{it in presets} ?: "oneui"
    fun savedColor(ctx:Context,key:String):Int {
        val values=baselineMode(ctx)
        return if(values.has("c_$key"))values.optInt("c_$key") else presetColor(ctx,baselineBase(ctx),key)
    }
    fun savedMetric(ctx:Context,key:String):Int {
        val values=baselineMode(ctx)
        return if(values.has("m_$key"))values.optInt("m_$key").coerceIn(minMetric(key),140)
            else presetMetric(baselineBase(ctx),key)
    }
    fun savedRadius(ctx:Context,key:String):Int {
        val values=baselineMode(ctx)
        return if(values.has("r_$key"))values.optInt("r_$key").coerceIn(0,100)
            else presetRadius(baselineBase(ctx),key)
    }
    private fun prefs(ctx:Context)=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun isDark(ctx:Context)=
        ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    fun active(ctx:Context)=session(ctx)?.activeId ?: persistedActive(ctx)
    fun title(ctx:Context):String = when(val id=active(ctx)){
        DRAFT -> "Unsaved edits"
        in presets -> presets[id] ?: id
        else -> named(ctx).firstOrNull{it.first==id}?.second ?: "Custom theme"
    }
    fun hasDraft(ctx:Context)=session(ctx)?.working?.containsKey(DRAFT)==true || prefs(ctx).contains("data_$DRAFT")
    fun named(ctx:Context):List<Pair<String,String>> = session(ctx)?.names?.toList() ?: persistedNamed(ctx)
    private fun persistedNamed(ctx:Context):List<Pair<String,String>>{
        val arr=try{JSONArray(prefs(ctx).getString(NAMES,"[]") ?: "[]")}catch(_:Exception){JSONArray()}
        return (0 until arr.length()).mapNotNull{
            val o=arr.optJSONObject(it)
            val id=o?.optString("id").orEmpty()
            val name=o?.optString("name").orEmpty()
            if(id.isBlank()||name.isBlank())null else id to name
        }
    }
    private fun stored(ctx:Context,id:String):JSONObject =
        try{JSONObject(prefs(ctx).getString("data_$id","{}") ?: "{}")}catch(_:Exception){JSONObject()}
    private fun root(ctx:Context):JSONObject {
        val id=active(ctx)
        return loadSessionTheme(ctx,id)
    }
    private fun base(ctx:Context)=root(ctx).optString("base","oneui").let{
        if(it in presets)it else "oneui"
    }
    private fun overrides(ctx:Context):JSONObject =
        root(ctx).optJSONObject(if(isDark(ctx))"dark" else "light") ?: JSONObject()

    private fun presetColor(ctx:Context,preset:String,key:String):Int{
        val dark=isDark(ctx)
        val default=ContextCompat.getColor(ctx, refs[key] ?: R.color.samsung_accent)
        fun hex(s:String)=Color.parseColor(s)
        // Independent of the theme accent, unless the user customizes it.
        if(key=="draft_text") return hex("#FF8A00")
        return when(preset) {
            "amoled" -> when(key){
                "surface","card" -> Color.BLACK
                "selected","search","composer" -> hex("#151515")
                "divider" -> hex("#303030")
                "outgoing","accent","badge" -> hex("#2D84FF")
                "fab","send" -> hex("#00B998")
                else -> default
            }
            "material" -> when(key){
                "surface" -> hex(if(dark)"#15131B" else "#FFFBFE")
                "card" -> hex(if(dark)"#24212A" else "#F3EDF7")
                "search","composer" -> hex(if(dark)"#39343F" else "#ECE6F0")
                "selected" -> hex(if(dark)"#443B51" else "#E8DEF8")
                "accent","badge","avatar","outgoing" -> hex(if(dark)"#D0BCFF" else "#6750A4")
                "outgoing_text" -> hex(if(dark)"#241735" else "#FFFFFF")
                "send","fab" -> hex(if(dark)"#D0BCFF" else "#6750A4")
                "send_icon","fab_icon" -> hex(if(dark)"#241735" else "#FFFFFF")
                "divider" -> hex(if(dark)"#49454F" else "#CAC4D0")
                else -> default
            }
            "classic" -> when(key){
                "surface","card" -> hex(if(dark)"#171717" else "#FFFFFF")
                "search","composer" -> hex(if(dark)"#303030" else "#EFEFEF")
                "accent","badge","avatar","outgoing" -> hex("#138BDD")
                "fab","send" -> hex("#138BDD")
                "selected" -> hex(if(dark)"#282828" else "#E8F4FE")
                else -> default
            }
            "oneui" -> when(key){
                "surface" -> hex(if(dark)"#010102" else "#F4F4F5")
                "card" -> hex(if(dark)"#161617" else "#FFFFFF")
                "search","composer" -> hex(if(dark)"#303033" else "#ECECEF")
                "selected" -> hex(if(dark)"#303033" else "#F0F3FB")
                "accent" -> hex(if(dark)"#387AFF" else "#1675EF")
                "badge" -> hex("#45B7D8")
                "outgoing" -> hex(if(dark)"#1A5FDC" else "#387AFF")
                "fab","send" -> hex(if(dark)"#009078" else "#00A287")
                else -> default
            }
            else -> default
        }
    }
    fun color(ctx:Context,key:String):Int{
        val o=overrides(ctx)
        return if(o.has("c_$key"))o.optInt("c_$key")
            else presetColor(ctx,base(ctx),key)
    }
    private fun presetMetric(preset:String,key:String):Int{
        val value=metrics[key] ?: 12
        return when(preset){
            "material" -> when(key){
                "row_height"->88;"row_padding"->16;"avatar_size"->44
                "inbox_inset"->12;"divider_inset"->78;"bubble_gap"->8
                "fab_size"->58;"fab_offset"->0;"fab_x_percent"->88
                "fab_y_percent"->88
                "bubble_side_incoming","bubble_side_outgoing"->16
                "bubble_width_percent"->88;"avatar_bubble_gap"->9
                "row_text_gap"->16
                else->value
            }
            "classic" -> when(key){
                "row_height"->70;"row_padding"->8;"inbox_inset"->0
                "avatar_size"->40;"divider_inset"->70
                "fab_size"->52;"fab_offset"->0;"fab_x_percent"->90
                "fab_y_percent"->90
                "bubble_side_incoming","bubble_side_outgoing"->12
                "bubble_width_percent"->92;"avatar_bubble_gap"->6
                "row_text_gap"->10
                else->value
            }
            else -> value
        }
    }
    private fun presetRadius(preset:String,key:String):Int = when(preset){
        "material" -> when(key){
            "card_radius"->28;"badge_radius"->20;"avatar_radius"->100
            "bubble_radius"->23;"composer_radius"->27
            "send_radius"->18;"fab_radius"->17
            else->radii[key]?:16
        }
        "classic"->when(key){
            "card_radius"->0;"bubble_radius"->8;"composer_radius"->8
            "send_radius"->8;"fab_radius"->8
            else->radii[key]?:16
        }
        else -> radii[key]?:16
    }
    private fun minMetric(key:String)=when(key){
        "row_height"->56;"avatar_size"->24;"divider_thickness"->1
        "composer_height"->40;"fab_size"->40
        "bubble_width_percent"->45
        else->0
    }
    fun metric(ctx:Context,key:String):Int{
        val o=overrides(ctx)
        return if(o.has("m_$key"))o.optInt("m_$key").coerceIn(minMetric(key),140)
            else presetMetric(base(ctx),key)
    }
    fun radius(ctx:Context,key:String):Int{
        val o=overrides(ctx)
        return if(o.has("r_$key"))o.optInt("r_$key").coerceIn(0,100)
            else presetRadius(base(ctx),key)
    }
    private fun edit(ctx:Context, update:(JSONObject)->Unit){
        val p=prefs(ctx)
        val current=active(ctx)
        val id=if(current in presets) DRAFT else current
        val preview=session(ctx)
        val obj=if(id==DRAFT && current in presets)
            JSONObject().put("base",current) else
            if(preview!=null) loadSessionTheme(ctx,id) else stored(ctx,id)
        val mode=if(isDark(ctx))"dark" else "light"
        val values=obj.optJSONObject(mode) ?: JSONObject().also{obj.put(mode,it)}
        update(values)
        if(preview!=null){
            if(current in presets) {
                // A new draft starts from the currently chosen preset, even if
                // an older saved draft happens to exist.
                preview.saved[id]=JSONObject().put("base",current)
            }
            preview.working[id]=obj
            preview.activeId=id
        } else p.edit().putString("data_$id",obj.toString()).putString(ACTIVE,id).apply()
    }
    fun setColor(ctx:Context,key:String,value:Int){
        require(key in colors);edit(ctx){it.put("c_$key",value)}
    }
    fun setRadius(ctx:Context,key:String,value:Int){
        require(key in shapes);edit(ctx){it.put("r_$key",value.coerceIn(0,100))}
    }
    fun setMetric(ctx:Context,key:String,value:Int){
        require(key in spacing);edit(ctx){it.put("m_$key",value.coerceIn(minMetric(key),140))}
    }
    fun switch(ctx:Context,id:String){
        require(id in presets || id==DRAFT && prefs(ctx).contains("data_$DRAFT")
            || named(ctx).any{it.first==id})
        val preview=session(ctx)
        if(preview!=null){
            loadSessionTheme(ctx,id)
            preview.activeId=id
        } else prefs(ctx).edit().putString(ACTIVE,id).apply()
    }
    fun saveAs(ctx:Context,name:String):String {
        val n=name.trim().take(60)
        require(n.isNotBlank())
        require(named(ctx).none{it.second.equals(n,ignoreCase=true)})
        val id="user_"+UUID.randomUUID().toString()
        val source=loadSessionTheme(ctx,active(ctx))
        val preview=session(ctx)
        if(preview!=null){
            preview.saved[id]=copy(source)
            preview.working[id]=copy(source)
            preview.names.add(id to n)
            preview.activeId=id
            return id
        }
        val arr=JSONArray()
        named(ctx).forEach { (existing,label)->arr.put(JSONObject().put("id",existing).put("name",label)) }
        arr.put(JSONObject().put("id",id).put("name",n))
        prefs(ctx).edit().putString("data_$id",source.toString())
            .putString(NAMES,arr.toString()).putString(ACTIVE,id).apply()
        return id
    }
    fun delete(ctx:Context,id:String){
        require(id !in presets && id != DRAFT)
        session(ctx)?.let { preview ->
            preview.names.removeAll { it.first==id }
            preview.deleted.add(id)
            preview.working.remove(id)
            if(preview.activeId==id)preview.activeId="oneui"
            return
        }
        val arr=JSONArray()
        named(ctx).filterNot{it.first==id}.forEach{(key,label)->
            arr.put(JSONObject().put("id",key).put("name",label))
        }
        val e=prefs(ctx).edit().putString(NAMES,arr.toString()).remove("data_$id")
        if(active(ctx)==id)e.putString(ACTIVE,"oneui")
        e.apply()
    }
    fun resetCurrentMode(ctx:Context){
        val id=active(ctx)
        if(id in presets)return
        val mode=if(isDark(ctx))"dark" else "light"
        session(ctx)?.let { preview ->
            val baseline=baseline(ctx)
            val current=loadSessionTheme(ctx,id)
            if(baseline.has(mode))current.put(mode,copy(baseline.getJSONObject(mode)))
            else current.remove(mode)
            return
        }
        val obj=stored(ctx,id)
        obj.remove(mode)
        prefs(ctx).edit().putString("data_$id",obj.toString()).apply()
    }
    /** Shared coordinate mapping for the actual inbox and the Theme Studio preview. */
    fun fabPosition(ctx:Context, width:Int, height:Int, buttonWidth:Int, buttonHeight:Int):Pair<Float,Float>{
        val availableX=(width-buttonWidth).coerceAtLeast(0)
        val availableY=(height-buttonHeight).coerceAtLeast(0)
        val x=availableX*(metric(ctx,"fab_x_percent").coerceIn(0,100)/100f)
        val up=dp(ctx,metric(ctx,"fab_offset"))
        val y=(availableY*(metric(ctx,"fab_y_percent").coerceIn(0,100)/100f)-up)
            .coerceIn(0f,availableY.toFloat())
        return x to y
    }
    /** Shared themed placeholder used by inbox, chat heads and notifications.
     * Real contact pictures and per-conversation image overrides take priority. */
    fun avatarBitmap(ctx:Context,label:String,group:Boolean):android.graphics.Bitmap {
        val size=128
        val bitmap=android.graphics.Bitmap.createBitmap(size,size,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color=color(ctx,if(group)"group" else "avatar")
        val configured=radius(ctx,if(group)"group_radius" else "avatar_radius")
        val r=(configured / 100f * 64f).coerceIn(0f,64f)
        canvas.drawRoundRect(0f,0f,size.toFloat(),size.toFloat(),r,r,paint)
        paint.color=color(ctx,"badge_text")
        if(group) {
            val centers=arrayOf(43f to 50f,83f to 50f,64f to 78f)
            for((x,y) in centers)canvas.drawCircle(x,y,13f,paint)
            paint.strokeWidth=8f
            canvas.drawLine(43f,55f,64f,78f,paint)
            canvas.drawLine(83f,55f,64f,78f,paint)
        }else{
            val letter=label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            paint.textAlign=android.graphics.Paint.Align.CENTER
            paint.typeface=android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL)
            paint.textSize=65f
            val center=(paint.descent()+paint.ascent())/2f
            canvas.drawText(letter,64f,64f-center,paint)
        }
        return bitmap
    }
    fun dp(ctx:Context,n:Int)=(n * ctx.resources.displayMetrics.density+0.5f).toInt()
    fun pill(ctx:Context,key:String,radiusDp:Int)=GradientDrawable().apply {
        shape=GradientDrawable.RECTANGLE
        setColor(color(ctx,key));cornerRadius=dp(ctx,radiusDp).toFloat()
    }
    fun shape(ctx:Context,key:String,radiusKey:String,strokeKey:String?=null)=GradientDrawable().apply{
        shape=GradientDrawable.RECTANGLE
        setColor(color(ctx,key))
        cornerRadius=dp(ctx,radius(ctx,radiusKey)).toFloat()
        if(strokeKey!=null)setStroke(dp(ctx,1).coerceAtLeast(1),color(ctx,strokeKey))
    }
    fun bubble(ctx:Context,sent:Boolean)=GradientDrawable().apply{
        shape=GradientDrawable.RECTANGLE
        setColor(color(ctx,if(sent)"outgoing" else "incoming"))
        val r=dp(ctx,radius(ctx,"bubble_radius")).toFloat()
        val small=dp(ctx,3).toFloat()
        cornerRadii=if(sent)floatArrayOf(r,r,r,r,small,small,r,r)
            else floatArrayOf(r,r,r,r,r,r,small,small)
    }
}
