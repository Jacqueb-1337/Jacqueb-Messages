package org.fossify.messages.theming

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** Synthetic interactive inbox and conversation theme previews. */
class ThemeEditorActivity: AppCompatActivity() {
    private var page=0
    private var previewScale=1f

    /** Measure the natural preview at full width, then scale it into the
     * remaining space. No scrolling, clipping or fake miniature layout values.
     * The native child retains its unscaled hit targets for editing. */
    private inner class FitPreviewFrame:FrameLayout(this@ThemeEditorActivity){
        init{clipChildren=true;clipToPadding=true}
        override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
            val width=View.MeasureSpec.getSize(widthMeasureSpec)
            val height=View.MeasureSpec.getSize(heightMeasureSpec)
            val content=getChildAt(0)
            if(content!=null){
                content.measure(
                    View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width,height)
        }
        override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){
            val content=getChildAt(0)?:return
            val width=right-left
            val height=bottom-top
            val fullHeight=content.measuredHeight.coerceAtLeast(1)
            val scale=1f
            previewScale=scale
            content.layout(0,0,width,fullHeight)
            content.pivotX=0f
            content.pivotY=0f
            content.scaleX=scale
            content.scaleY=scale
            content.translationX=(width-width*scale)/2f
            // Keep the mock inbox aligned to the top of the viewport like the
            // production activity; do not vertically center a tiny card.
            content.translationY=0f
        }
    }
    private fun d(n:Int)=UiTheme.dp(this,n)
    private fun c(k:String)=UiTheme.color(this,k)
    private fun vertical()=LinearLayout(this).apply{orientation=1}
    private fun horizontal()=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER_VERTICAL}
    private fun t(value:String,sp:Float,key:String="title",bold:Boolean=false)=TextView(this).apply{
        text=value;textSize=sp;setTextColor(c(key));gravity=Gravity.CENTER_VERTICAL
        if(bold)setTypeface(typeface,Typeface.BOLD)
    }
    private fun lp(width:Int,height:Int)=LinearLayout.LayoutParams(width,height)
    private fun tap(v:View,key:String,shape:String?=null):View=v.apply{
        isClickable=true
        UiMotion.press(this, .965f)
        setOnClickListener{edit(key,shape)}
        contentDescription="Edit "+(UiTheme.colors[key]?:key)
    }
    private fun pad(v:View,a:Int,b:Int=a)=v.apply{setPadding(d(a),d(b),d(a),d(b))}
    private fun avatar(letter:String,group:Boolean=false):TextView =
        t(letter,20f,"badge_text",true).apply{
            gravity=Gravity.CENTER
            val color=if(group)"group" else "avatar"
            val shape=if(group)"group_radius" else "avatar_radius"
            background=UiTheme.shape(this@ThemeEditorActivity,color,shape)
            tap(this,color,shape)
        }
    private fun badge(label:String):TextView=t(label,12f,"badge_text",true).apply{
        gravity=Gravity.CENTER
        background=UiTheme.shape(this@ThemeEditorActivity,"badge","badge_radius")
        tap(this,"badge","badge_radius")
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,true)
        page=savedInstanceState?.getInt("page")?:0
        render()
    }
    override fun onSaveInstanceState(state:Bundle){
        super.onSaveInstanceState(state);state.putInt("page",page)
    }
    private fun render(){
        window.statusBarColor=c("surface")
        window.navigationBarColor=c("surface")
        val iconController=WindowCompat.getInsetsController(window,window.decorView)
        iconController.isAppearanceLightStatusBars=!UiTheme.isDark(this)
        iconController.isAppearanceLightNavigationBars=!UiTheme.isDark(this)
        val root=vertical().apply{
            setBackgroundColor(c("surface"))
            setPadding(d(15),d(12),d(15),d(10))
        }
        setContentView(root)
        root.post { if(root.isAttachedToWindow) UiMotion.enter(root, 5f) }
        val heading=horizontal()
        val close=t("‹",30f).apply{
            UiMotion.press(this, .91f)
            gravity=Gravity.CENTER;setOnClickListener{finish()}
        }
        heading.addView(close,lp(d(43),d(45)))
        heading.addView(t("Theme studio",24f,"title",true),LinearLayout.LayoutParams(0,d(45),1f))
        heading.addView(t("Reset",15f,"accent",true).apply{
            setOnClickListener{
                AlertDialog.Builder(this@ThemeEditorActivity).setTitle("Reset theme?")
                    .setMessage("Restore default colors and shapes for this appearance?")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Reset"){_,_->UiTheme.resetCurrentMode(this@ThemeEditorActivity);render()}
                    .show().also { UiMotion.dialog(it.window?.decorView) }
            }
        })
        root.addView(heading)
        root.addView(t("Tap a preview element to edit its color and shape.",13f,"preview").apply{
            setPadding(d(5),d(5),d(5),d(13))
        })
        val tabs=horizontal()
        listOf("Inbox","Conversation").forEachIndexed{index,name->
            val button=Button(this).apply{
                text=name;isAllCaps=false;textSize=14f
                background=UiTheme.shape(this@ThemeEditorActivity,
                    if(page==index)"accent" else "card","composer_radius")
                setTextColor(c(if(page==index)"badge_text" else "title"))
                UiMotion.press(this, .95f)
                setOnClickListener{if(page!=index){page=index;render()}}
            }
            tabs.addView(button,LinearLayout.LayoutParams(0,d(48),1f).apply{
                marginStart=d(3);marginEnd=d(3)
            })
        }
        root.addView(tabs)
        val actions=horizontal().apply{setPadding(d(3),d(7),d(3),d(4))}
        val picker=Button(this).apply{
            text=UiTheme.title(this@ThemeEditorActivity)+" ▾"
            isAllCaps=false
            setTextColor(c("title"))
            background=UiTheme.shape(this@ThemeEditorActivity,"card","composer_radius")
            UiMotion.press(this, .95f)
            setOnClickListener{chooseTheme()}
        }
        actions.addView(picker,LinearLayout.LayoutParams(0,d(47),1f))
        val save=Button(this).apply{
            text="Save as"
            isAllCaps=false
            setTextColor(c("title"))
            background=UiTheme.shape(this@ThemeEditorActivity,"card","composer_radius")
            UiMotion.press(this, .95f)
            setOnClickListener{saveTheme()}
        }
        actions.addView(save,lp(d(105),d(47)))
        val metrics=Button(this).apply{
            text="Spacing"
            isAllCaps=false
            setTextColor(c("title"))
            background=UiTheme.shape(this@ThemeEditorActivity,"card","composer_radius")
            UiMotion.press(this, .95f)
            setOnClickListener{spacingEditor()}
        }
        actions.addView(metrics,lp(d(108),d(47)))
        root.addView(actions)
        val preview=FitPreviewFrame()
        val samples=vertical()
        preview.addView(samples,FrameLayout.LayoutParams(-1,-2))
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        if(page==0)inbox(samples)else conversation(samples)
        root.addView(t((if(UiTheme.isDark(this))"Dark" else "Light")+
            " theme • saved automatically",12f,"preview").apply{
                gravity=Gravity.CENTER;setPadding(0,d(8),0,0)
            })
    }
    private fun inbox(parent:LinearLayout){
        // The compose button floats over the actual mock inbox, not a separate
        // 180dp staging area. This makes preview positions match the live layout.
        val fabArea=FrameLayout(this).apply{
            contentDescription="Drag the new-message button anywhere in this preview"
        }
        val screenColumn=vertical()
        fabArea.addView(screenColumn,FrameLayout.LayoutParams(-1,-1))
        parent.addView(fabArea,LinearLayout.LayoutParams(-1,0,1f))

        // The real inbox starts with a search toolbar, not a "Messages" title.
        val search=horizontal().apply{
            background=UiTheme.shape(this@ThemeEditorActivity,"search","composer_radius")
            setPadding(d(17),0,d(12),0)
        }
        search.addView(tap(ImageView(this).apply{
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList=android.content.res.ColorStateList.valueOf(c("title"))
            setPadding(d(10),d(11),d(10),d(11))
        },"title"),lp(d(42),d(52)))
        search.addView(tap(t("Search",18f,"preview"),"preview"),
            LinearLayout.LayoutParams(0,d(52),1f))
        search.addView(tap(t("⋮",25f,"title"),"title"),lp(d(32),d(52)))
        screenColumn.addView(search,lp(-1,d(52)).apply{
            marginStart=d(3);marginEnd=d(3);bottomMargin=d(8)
        })
        val card=vertical().apply{
            background=UiTheme.shape(this@ThemeEditorActivity,"card","card_radius","divider")
            clipToOutline=true
        }
        screenColumn.addView(card,LinearLayout.LayoutParams(-1,0,1f).apply{
            marginStart=d(UiTheme.metric(this@ThemeEditorActivity,"inbox_inset"))
            marginEnd=d(UiTheme.metric(this@ThemeEditorActivity,"inbox_inset"))
            topMargin=d(4)
        })
        rowSample(card,"A","Alex Morgan","See you soon","9:46 AM",false,false)
        divider(card)
        rowSample(card,"T","Taylor","You have unread messages","9:42 AM",true,false)
        divider(card)
        rowSample(card,"✣","Family group","Jordan sent 2 photos","Yesterday",true,true)
        divider(card)
        rowSample(card,"D","Dad","Attachment","Tuesday",false,false,true)
        val foot=horizontal().apply{gravity=Gravity.CENTER;minimumHeight=d(63)}
        foot.addView(tap(pad(t("Conversations",17f,"accent",true),7),"accent"))
        foot.addView(badge("4"),lp(d(24),d(24)))
        foot.addView(tap(pad(t("Contacts",17f,"preview"),20),"preview"))
        screenColumn.addView(foot)
        val fab=t("+",30f,"fab_icon",true).apply{
            gravity=Gravity.CENTER
            background=UiTheme.shape(this@ThemeEditorActivity,"fab","fab_radius")
            contentDescription="Tap to style button; drag to move it"
        }
        val fabSize=d(UiTheme.metric(this,"fab_size"))
        fabArea.addView(fab,FrameLayout.LayoutParams(fabSize,fabSize))
        val fineUp=d(UiTheme.metric(this,"fab_offset"))
        fabArea.post{
            val (x,y)=UiTheme.fabPosition(this,fabArea.width,fabArea.height,fab.width,fab.height)
            fab.x=x
            fab.y=y
        }
        var touchX=0f
        var touchY=0f
        var wasDragged=false
        fab.setOnTouchListener{view,event->
            when(event.actionMasked){
                android.view.MotionEvent.ACTION_DOWN->{
                    touchX=event.rawX
                    touchY=event.rawY
                    wasDragged=false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE->{
                    val dx=event.rawX-touchX
                    val dy=event.rawY-touchY
                    if(kotlin.math.abs(dx)>d(4) || kotlin.math.abs(dy)>d(4))
                        wasDragged=true
                    if(wasDragged){
                        view.x=(view.x+dx/previewScale).coerceIn(0f,
                            (fabArea.width-view.width).coerceAtLeast(0).toFloat())
                        view.y=(view.y+dy/previewScale).coerceIn(0f,
                            (fabArea.height-view.height).coerceAtLeast(0).toFloat())
                        touchX=event.rawX
                        touchY=event.rawY
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP->{
                    if(wasDragged){
                        val availableX=(fabArea.width-view.width).coerceAtLeast(1)
                        val availableY=(fabArea.height-view.height).coerceAtLeast(1)
                        UiTheme.setMetric(this,"fab_x_percent",
                            (view.x/availableX*100f).toInt().coerceIn(0,100))
                        UiTheme.setMetric(this,"fab_y_percent",
                            ((view.y+fineUp)/availableY*100f).toInt().coerceIn(0,100))
                        render()
                    }else edit("fab","fab_radius")
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL->true
                else->false
            }
        }
        parent.addView(tap(pad(t("Drag + to move; tap to style; edit + icon separately",12f,"preview"),7,3),"fab_icon"))
        parent.addView(pad(t("Demo messages only",11f,"preview"),7,3))
    }
    private fun rowSample(parent:LinearLayout,letter:String,name:String,
                          snippet:String,date:String,unread:Boolean,group:Boolean,
                          selected:Boolean=false){
        // Mirror item_conversation.xml and BaseConversationsAdapter geometry.
        val line=horizontal().apply{
            setPadding(d(20),d(UiTheme.metric(this@ThemeEditorActivity,"row_padding")),
                d(24),d(UiTheme.metric(this@ThemeEditorActivity,"row_padding")))
            if(selected)background=UiTheme.shape(this@ThemeEditorActivity,"selected","card_radius")
            UiMotion.press(this, .975f)
            setOnClickListener{edit(if(selected)"selected" else "card","card_radius")}
        }
        parent.addView(line,lp(-1,d(UiTheme.metric(this,"row_height"))))
        val avatarSize=d(UiTheme.metric(this,"avatar_size"))
        line.addView(avatar(letter,group),lp(avatarSize,avatarSize))
        val content=vertical().apply{
            setPadding(d(UiTheme.metric(this@ThemeEditorActivity,"row_text_gap")),0,0,0)
        }
        val first=horizontal()
        first.addView(tap(t(name,16f,"title",unread),"title"),
            LinearLayout.LayoutParams(0,-2,1f))
        first.addView(tap(t(date,13f,"date",unread),"date"))
        content.addView(first)
        val second=horizontal().apply{setPadding(0,d(5),0,0)}
        second.addView(tap(t(snippet,13f,"preview",unread).apply{
            alpha=if(unread)1f else .7f
            maxLines=2
            setLineSpacing(d(2).toFloat(),1f)
            ellipsize=android.text.TextUtils.TruncateAt.END
        },"preview"),LinearLayout.LayoutParams(0,-2,1f))
        if(unread)second.addView(badge(if(group)"5" else "2"),lp(d(22),d(22)))
        content.addView(second)
        line.addView(content,LinearLayout.LayoutParams(0,-2,1f))
    }
    private fun divider(parent:LinearLayout){
        val strip=FrameLayout(this).apply{
            setPadding(d(UiTheme.metric(this@ThemeEditorActivity,"divider_inset")),0,d(20),0)
        }
        val line=View(this).apply{setBackgroundColor(c("divider"))}
        strip.addView(line,FrameLayout.LayoutParams(-1,
            d(UiTheme.metric(this,"divider_thickness")).coerceAtLeast(1),Gravity.CENTER_VERTICAL))
        tap(strip,"divider")
        parent.addView(strip,lp(-1,d(UiTheme.metric(this,"divider_thickness")).coerceAtLeast(1)))
    }
    private fun conversation(parent:LinearLayout){
        // Match the live ThreadActivity hierarchy: surface toolbar, inset
        // rounded card, scrolling message list, and fixed bottom composer.
        // The preview is always life-size, never scaled into a miniature.
        val toolbar=horizontal().apply{setPadding(d(6),0,d(8),0)}
        toolbar.addView(tap(t("‹",27f,"title").apply{gravity=Gravity.CENTER},"title"),
            lp(d(42),d(55)))
        toolbar.addView(tap(t("Alex Morgan",18f,"title",true),"title"),
            LinearLayout.LayoutParams(0,d(55),1f))
        toolbar.addView(tap(t("☎",23f,"accent"),"accent"),lp(d(35),d(55)))
        toolbar.addView(tap(t("⋮",24f,"title"),"title"),lp(d(32),d(55)))
        parent.addView(toolbar)
        val card=vertical().apply{
            background=UiTheme.shape(this@ThemeEditorActivity,"card","card_radius","divider")
            clipToOutline=true
        }
        parent.addView(card,LinearLayout.LayoutParams(-1,0,1f).apply{
            marginStart=d(UiTheme.metric(this@ThemeEditorActivity,"inbox_inset"))
            marginEnd=d(UiTheme.metric(this@ThemeEditorActivity,"inbox_inset"))
            topMargin=d(4)
        })
        val messages=vertical()
        val scroll=ScrollView(this).apply{
            isFillViewport=false
            isVerticalScrollBarEnabled=false
            clipToPadding=false
            addView(messages,FrameLayout.LayoutParams(-1,-2))
        }
        card.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        messages.addView(tap(t("Today • 9:34 AM",12f,"date").apply{
            gravity=Gravity.CENTER
            setPadding(0,d(14),0,d(15))
        },"date"))
        bubble(messages,"Hi! Did you get the photos?",false)
        bubble(messages,"I did! Sending them now.",true)
        bubble(messages,"📷 2 photos attached",true)
        bubble(messages,"Thanks!",false)
        messages.addView(tap(t("9:45 AM    •    Delivered",12f,"date").apply{
            gravity=Gravity.END
            setPadding(d(12),d(10),d(12),d(12))
        },"date"))
        val compose=horizontal().apply{
            setPadding(d(4),d(8),d(4),d(8))
        }
        compose.addView(tap(t("+",29f,"accent").apply{
            gravity=Gravity.CENTER
        },"accent"),lp(d(35),d(44)))
        compose.addView(tap(t("Type a message...",14f,"composer_text").apply{
            setPadding(d(14),0,d(12),0)
            background=UiTheme.shape(this@ThemeEditorActivity,"composer","composer_radius")
        },"composer","composer_radius"),LinearLayout.LayoutParams(0,
            d(UiTheme.metric(this,"composer_height")),1f))
        compose.addView(tap(t("➤",21f,"send_icon",true).apply{
            gravity=Gravity.CENTER
            background=UiTheme.shape(this@ThemeEditorActivity,"send","send_radius")
        },"send","send_radius"),lp(d(51),d(51)).apply{marginStart=d(7)})
        card.addView(compose)
        // Editing labels stay outside the mock screen and never displace its
        // composer; tapping any message edits fill + foreground together.
        parent.addView(tap(pad(t("Tap a bubble to edit background, text and corners",12f,"preview"),4,5),
            "preview"))
        parent.addView(tap(pad(t("Edit send icon color",12f,"preview"),4,3),"send_icon"))
    }
    private fun bubble(parent:LinearLayout,body:String,sent:Boolean){
        val line=horizontal().apply{
            gravity=if(sent)Gravity.END else Gravity.START
            setPadding(d(UiTheme.metric(this@ThemeEditorActivity,"bubble_side_incoming")),
                d(UiTheme.metric(this@ThemeEditorActivity,"bubble_gap")),
                d(UiTheme.metric(this@ThemeEditorActivity,"bubble_side_outgoing")),0)
        }
        if(!sent)line.addView(avatar("A"),lp(d(UiTheme.metric(this,"avatar_size")),d(UiTheme.metric(this,"avatar_size"))).apply{
            marginEnd=d(UiTheme.metric(this@ThemeEditorActivity,"avatar_bubble_gap"))
        })
        val box=FrameLayout(this).apply{
            background=UiTheme.bubble(this@ThemeEditorActivity,sent)
        }
        // The bubble owns the hit target. An individually clickable TextView
        // swallowed taps and offered only its text color.
        box.addView(pad(t(body,14f,if(sent)"outgoing_text" else "incoming_text"),
            UiTheme.metric(this,"bubble_padding"),
            UiTheme.metric(this,"bubble_padding")))
        box.contentDescription=if(sent)"Edit sent bubble and text" else "Edit received bubble and text"
        UiMotion.press(box, .968f)
        box.setOnClickListener{editBubble(sent)}
        val maxBubble=(resources.displayMetrics.widthPixels
            * UiTheme.metric(this,"bubble_width_percent")/100f).toInt()
        if(body.length>24)
            line.addView(box,lp(d(270).coerceAtMost(maxBubble),-2))
        else
            line.addView(box,lp(-2,-2))
        parent.addView(line)
    }
    private fun chooseTheme(){
        val entries=UiTheme.presets.map{it.key to it.value}+UiTheme.named(this)+
            (if(UiTheme.hasDraft(this))listOf("draft" to "Unsaved edits") else emptyList())
        val labels=(entries.map { entry ->
            if(entry.first==UiTheme.active(this)) "✓  "+entry.second else entry.second
        }+listOf("Delete current named theme")).toTypedArray()
        FluidSheets.actions(this,"Choose theme",labels){index ->
            if(index<entries.size){
                UiTheme.switch(this,entries[index].first)
                render()
            }else{
                val id=UiTheme.active(this)
                if(id.startsWith("user_")){
                    AlertDialog.Builder(this).setTitle("Delete "+UiTheme.title(this)+"?")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Delete"){_,_->UiTheme.delete(this,id);render()}
                        .show()
                }else Toast.makeText(this,"Built-in themes cannot be deleted",Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun saveTheme(){
        val input=EditText(this).apply{
            setSingleLine()
            hint="Theme name"
            setPadding(d(20),d(14),d(20),d(14))
        }
        AlertDialog.Builder(this).setTitle("Save theme")
            .setMessage("Save a named copy of the current theme.")
            .setView(input).setNegativeButton("Cancel",null)
            .setPositiveButton("Save"){_,_->
                try{UiTheme.saveAs(this,input.text.toString());render()}
                catch(_:IllegalArgumentException){
                    Toast.makeText(this,"Choose a unique name",Toast.LENGTH_LONG).show()
                }
            }.show().also { UiMotion.dialog(it.window?.decorView) }
    }
    private fun editBubble(sent:Boolean){
        val fill = if(sent)"outgoing" else "incoming"
        val lettering = if(sent)"outgoing_text" else "incoming_text"
        val contents=vertical().apply { setPadding(d(16),d(8),d(16),d(8)) }
        var bubbleColor=c(fill)
        var textColor=c(lettering)
        var editingBubble=true
        val toggles=horizontal()
        val bubbleButton=Button(this).apply {
            text="Bubble background"
            isAllCaps=false
        }
        val textButton=Button(this).apply {
            text="Text"
            isAllCaps=false
        }
        toggles.addView(bubbleButton,LinearLayout.LayoutParams(0,d(48),1f))
        toggles.addView(textButton,LinearLayout.LayoutParams(0,d(48),1f))
        contents.addView(toggles)
        val activeLabel=t("Editing bubble background",14f,"preview")
        contents.addView(activeLabel)
        val picker=ThemeColorPicker(this,bubbleColor)
        contents.addView(picker)
        picker.onColorChanged={value->
            if(editingBubble)bubbleColor=value else textColor=value
        }
        fun chooseBubble(isBackground:Boolean){
            editingBubble=isBackground
            activeLabel.text=if(isBackground)"Editing bubble background" else "Editing message text"
            picker.setColor(if(isBackground)bubbleColor else textColor)
            bubbleButton.alpha=if(isBackground)1f else .55f
            textButton.alpha=if(isBackground).55f else 1f
        }
        UiMotion.press(bubbleButton, .955f)
        UiMotion.press(textButton, .955f)
        bubbleButton.setOnClickListener{
            if(picker.validColorOrNull()==null){picker.showInvalidCode()}
            else chooseBubble(true)
        }
        textButton.setOnClickListener{
            if(picker.validColorOrNull()==null){picker.showInvalidCode()}
            else chooseBubble(false)
        }
        chooseBubble(true)
        val radiusTitle=t("Bubble corner radius: "+UiTheme.radius(this,"bubble_radius")+" dp",14f,"preview")
        contents.addView(radiusTitle)
        val radius=SeekBar(this).apply{
            max=100
            progress=UiTheme.radius(this@ThemeEditorActivity,"bubble_radius")
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){
                    radiusTitle.text="Bubble corner radius: $value dp"
                }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        contents.addView(radius)
        FluidSheets.editor(this,
            if(sent)"Sent message bubble" else "Received message bubble",contents){
            val current=picker.validColorOrNull()
            if(current==null){
                picker.showInvalidCode()
                false
            }else{
                if(editingBubble)bubbleColor=current else textColor=current
                UiTheme.setColor(this,fill,bubbleColor)
                UiTheme.setColor(this,lettering,textColor)
                UiTheme.setRadius(this,"bubble_radius",radius.progress)
                render()
                true
            }
        }
    }

    private fun spacingEditor(){
        val keys=UiTheme.spacing.keys.toList()
        val labels=keys.map{
            (UiTheme.spacing[it] ?: it)+": "+UiTheme.metric(this,it)+
                (if(it.endsWith("_percent"))"%" else " dp")
        }.toTypedArray()
        FluidSheets.actions(this,"Layout & spacing",labels){index->
                val key=keys[index]
                val holder=vertical().apply{setPadding(d(20),d(12),d(20),d(10))}
                val unit=if(key.endsWith("_percent"))"%" else "dp"
                val value=t((UiTheme.spacing[key]?:key)+": "+UiTheme.metric(this,key)+" "+unit,16f)
                holder.addView(value)
                val seek=SeekBar(this).apply{
                    max=when(key){
                        "divider_thickness"->6
                        "row_height"->140
                        "fab_offset"->140
                        else->100
                    }
                    min=when(key){
                        "row_height"->56;"avatar_size"->24
                        "bubble_width_percent"->45;"fab_size"->40
                        "composer_height"->40;"divider_thickness"->1
                        else->0
                    }
                    progress=UiTheme.metric(this@ThemeEditorActivity,key)
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                        override fun onProgressChanged(bar:SeekBar?,amount:Int,fromUser:Boolean){
                            value.text=(UiTheme.spacing[key]?:key)+": "+amount+" "+unit
                        }
                        override fun onStartTrackingTouch(bar:SeekBar?){}
                        override fun onStopTrackingTouch(bar:SeekBar?){}
                    })
                }
                holder.addView(seek)
                FluidSheets.editor(this,"Edit spacing",holder){
                    UiTheme.setMetric(this,key,seek.progress)
                    render()
                    true
                }
            }
    }

    private fun edit(key:String,shape:String?=null){
        val contents=vertical().apply{setPadding(d(16),d(6),d(16),d(6))}
        val picker=ThemeColorPicker(this,c(key))
        contents.addView(picker)
        var corner:SeekBar?=null
        if(shape!=null){
            val shapeLabel=t("Corner radius: "+UiTheme.radius(this,shape)+" dp",14f,"preview")
            contents.addView(shapeLabel)
            corner=SeekBar(this).apply{
                max=100
                progress=UiTheme.radius(this@ThemeEditorActivity,shape)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(v:SeekBar?,n:Int,user:Boolean){
                        shapeLabel.text="Corner radius: $n dp"
                    }
                    override fun onStartTrackingTouch(v:SeekBar?){}
                    override fun onStopTrackingTouch(v:SeekBar?){}
                })
            }
            contents.addView(corner)
        }
        FluidSheets.editor(this,UiTheme.colors[key]?:key,contents){
            val chosen=picker.validColorOrNull()
            if(chosen==null){
                picker.showInvalidCode()
                false
            }else{
                UiTheme.setColor(this,key,chosen)
                if(shape!=null)UiTheme.setRadius(this,shape,corner?.progress?:16)
                render()
                true
            }
        }
    }
}
