package org.fossify.messages.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.activities.ManageBlockedNumbersActivity
import org.fossify.commons.dialogs.ChangeDateTimeFormatDialog
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.dialogs.SecurityDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.getFontSizeText
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.FONT_SIZE_EXTRA_LARGE
import org.fossify.commons.helpers.FONT_SIZE_LARGE
import org.fossify.commons.helpers.FONT_SIZE_MEDIUM
import org.fossify.commons.helpers.FONT_SIZE_SMALL
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivitySettingsBinding
import org.fossify.messages.dialogs.ExportMessagesDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.emptyMessagesRecycleBin
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.FILE_SIZE_100_KB
import org.fossify.messages.helpers.FILE_SIZE_1_MB
import org.fossify.messages.helpers.FILE_SIZE_200_KB
import org.fossify.messages.helpers.FILE_SIZE_2_MB
import org.fossify.messages.helpers.FILE_SIZE_300_KB
import org.fossify.messages.helpers.FILE_SIZE_600_KB
import org.fossify.messages.helpers.FILE_SIZE_NONE
import org.fossify.messages.helpers.LOCK_SCREEN_NOTHING
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER
import org.fossify.messages.helpers.LOCK_SCREEN_SENDER_MESSAGE
import org.fossify.messages.helpers.MessagesImporter
import org.fossify.messages.helpers.refreshConversations
import java.util.Locale
import kotlin.system.exitProcess
import org.fossify.messages.updates.UpdateRepository
import org.fossify.messages.updates.UpdateActivity
import org.fossify.messages.BuildConfig
import org.fossify.messages.theming.UiTheme
import org.fossify.messages.search.SemanticSearchPrefs
import org.fossify.messages.search.SemanticEngine
import org.fossify.messages.search.SemanticModelManager
import org.fossify.messages.search.SemanticIndex
import org.fossify.messages.search.SemanticIndexWorker
import androidx.work.WorkManager
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AlertDialog

