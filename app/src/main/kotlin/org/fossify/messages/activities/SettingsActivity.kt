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
        holder.findViewWithTag<android.view.View>("messages_semantic_section")?.let {holder.removeView(it)}
        val px=(20*resources.displayMetrics.density).toInt()
        val section=LinearLayout(this).apply {
            tag="messages_semantic_section"
            orientation=LinearLayout.VERTICAL
            setPadding(px,px,px,px)
        }
        fun item(t:String,size:Float=15f):TextView=TextView(this).apply {
            text=t; textSize=size
            setTextColor(UiTheme.color(this@SettingsActivity,"title"))
            setPadding(0,px/3,0,px/3)
        }
        section.addView(item("SEMANTIC SEARCH",13f).apply{
            setTextColor(UiTheme.color(this@SettingsActivity,"accent"))
        })
        val summary=if(SemanticSearchPrefs.optedIn(this)) {
            if(SemanticSearchPrefs.engineInstalled(this))
                "Enabled • "+SemanticSearchPrefs.selected(this).label
            else "Enabled • download engine"
        } else "Off • optional"
        section.addView(item("Semantic search: "+summary+"   "+if(semanticSettingsExpanded)"▴" else "▾",16f).apply{
            setOnClickListener {
                semanticSettingsExpanded=!semanticSettingsExpanded
                setupSemanticSearchSettings()
            }
        })
        if(!semanticSettingsExpanded){
            holder.addView(section,0)
            return
        }
        val selected=SemanticSearchPrefs.selected(this)
        val installed=SemanticModelManager.installed(this,selected)
        section.addView(item("Search Engine: "+selected.label,17f).apply{
            setOnClickListener {
                val options=SemanticEngine.entries.map{e->
                    e.label+" (about "+e.sizeMb+" MB)\n"+e.description+
                        if(SemanticModelManager.installed(this@SettingsActivity,e))"  |  Installed" else ""
                }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity).setCustomTitle(item("Choose Semantic Search Engine",20f).apply{setPadding(px,px,px,px)})
                    .setSingleChoiceItems(options,SemanticEngine.entries.indexOf(selected)){dialog,which->
                        SemanticSearchPrefs.select(this@SettingsActivity,SemanticEngine.entries[which])
                        dialog.dismiss()
                        setupSemanticSearchSettings()
                    }.setNegativeButton("Cancel",null).show()
            }
        })
        section.addView(item("Both engines run entirely on-device. Fast uses MiniLM L3; Accurate uses MiniLM L6. The larger engine may return more relevant matches but takes more processing. No AI is used for other features.",13f))
        val consent=SwitchCompat(this).apply {
            text="Enable semantic search (optional)"
            isChecked=SemanticSearchPrefs.optedIn(this@SettingsActivity)
            setOnCheckedChangeListener{_,checked->
                if(checked)SemanticSearchPrefs.optIn(this@SettingsActivity)
                else SemanticSearchPrefs.disable(this@SettingsActivity)
                setupSemanticSearchSettings()
            }
        }
        section.addView(consent)
        section.addView(item(
            if(installed)"Model installed | "+SemanticIndex.count(this,selected)+" indexed messages"
            else "Engine download required (about "+selected.sizeMb+" MB plus tokenizer)",13f))
        section.addView(item(if(installed)"Remove downloaded "+selected.label+" engine"
            else "Download "+selected.label+" Semantic Search Engine",16f).apply {
            setOnClickListener {
                if(installed)AlertDialog.Builder(this@SettingsActivity)
                    .setCustomTitle(item("Remove downloaded engine?",20f).apply{setPadding(px,px,px,px)})
                    .setMessage("Remove the model and its local index? Regular search will keep working.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove"){_,_->
                        SemanticModelManager.delete(this@SettingsActivity,selected)
                        setupSemanticSearchSettings()
                    }.show()
                else {
                    if(!SemanticSearchPrefs.optedIn(this@SettingsActivity)){
                        AlertDialog.Builder(this@SettingsActivity).setCustomTitle(item("Opt in first",20f).apply{setPadding(px,px,px,px)})
                            .setMessage("Enable semantic search before downloading an engine.")
                            .setPositiveButton("OK",null).show()
                    } else AlertDialog.Builder(this@SettingsActivity)
                        .setCustomTitle(item("Download "+selected.label+" Search Engine?",20f).apply{setPadding(px,px,px,px)})
                        .setMessage("Download about "+selected.sizeMb+" MB from Hugging Face. Only the model and tokenizer are downloaded. Your messages and queries stay on this device.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Download"){_,_->
                            val cancel=AtomicBoolean(false)
                            val progressBar=ProgressBar(this@SettingsActivity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100}
                            val title=item("Preparing download...",14f)
                            val view=LinearLayout(this@SettingsActivity).apply{
                                orientation=LinearLayout.VERTICAL
                                setPadding(px,px,px,px)
                                addView(title)
                                addView(progressBar,LinearLayout.LayoutParams(-1,px*2))
                            }
                            val progressDialog=AlertDialog.Builder(this@SettingsActivity)
                                .setCustomTitle(item("Semantic Search Engine",20f).apply{setPadding(px,px,px,px)})
                                .setView(view)
                                .setNegativeButton("Cancel"){_,_->cancel.set(true)}
                                .create()
                            progressDialog.setCanceledOnTouchOutside(false)
                            progressDialog.show()
                            Thread({
                                val error=runCatching{
                                    SemanticModelManager.install(applicationContext,selected,cancel){percent,description->
                                        runOnUiThread{
                                            if(!isFinishing&&!isDestroyed){
                                                progressBar.progress=percent
                                                title.text=description
                                            }
                                        }
                                    }
                                }.exceptionOrNull()
                                if(error==null)SemanticIndexWorker.schedule(applicationContext)
                                runOnUiThread{
                                    progressDialog.dismiss()
                                    if(!isFinishing&&!isDestroyed){
                                        if(error!=null) AlertDialog.Builder(this@SettingsActivity)
                                            .setCustomTitle(item("Engine download incomplete",20f).apply{setPadding(px,px,px,px)})
                                            .setMessage(error.message ?: "Download failed. Try again.")
                                            .setPositiveButton("OK",null).show()
                                        setupSemanticSearchSettings()
                                    }
                                }
                            },"SemanticEngineDownload").start()
                        }.show()
                }
            }
        })
        if(installed) {
            section.addView(item("Rebuild search index",16f).apply{
                setOnClickListener{
                    SemanticIndex.clear(this@SettingsActivity)
                    SemanticIndexWorker.schedule(this@SettingsActivity)
                    android.widget.Toast.makeText(this@SettingsActivity,
                        "Indexing scheduled. New results appear as indexing completes.",android.widget.Toast.LENGTH_LONG).show()
                    setupSemanticSearchSettings()
                }
            })
            section.addView(item("Delete semantic search index",15f).apply{
                setOnClickListener {
                    SemanticIndex.clear(this@SettingsActivity)
                    setupSemanticSearchSettings()
                }
            })
        }
        section.addView(item("Indexing occurs locally in the background when enabled. Disabling deletes search vectors; downloaded engines can be deleted separately.",12f))
        section.addView(item("Don't ask again when searching",14f).apply{
            setOnClickListener{
                SemanticSearchPrefs.dismiss(this@SettingsActivity)
                setupSemanticSearchSettings()
            }
        })
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