class SettingsActivity : SimpleActivity() {
    private var semanticSettingsExpanded = false
    private var semanticAdvancedExpanded = false
    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private val messagesFileType = "application/json"
    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        if (!isQPlus()) {
            add("application/octet-stream")
        }
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                MessagesImporter(this).importMessages(uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(messagesFileType)) { uri ->
            if (uri != null) {
                toast(org.fossify.commons.R.string.exporting)
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        semanticAdvancedExpanded=savedInstanceState?.getBoolean("semanticAdvancedExpanded") ?: false
        semanticSettingsExpanded=savedInstanceState?.getBoolean("semanticExpanded")
            ?: intent.getBooleanExtra("open_semantic_settings",false)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.settingsNestedScrollview))
        setupMaterialScrollListener(
            scrollingView = binding.settingsNestedScrollview,
            topAppBar = binding.settingsAppbar
        )
    }

    override fun onSaveInstanceState(outState:Bundle){
        outState.putBoolean("semanticExpanded",semanticSettingsExpanded)
        outState.putBoolean("semanticAdvancedExpanded",semanticAdvancedExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)

        setupCustomizeColors()
        setupCustomizeNotifications()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageBlockedKeywords()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupShowCharacterCounter()
        setupUseSimpleCharacters()
        setupSendOnEnter()
        setupEnableDeliveryReports()
        setupSendLongMessageAsMMS()
        setupGroupMessageAsMMS()
        setupKeepConversationsArchived()
        setupLockScreenVisibility()
        setupMMSFileSizeLimit()
        setupUseRecycleBin()
        setupEmptyRecycleBin()
        setupAppPasswordProtection()
        setupMessagesExport()
        setupMessagesImport()
        setupUpdateSettings()
        setupSemanticSearchSettings()
        updateTextColors(binding.settingsNestedScrollview)

        if (
            blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()
        ) {
            refreshConversations()
        }

        arrayOf(
            binding.settingsColorCustomizationSectionLabel,
            binding.settingsGeneralSettingsLabel,
            binding.settingsOutgoingMessagesLabel,
            binding.settingsNotificationsLabel,
            binding.settingsArchivedMessagesLabel,
            binding.settingsRecycleBinLabel,
            binding.settingsSecurityLabel,
            binding.settingsMigratingLabel
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupSemanticSearchSettings() {
        val holder=binding.settingsNestedScrollview.getChildAt(0) as? LinearLayout ?: return
        holder.findViewWithTag<android.view.View>("messages_semantic_section")?.let{holder.removeView(it)}
        fun dp(v:Int)=(v*resources.displayMetrics.density+0.5f).toInt()
        val dark=(resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK)==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val primary=UiTheme.color(this,"title")
        val accent=UiTheme.color(this,"accent")
        val muted=if(dark)0xFFAAAAAF.toInt() else 0xFF62646E.toInt()
        val surface=if(dark)0xFF28282B.toInt() else 0xFFF2F2F5.toInt()
        val selectedSurface=if(dark)0xFF34343C.toInt() else 0xFFE8EAF0.toInt()
        fun text(value:String,size:Float=14f,color:Int=primary,bold:Boolean=false)=TextView(this).apply{
            text=value;textSize=size;setTextColor(color)
            if(bold)setTypeface(null,android.graphics.Typeface.BOLD)
        }
        fun round(color:Int,border:Int?=null,radius:Int=16)=
            android.graphics.drawable.GradientDrawable().apply{
                cornerRadius=dp(radius).toFloat();setColor(color)
                if(border!=null)setStroke(dp(1),border)
            }
        fun surface(view:android.view.View,color:Int,border:Int?=null) {
            view.background=android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                    if(dark)0x36FFFFFF else 0x22000000),round(color,border),null)
            view.isClickable=true
            view.isFocusable=true
        }
        fun column()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun gap(parent:LinearLayout,h:Int) {
            parent.addView(android.view.View(this),LinearLayout.LayoutParams(1,dp(h)))
        }
        fun button(label:String,callback:()->Unit)=text(label,15f,android.graphics.Color.WHITE,true).apply{
            gravity=android.view.Gravity.CENTER
            minHeight=dp(52)
            setPadding(dp(13),dp(11),dp(13),dp(11))
            surface(this,accent)
            setOnClickListener{callback()}
        }

        val chosen=SemanticSearchPrefs.selected(this)
        val installed=SemanticModelManager.installed(this,chosen)
        val opted=SemanticSearchPrefs.optedIn(this)
        val indexed=if(installed && opted)SemanticIndex.count(this,chosen) else 0
        val status=when{
            !installed -> "Not ready • engine download required"
            !opted -> "Engine downloaded • turned off"
            indexed>0 -> "Ready • "+indexed+" messages indexed"
            else -> "Installed • indexing messages"
        }
        val section=column().apply{
            tag="messages_semantic_section"
            setPadding(dp(16),dp(18),dp(16),dp(18))
        }
        val summary=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18),dp(17),dp(16),dp(17))
        }
        val sumText=column()
        sumText.addView(text("Semantic search",18f,primary,true))
        gap(sumText,3)
        sumText.addView(text(status,13f,muted))
        summary.addView(sumText,LinearLayout.LayoutParams(0,-2,1f))
        summary.addView(text(if(semanticSettingsExpanded)"⌃" else "⌄",24f,muted).apply{
            gravity=android.view.Gravity.CENTER
        },LinearLayout.LayoutParams(dp(30),dp(34)))
        surface(summary,surface)
        summary.contentDescription="Semantic search: "+status+
            if(semanticSettingsExpanded)". Collapse options" else ". Expand options"
        summary.setOnClickListener{
            semanticSettingsExpanded=!semanticSettingsExpanded
            setupSemanticSearchSettings()
        }
        section.addView(summary,LinearLayout.LayoutParams(-1,-2))
        if(!semanticSettingsExpanded){holder.addView(section,0);return}

        gap(section,18)
        section.addView(text("Choose a search engine",16f,primary,true))
        gap(section,5)
        section.addView(text("Select one to download. Neither model is bundled in Messages.",13f,muted))
        gap(section,12)
        for(engine in SemanticEngine.entries) {
            val chosenEngine=engine==chosen
            val downloaded=SemanticModelManager.installed(this,engine)
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL
                gravity=android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(15),dp(13),dp(12),dp(13))
                minimumHeight=dp(86)
            }
            val details=column()
            details.addView(text(engine.label,17f,primary,true))
            gap(details,3)
            details.addView(text(
                if(engine==SemanticEngine.FAST)
                    "Quicker • ideal for older phones"
                else "Higher relevance • more processing",13f,muted))
            gap(details,5)
            details.addView(text("About "+engine.sizeMb+" MB"+
                if(downloaded)"  •  Downloaded" else "  •  Not downloaded",
                12f,if(chosenEngine)accent else muted))
            row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(text(if(chosenEngine)"✓" else "○",24f,
                if(chosenEngine)accent else muted).apply{gravity=android.view.Gravity.CENTER},
                LinearLayout.LayoutParams(dp(37),dp(40)))
            surface(row,if(chosenEngine)selectedSurface else surface,
                if(chosenEngine)accent else null)
            row.contentDescription=engine.label+", "+engine.sizeMb+" megabytes"+
                if(chosenEngine)", selected" else ", tap to select"
            row.setOnClickListener {
                if(engine!=SemanticSearchPrefs.selected(this)) {
                    SemanticSearchPrefs.select(this,engine)
                    setupSemanticSearchSettings()
                }
            }
            section.addView(row,LinearLayout.LayoutParams(-1,-2))
            gap(section,9)
        }
        gap(section,6)
        section.addView(text(when {
            installed && opted && indexed>0 -> "Search is ready. Regular keyword matches still appear first."
            installed && opted -> "The model is installed. Your private search index is being prepared."
            installed -> "The model is downloaded but semantic search is turned off."
            else -> "The engine runs on your phone only after you choose to download it."
        },13f,muted))
        gap(section,14)

        fun downloadChosen() {
            AlertDialog.Builder(this)
                .setTitle("Download & enable "+chosen.label+"?")
                .setMessage("Download about "+chosen.sizeMb+" MB from Hugging Face. "+
                    "Searches and messages stay on this device. Regular search works without it.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Download & enable"){_,_->
                    SemanticSearchPrefs.optIn(this)
                    val cancelled=AtomicBoolean(false)
                    val indicator=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
                    val caption=text("Preparing download...",13f)
                    val panel=column().apply{
                        setPadding(dp(22),dp(14),dp(22),dp(14))
                        addView(caption);gap(this,12);addView(indicator)
                    }
                    val dialog=AlertDialog.Builder(this)
                        .setTitle("Downloading "+chosen.label+" engine")
                        .setView(panel)
                        .setNegativeButton("Cancel download"){_,_->cancelled.set(true)}
                        .create()
                    dialog.setCanceledOnTouchOutside(false)
                    dialog.show()
                    Thread({
                        val problem=runCatching{
                            SemanticModelManager.install(applicationContext,chosen,cancelled){pct,message->
                                runOnUiThread {
                                    if(!isFinishing&&!isDestroyed){
                                        indicator.progress=pct;caption.text=message
                                    }
                                }
                            }
                        }.exceptionOrNull()
                        if(problem==null)SemanticIndexWorker.schedule(applicationContext)
                        runOnUiThread{
                            dialog.dismiss()
                            if(!isFinishing&&!isDestroyed){
                                if(problem!=null)AlertDialog.Builder(this)
                                    .setTitle("Engine download incomplete")
                                    .setMessage(problem.message ?: "Download failed. Please retry.")
                                    .setPositiveButton("OK",null).show()
                                setupSemanticSearchSettings()
                            }
                        }
                    },"SemanticEngineDownload").start()
                }.show()
        }
        when {
            !installed -> section.addView(
                button("Download & enable "+chosen.label+"  •  "+chosen.sizeMb+" MB"){downloadChosen()},
                LinearLayout.LayoutParams(-1,-2))
            !opted -> section.addView(
                button("Enable "+chosen.label+" search"){
                    SemanticSearchPrefs.optIn(this)
                    SemanticIndexWorker.schedule(applicationContext)
                    setupSemanticSearchSettings()
                },LinearLayout.LayoutParams(-1,-2))
            else -> section.addView(
                button("Search messages"){
                    startActivity(Intent(this,MainActivity::class.java).apply{
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                },LinearLayout.LayoutParams(-1,-2))
        }
        gap(section,10)
        section.addView(text("Private and on-device. No other AI features are enabled.",
            12f,muted).apply{gravity=android.view.Gravity.CENTER})
        gap(section,18)
        val management=text("Manage engines & search data    "+
            if(semanticAdvancedExpanded)"⌃" else "⌄",14f,accent,true).apply{
            minHeight=dp(47)
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(8),dp(8),dp(8))
            isClickable=true
            isFocusable=true
            setOnClickListener {
                semanticAdvancedExpanded=!semanticAdvancedExpanded
                setupSemanticSearchSettings()
            }
        }
        section.addView(management)
        if(semanticAdvancedExpanded) {
            val actions=column().apply{
                setPadding(dp(12),dp(9),dp(12),dp(9))
                background=round(surface)
            }
            fun managementRow(label:String,subtitle:String,callback:()->Unit) {
                val row=column().apply{
                    setPadding(dp(8),dp(12),dp(8),dp(12))
                    isClickable=true;isFocusable=true
                    addView(text(label+"   ›",14f,accent,true))
                    gap(this,3)
                    addView(text(subtitle,12f,muted))
                    setOnClickListener{callback()}
                }
                actions.addView(row,LinearLayout.LayoutParams(-1,-2))
            }
            if(opted)managementRow("Turn off semantic search",
                "Delete search vectors, keep downloaded models"){
                SemanticSearchPrefs.disable(this)
                setupSemanticSearchSettings()
            }
            if(installed && opted)managementRow("Rebuild search index",
                "Refresh local message indexing in background"){
                SemanticIndex.clear(this)
                SemanticIndexWorker.schedule(applicationContext)
                setupSemanticSearchSettings()
            }
            for(engine in SemanticEngine.entries) {
                if(SemanticModelManager.installed(this,engine))
                    managementRow("Remove "+engine.label+" model",
                        "Delete the downloaded "+engine.sizeMb+" MB engine"){
                        AlertDialog.Builder(this).setTitle("Remove "+engine.label+" engine?")
                            .setMessage("This deletes the model, not your messages.")
                            .setNegativeButton("Cancel",null)
                            .setPositiveButton("Remove"){_,_->
                                SemanticModelManager.delete(this,engine)
                                setupSemanticSearchSettings()
                            }.show()
                    }
            }
            if(!opted && SemanticSearchPrefs.shouldOffer(this))
                managementRow("Stop setup reminders","Don't ask during searches"){
                    SemanticSearchPrefs.dismiss(this)
                    setupSemanticSearchSettings()
                }
            if(!opted && SemanticSearchPrefs.dismissed(this))
                managementRow("Restore setup reminders","Ask about semantic search again"){
                    SemanticSearchPrefs.reset(this)
                    setupSemanticSearchSettings()
                }
            section.addView(actions,LinearLayout.LayoutParams(-1,-2))
        }
        holder.addView(section,0)
    }

    private fun setupUpdateSettings() {
        val holder=binding.settingsNestedScrollview.getChildAt(0) as? LinearLayout ?: return
        val previous=holder.findViewWithTag<android.view.View>("jacqueb_updates_section")
        if(previous!=null)holder.removeView(previous)
        val px=(20*resources.displayMetrics.density).toInt()
        val section=LinearLayout(this).apply {
            tag="jacqueb_updates_section"
            orientation=LinearLayout.VERTICAL
            setPadding(px,px,px,px)
        }
        fun label(text:String, size:Float=15f, isHeading:Boolean=false): TextView = TextView(this).apply {
            this.text=text
            textSize=size
            setTextColor(UiTheme.color(this@SettingsActivity,if(isHeading)"accent" else "title"))
            if(isHeading)setTypeface(null,android.graphics.Typeface.BOLD)
            setPadding(0,px/3,0,px/3)
        }
        section.addView(label("ABOUT & UPDATES",13f,true))
        section.addView(label("Messages  ${BuildConfig.VERSION_NAME}",17f,true))
        section.addView(label("Package: $packageName",12f))
        val automatic=SwitchCompat(this).apply {
            text="Automatically check for updates"
            isChecked=UpdateRepository.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _,checked ->
                UpdateRepository.setEnabled(this@SettingsActivity,checked)
                if(checked && android.os.Build.VERSION.SDK_INT>=33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(this@SettingsActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    androidx.core.app.ActivityCompat.requestPermissions(this@SettingsActivity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),4113)
            }
            setPadding(0,px/2,0,px/2)
        }
        section.addView(automatic)
        section.addView(label("Checks roughly every 6 hours, including in the background (Android may delay them).",12f))
        section.addView(label(UpdateRepository.lastCheckText(this),12f))
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            section.addView(label("Update notifications are disabled. Tap to allow alerts.",13f,true).apply {
                setOnClickListener {
                    androidx.core.app.ActivityCompat.requestPermissions(this@SettingsActivity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),4113)
                }
            })
        }
        val channel=label("Update channel: ${UpdateRepository.channel(this).replaceFirstChar { it.uppercase() }}")
        channel.setOnClickListener {
            val options=arrayOf("Stable", "Beta (includes stable)", "Alpha (all builds)")
            val ids=listOf("stable","beta","alpha")
            AlertDialog.Builder(this).setTitle("Update channel")
                .setSingleChoiceItems(options,ids.indexOf(UpdateRepository.channel(this))) { dialog,selected ->
                    UpdateRepository.setChannel(this,ids[selected])
                    channel.text="Update channel: ${ids[selected].replaceFirstChar { it.uppercase() }}"
                    dialog.dismiss()
                }.setNegativeButton("Cancel",null).show()
        }
        section.addView(channel)
        section.addView(label("Check for updates",17f,true).apply {
            contentDescription="Check for app updates and view release notes"
            setOnClickListener { startActivity(Intent(this@SettingsActivity,UpdateActivity::class.java)) }
        })
        holder.addView(section)
    }

    private fun setupMessagesExport() {
        binding.settingsExportMessagesHolder.setOnClickListener {
            exportMessagesDialog = ExportMessagesDialog(this) { fileName ->
                saveDocument.launch("$fileName.json")
            }
        }
    }

    private fun setupMessagesImport() {
        binding.settingsImportMessagesHolder.setOnClickListener {
            getContent.launch(messageImportFileTypes.toTypedArray())
        }
    }

    override fun onPause() {
        super.onPause()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    private fun setupCustomizeColors() = binding.apply {
        settingsColorCustomizationHolder.setOnClickListener {
            // Our independently branded fork has its own package ID. The
            // upstream Fossify helper mislabels all non-Fossify IDs as "fake"
            // once its counter reaches 100. Open the actual customization
            // screen directly; do not run upstream's branding gate.
            startActivity(android.content.Intent(
                this@SettingsActivity,
                org.fossify.commons.activities.CustomizationActivity::class.java
            ))
        }
    }

    private fun setupCustomizeNotifications() = binding.apply {
        settingsCustomizeNotificationsHolder.setOnClickListener {
            launchCustomizeNotificationsIntent()
        }
    }

    private fun setupUseEnglish() = binding.apply {
        settingsUseEnglishHolder.beVisibleIf(
            (config.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )
        settingsUseEnglish.isChecked = config.useEnglish
        settingsUseEnglishHolder.setOnClickListener {
            settingsUseEnglish.toggle()
            config.useEnglish = settingsUseEnglish.isChecked
            exitProcess(0)
        }
    }

    private fun setupLanguage() = binding.apply {
        settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            settingsLanguageHolder.beVisible()
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            settingsLanguageHolder.beGone()
        }
    }

    private fun setupManageBlockedNumbers() = binding.apply {
        settingsManageBlockedNumbers.text =
            getString(org.fossify.commons.R.string.manage_blocked_numbers)
        settingsManageBlockedNumbersHolder.beVisible()
        settingsManageBlockedNumbersHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupManageBlockedKeywords() = binding.apply {
        settingsManageBlockedKeywords.text =
            getString(R.string.manage_blocked_keywords)

        settingsManageBlockedKeywordsHolder.setOnClickListener {
            Intent(this@SettingsActivity, ManageBlockedKeywordsActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() = binding.apply {
        settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this@SettingsActivity) {
                refreshConversations()
            }
        }
    }

    private fun setupFontSize() = binding.apply {
        settingsFontSize.text = getFontSizeText()
        settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(org.fossify.commons.R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(org.fossify.commons.R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(org.fossify.commons.R.string.large)),
                RadioItem(
                    FONT_SIZE_EXTRA_LARGE,
                    getString(org.fossify.commons.R.string.extra_large)
                )
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupShowCharacterCounter() = binding.apply {
        settingsShowCharacterCounter.isChecked = config.showCharacterCounter
        settingsShowCharacterCounterHolder.setOnClickListener {
            settingsShowCharacterCounter.toggle()
            config.showCharacterCounter = settingsShowCharacterCounter.isChecked
        }
    }

    private fun setupUseSimpleCharacters() = binding.apply {
        settingsUseSimpleCharacters.isChecked = config.useSimpleCharacters
        settingsUseSimpleCharactersHolder.setOnClickListener {
            settingsUseSimpleCharacters.toggle()
            config.useSimpleCharacters = settingsUseSimpleCharacters.isChecked
        }
    }

    private fun setupSendOnEnter() = binding.apply {
        settingsSendOnEnter.isChecked = config.sendOnEnter
        settingsSendOnEnterHolder.setOnClickListener {
            settingsSendOnEnter.toggle()
            config.sendOnEnter = settingsSendOnEnter.isChecked
        }
    }

    private fun setupEnableDeliveryReports() = binding.apply {
        settingsEnableDeliveryReports.isChecked = config.enableDeliveryReports
        settingsEnableDeliveryReportsHolder.setOnClickListener {
            settingsEnableDeliveryReports.toggle()
            config.enableDeliveryReports = settingsEnableDeliveryReports.isChecked
        }
    }

    private fun setupSendLongMessageAsMMS() = binding.apply {
        settingsSendLongMessageMms.isChecked = config.sendLongMessageMMS
        settingsSendLongMessageMmsHolder.setOnClickListener {
            settingsSendLongMessageMms.toggle()
            config.sendLongMessageMMS = settingsSendLongMessageMms.isChecked
        }
    }

    private fun setupGroupMessageAsMMS() = binding.apply {
        settingsSendGroupMessageMms.isChecked = config.sendGroupMessageMMS
        settingsSendGroupMessageMmsHolder.setOnClickListener {
            settingsSendGroupMessageMms.toggle()
            config.sendGroupMessageMMS = settingsSendGroupMessageMms.isChecked
        }
    }

    private fun setupKeepConversationsArchived() = binding.apply {
        settingsKeepConversationsArchived.isChecked = config.keepConversationsArchived
        settingsKeepConversationsArchivedHolder.setOnClickListener {
            settingsKeepConversationsArchived.toggle()
            config.keepConversationsArchived = settingsKeepConversationsArchived.isChecked
        }
    }

    private fun setupLockScreenVisibility() = binding.apply {
        settingsLockScreenVisibility.text = getLockScreenVisibilityText()
        settingsLockScreenVisibilityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
                RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
                RadioItem(LOCK_SCREEN_NOTHING, getString(org.fossify.commons.R.string.nothing)),
            )

            RadioGroupDialog(this@SettingsActivity, items, config.lockScreenVisibilitySetting) {
                config.lockScreenVisibilitySetting = it as Int
                settingsLockScreenVisibility.text = getLockScreenVisibilityText()
            }
        }
    }

    private fun getLockScreenVisibilityText() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> org.fossify.commons.R.string.nothing
        }
    )

    private fun setupMMSFileSizeLimit() = binding.apply {
        settingsMmsFileSizeLimit.text = getMMSFileLimitText()
        settingsMmsFileSizeLimitHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(7, getString(R.string.mms_file_size_limit_none), FILE_SIZE_NONE),
                RadioItem(6, getString(R.string.mms_file_size_limit_2mb), FILE_SIZE_2_MB),
                RadioItem(5, getString(R.string.mms_file_size_limit_1mb), FILE_SIZE_1_MB),
                RadioItem(4, getString(R.string.mms_file_size_limit_600kb), FILE_SIZE_600_KB),
                RadioItem(3, getString(R.string.mms_file_size_limit_300kb), FILE_SIZE_300_KB),
                RadioItem(2, getString(R.string.mms_file_size_limit_200kb), FILE_SIZE_200_KB),
                RadioItem(1, getString(R.string.mms_file_size_limit_100kb), FILE_SIZE_100_KB),
            )

            val checkedItemId = items.find { it.value == config.mmsFileSizeLimit }?.id ?: 7
            RadioGroupDialog(this@SettingsActivity, items, checkedItemId) {
                config.mmsFileSizeLimit = it as Long
                settingsMmsFileSizeLimit.text = getMMSFileLimitText()
            }
        }
    }

    private fun setupUseRecycleBin() = binding.apply {
        updateRecycleBinButtons()
        settingsUseRecycleBin.isChecked = config.useRecycleBin
        settingsUseRecycleBin.text = getString(R.string.move_deleted_messages_to_trash)
        settingsUseRecycleBinHolder.setOnClickListener {
            settingsUseRecycleBin.toggle()
            config.useRecycleBin = settingsUseRecycleBin.isChecked
            updateRecycleBinButtons()
        }
    }

    private fun updateRecycleBinButtons() = binding.apply {
        settingsEmptyRecycleBinHolder.beVisibleIf(config.useRecycleBin)
    }

    private fun setupEmptyRecycleBin() = binding.apply {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread {
                settingsEmptyRecycleBinSize.text =
                    resources.getQuantityString(
                        R.plurals.delete_messages,
                        recycleBinMessages,
                        recycleBinMessages
                    )
            }
        }

        settingsEmptyRecycleBinHolder.setOnClickListener {
            if (recycleBinMessages == 0) {
                toast(org.fossify.commons.R.string.recycle_bin_empty)
            } else {
                ConfirmationDialog(
                    activity = this@SettingsActivity,
                    message = "",
                    messageId = R.string.empty_recycle_bin_messages_confirmation,
                    positive = org.fossify.commons.R.string.yes,
                    negative = org.fossify.commons.R.string.no
                ) {
                    ensureBackgroundThread {
                        emptyMessagesRecycleBin()
                    }
                    recycleBinMessages = 0
                    settingsEmptyRecycleBinSize.text =
                        resources.getQuantityString(
                            R.plurals.delete_messages,
                            recycleBinMessages,
                            recycleBinMessages
                        )
                }
            }
        }
    }

    private fun setupAppPasswordProtection() = binding.apply {
        settingsAppPasswordProtection.isChecked = config.isAppPasswordProtectionOn
        settingsAppPasswordProtectionHolder.setOnClickListener {
            val tabToShow = if (config.isAppPasswordProtectionOn) {
                config.appProtectionType
            } else {
                SHOW_ALL_TABS
            }

            SecurityDialog(
                activity = this@SettingsActivity,
                requiredHash = config.appPasswordHash,
                showTabIndex = tabToShow
            ) { hash, type, success ->
                if (success) {
                    val hasPasswordProtection = config.isAppPasswordProtectionOn
                    settingsAppPasswordProtection.isChecked = !hasPasswordProtection
                    config.isAppPasswordProtectionOn = !hasPasswordProtection
                    config.appPasswordHash = if (hasPasswordProtection) "" else hash
                    config.appProtectionType = type

                    if (config.isAppPasswordProtectionOn) {
                        val confirmationTextId =
                            if (config.appProtectionType == PROTECTION_FINGERPRINT) {
                                org.fossify.commons.R.string.fingerprint_setup_successfully
                            } else {
                                org.fossify.commons.R.string.protection_setup_successfully
                            }

                        ConfirmationDialog(
                            activity = this@SettingsActivity,
                            message = "",
                            messageId = confirmationTextId,
                            positive = org.fossify.commons.R.string.ok,
                            negative = 0
                        ) { }
                    }
                }
            }
        }
    }

    private fun getMMSFileLimitText() = getString(
        when (config.mmsFileSizeLimit) {
            FILE_SIZE_100_KB -> R.string.mms_file_size_limit_100kb
            FILE_SIZE_200_KB -> R.string.mms_file_size_limit_200kb
            FILE_SIZE_300_KB -> R.string.mms_file_size_limit_300kb
            FILE_SIZE_600_KB -> R.string.mms_file_size_limit_600kb
            FILE_SIZE_1_MB -> R.string.mms_file_size_limit_1mb
            FILE_SIZE_2_MB -> R.string.mms_file_size_limit_2mb
            else -> R.string.mms_file_size_limit_none
        }
    )
}
