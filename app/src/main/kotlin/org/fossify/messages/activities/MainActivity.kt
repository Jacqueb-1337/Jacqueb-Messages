package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import org.fossify.messages.chatheads.ChatHeadsSettingsActivity
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.provider.Telephony
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import android.view.View
import android.content.res.ColorStateList
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Canvas
import android.graphics.Paint
import org.fossify.messages.theming.UiTheme
import org.fossify.messages.theming.ThemeEditorActivity
import org.fossify.messages.theming.UiMotion
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.checkWhatsNew
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.fadeIn
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LICENSE_EVENT_BUS
import org.fossify.commons.helpers.LICENSE_INDICATOR_FAST_SCROLL
import org.fossify.commons.helpers.LICENSE_SMS_MMS
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.PERMISSION_SEND_SMS
import org.fossify.commons.helpers.SHORT_ANIMATION_DURATION
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.Release
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.adapters.ConversationsAdapter
import org.fossify.messages.adapters.SearchResultsAdapter
import org.fossify.messages.databinding.ActivityMainBinding
import org.fossify.messages.extensions.checkAndDeleteOldRecycleBinMessages
import org.fossify.messages.extensions.clearAllMessagesIfNeeded
import org.fossify.messages.extensions.clearExpiredScheduledMessages
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.SEARCHED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.models.Message
import org.fossify.messages.models.SearchResult
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = true
    
    private val MAKE_DEFAULT_APP_REQUEST = 1

    private var storedTextColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null

    private val binding by viewBinding(ActivityMainBinding::inflate)
    private var sharedThreadId = -1L
    private var lastUnreadTotal = -1

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiMotion.prepareWindow(window, forThread = false)
        setContentView(binding.root)
        contentResolver.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, inboxMessagesObserver)
        contentResolver.registerContentObserver(android.provider.Telephony.Mms.CONTENT_URI, true, inboxMessagesObserver)
        sharedThreadId = savedInstanceState?.getLong("shared_thread_id", -1L) ?: -1L
        setExitSharedElementCallback(object : android.app.SharedElementCallback() {
            override fun onMapSharedElements(
                names: MutableList<String>,
                elements: MutableMap<String, View>
            ) {
                if (UiMotion.THREAD_SURFACE !in names) return
                val adapter = binding.conversationsList.adapter as? ConversationsAdapter
                val position = adapter?.currentList?.indexOfFirst {
                    it.threadId == sharedThreadId
                } ?: -1
                val row = if (position >= 0)
                    binding.conversationsList.findViewHolderForAdapterPosition(position)?.itemView
                else null
                if (row != null && row.isAttachedToWindow) {
                    ViewCompat.setTransitionName(row, UiMotion.THREAD_SURFACE)
                    elements[UiMotion.THREAD_SURFACE] = row
                } else {
                    names.remove(UiMotion.THREAD_SURFACE)
                    elements.remove(UiMotion.THREAD_SURFACE)
                }
            }
        })
        UiMotion.press(binding.conversationsFab, .91f)
        UiMotion.press(binding.samsungTabContacts, .96f)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        installInboxDividers()
        // Reapply the normalized position after rotation, multiwindow or insets.
        binding.mainCoordinator.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,oright,obottom ->
            if(r-l!=oright-ol || b-t!=obottom-ot) {
                binding.conversationsFab.post { if(!isFinishing && !isDestroyed)positionComposeFab() }
            }
        }

        // The tab bar and FAB are siblings of the list. Padding just the
        // RecyclerView still lets system gesture/3-button navigation cover
        // the actual bottom tabs. Inset the entire content coordinator.
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.mainCoordinator))

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }

        // This is an independently branded GPL-derived messaging app, NOT a
        // Fossify release. The upstream Fossify signature/package authenticity
        // dialog is inapplicable here and falsely labels this rebranded app
        // as a fake official Fossify build. Do not run it in our fork.
    }

    private val inboxSyncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inboxSyncRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            ensureBackgroundThread {
                val existing = try {
                    (conversationsDB.getNonArchived() + conversationsDB.getAllArchived())
                        .toMutableList() as ArrayList<Conversation>
                } catch (_: Exception) { ArrayList<Conversation>() }
                if (!isFinishing && !isDestroyed) getNewConversations(existing)
            }
        }
    }
    private val inboxMessagesObserver = object : android.database.ContentObserver(inboxSyncHandler) {
        override fun onChange(selfChange: Boolean) { scheduleInboxSync() }
    }
    private fun scheduleInboxSync() {
        inboxSyncHandler.removeCallbacks(inboxSyncRunnable)
        inboxSyncHandler.postDelayed(inboxSyncRunnable, 170L)
    }

    private var composeReturnPending = false

    override fun onResume() {
        super.onResume()
        org.fossify.messages.updates.UpdateRepository.checkOnLaunch(this,binding.root)
        if (composeReturnPending && UiMotion.enabled()) {
            composeReturnPending = false
            // Rebinding every conversation, rebuilding FAB layout and querying
            // drafts during the shared-element return made Back appear frozen.
            // Keep the already drawn inbox stable until the + transition lands.
            binding.mainCoordinator.postDelayed({
                if (!isFinishing && !isDestroyed) refreshInboxAfterResume()
            }, 420L)
        } else {
            composeReturnPending = false
            refreshInboxAfterResume()
        }
    }

    private fun refreshInboxAfterResume() {
        scheduleInboxSync()
        updateMenuColors()
        refreshMenuItems()
        if (resumeReadSync++ > 0) reconcileReadState()

        getOrCreateConversationsAdapter().apply {
            if (storedTextColor != getProperTextColor()) {
                updateTextColor(getProperTextColor())
            }

            if (storedFontSize != config.fontSize) {
                updateFontSize()
            }

            updateDrafts()
        }

        updateTextColors(binding.mainCoordinator)
        // Color the actual root, not just the search overlay: the Samsung
        // inbox floats its conversation card over a separate surface.
        binding.mainCoordinator.setBackgroundColor(UiTheme.color(this,"surface"))
        binding.searchHolder.setBackgroundColor(UiTheme.color(this,"surface"))
        binding.samsungBottomTabs.setBackgroundColor(UiTheme.color(this,"surface"))
        binding.mainHolder.background=UiTheme.shape(this,"card","card_radius","divider")
        binding.mainHolder.clipToOutline=true
        val mainLp=binding.mainHolder.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val inset=UiTheme.dp(this,UiTheme.metric(this,"inbox_inset"))
        mainLp.marginStart=inset
        mainLp.marginEnd=inset
        binding.mainHolder.layoutParams=mainLp
        binding.samsungTabConversations.setTextColor(UiTheme.color(this,"accent"))
        binding.samsungTabUnreadCount.setTextColor(UiTheme.color(this,"badge_text"))
        binding.samsungTabUnreadCount.background=UiTheme.shape(this,"badge","badge_radius")
        binding.samsungTabContacts.setTextColor(UiTheme.color(this,"preview"))
        applyComposeFabTheme()
        binding.conversationsList.invalidateItemDecorations()
        binding.conversationsList.adapter?.notifyDataSetChanged()
        // MySearchMenu draws its search field gray, but the overflow slot belongs
        // to the toolbar. Giving that slot the near-black page color created
        // the square at the search field's right edge.
        binding.mainMenu.requireToolbar().background=UiTheme.shape(this,"search","composer_radius")

        val properPrimaryColor = getProperPrimaryColor()
        binding.noConversationsPlaceholder2.setTextColor(properPrimaryColor)
        binding.noConversationsPlaceholder2.underlineText()
        binding.conversationsFastscroller.updateColors(properPrimaryColor)
        binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()
    }

    private fun applyComposeFabTheme(){
        // Reapply colors, corner radius, size and position atomically. Some
        // OEM Material FAB background/state updates run after onResume; a
        // second pass on regained focus prevents that overriding a custom
        // saved theme from the editor.
        // Direct drawable styling avoids MyFloatingActionButton's internal
        // color override, which reported the correct tint but painted teal.
        binding.conversationsFab.background=UiTheme.shape(this,"fab","fab_radius")
        binding.conversationsFab.imageTintList=ColorStateList.valueOf(UiTheme.color(this,"fab_icon"))
        binding.conversationsFab.elevation=UiTheme.dp(this,8).toFloat()
        val fabLp=binding.conversationsFab.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val fabSize=UiTheme.dp(this,UiTheme.metric(this,"fab_size"))
        fabLp.width=fabSize
        fabLp.height=fabSize
        // Keep the actual view at the origin; CoordinatorLayout margin/gravity and
        // end-relative XML offsets must not fight the theme's 2D coordinates.
        fabLp.gravity=android.view.Gravity.TOP or android.view.Gravity.START
        fabLp.setMargins(0,0,0,0)
        fabLp.marginStart=0
        fabLp.marginEnd=0
        binding.conversationsFab.layoutParams=fabLp
        binding.mainCoordinator.post {
            if (!isFinishing && !isDestroyed) positionComposeFab()
        }
    }

    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && !isFinishing && !isDestroyed){
            binding.conversationsFab.post{
                if(!isFinishing && !isDestroyed) applyComposeFabTheme()
            }
        }
    }

    private fun positionComposeFab(){
        val host=binding.mainCoordinator
        val fab=binding.conversationsFab
        if(host.width<=0 || host.height<=0 || fab.width<=0 || fab.height<=0)return
        val (x,y)=UiTheme.fabPosition(this,host.width,host.height,fab.width,fab.height)
        fab.x=x
        fab.y=y
    }

    private fun reconcileReadState() {
        ensureBackgroundThread {
            // Never mark everything read if SMS permission/provider access
            // fails: only reconcile after successfully querying BOTH providers.
            if (android.provider.Telephony.Sms.getDefaultSmsPackage(this) != packageName) return@ensureBackgroundThread
            val unread = HashSet<Long>()
            var successfulQueries = 0
            for (uri in listOf(android.provider.Telephony.Sms.CONTENT_URI, android.provider.Telephony.Mms.CONTENT_URI)) {
                try {
                    contentResolver.query(uri, arrayOf("thread_id"), "read=0", null, null)?.use { c ->
                        successfulQueries++
                        while (c.moveToNext()) unread.add(c.getLong(0))
                    }
                } catch (_: Exception) {}
            }
            if (successfulQueries != 2) return@ensureBackgroundThread
            try {
                conversationsDB.getNonArchived().forEach { c ->
                    if (!c.read && c.threadId !in unread) conversationsDB.markRead(c.threadId)
                }
            } catch (_: Exception) {}
            runOnUiThread { if (!isFinishing && !isDestroyed) getCachedConversations() }
        }
    }

    private var resumeReadSync = 0

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(inboxMessagesObserver)
        inboxSyncHandler.removeCallbacks(inboxSyncRunnable)
        super.onDestroy()
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    private fun installInboxDividers() {
        // Draw in RecyclerView's own coordinates AFTER children. The old XML
        // divider was translated beyond the padded row and got clipped away.
        binding.conversationsList.addItemDecoration(object:RecyclerView.ItemDecoration() {
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDrawOver(canvas:Canvas,parent:RecyclerView,state:RecyclerView.State) {
                super.onDrawOver(canvas,parent,state)
                if (parent.adapter==null) return
                paint.color=UiTheme.color(this@MainActivity,"divider")
                val height=UiTheme.dp(this@MainActivity,
                    UiTheme.metric(this@MainActivity,"divider_thickness")).coerceAtLeast(1)
                val start=parent.paddingLeft+UiTheme.dp(this@MainActivity,
                    UiTheme.metric(this@MainActivity,"divider_inset"))
                for (i in 0 until parent.childCount) {
                    val child=parent.getChildAt(i)
                    val position=parent.getChildAdapterPosition(child)
                    if(position==RecyclerView.NO_POSITION || position==parent.adapter!!.itemCount-1)
                        continue
                    val y=child.bottom+child.translationY
                    if(y < 0 || y > parent.height)continue
                    canvas.drawRect(start.toFloat(),y-height,
                        (parent.width-parent.paddingRight-UiTheme.dp(this@MainActivity,20)).toFloat(),
                        y,paint)
                }
            }
        })
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu_main)
        binding.mainMenu.toggleHideOnScroll(true)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchClosedListener = {
            fadeOutSearch()
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            if (text.isNotEmpty()) {
                if (binding.searchHolder.alpha < 1f) {
                    binding.searchHolder.fadeIn()
                }
            } else {
                fadeOutSearch()
            }
            searchTextChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.show_recycle_bin -> launchRecycleBin()
                R.id.show_archived -> launchArchivedConversations()
                R.id.chat_heads -> startActivity(Intent(this, ChatHeadsSettingsActivity::class.java))
                R.id.theme_studio -> startActivity(Intent(this, ThemeEditorActivity::class.java))
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun refreshMenuItems() {
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.show_recycle_bin).isVisible = true
            findItem(R.id.show_archived).isVisible = config.isArchiveAvailable
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            if (resultCode == RESULT_OK) {
                askPermissions()
            } else {
                finish()
            }
        }
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedFontSize = config.fontSize
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun loadMessages() {
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
                }
            } else {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                startActivityForResult(intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional.
    // If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            handleNotificationPermission { granted ->
                                if (!granted) {
                                    PermissionRequiredDialog(
                                        activity = this,
                                        textId = org.fossify.commons.R.string.allow_notifications_incoming_messages,
                                        positiveActionCallback = { openNotificationSettings() })
                                }
                            }

                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun initMessenger() {
        checkWhatsNewDialog()
        storeStateVariables()
        getCachedConversations()
        binding.noConversationsPlaceholder2.setOnClickListener {
            launchNewConversation()
        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
        // Samsung's Contacts tab is a working action: it opens the existing
        // contacts/conversation picker instead of an inert decorative tab.
        binding.samsungTabContacts.setOnClickListener {
            launchNewConversation(binding.samsungTabContacts)
        }
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }

            val archived = try {
                conversationsDB.getAllArchived()
            } catch (_: Exception) {
                listOf()
            }

            runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations(
                    (conversations + archived).toMutableList() as ArrayList<Conversation>
                )
            }
            conversations.forEach {
                clearExpiredScheduledMessages(it.threadId)
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        ensureBackgroundThread {
            val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)

            conversations.forEach { clonedConversation ->
                val threadIds = cachedConversations.map { it.threadId }
                if (!threadIds.contains(clonedConversation.threadId)) {
                    conversationsDB.insertOrUpdate(clonedConversation)
                    cachedConversations.add(clonedConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                if (isConversationDeleted && !isTemporaryThread) {
                    conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    // delete the original temporary thread and move any scheduled messages
                    // to the new thread
                    conversationsDB.deleteThreadId(threadId)
                    messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            messagesDB.insertOrUpdate(
                                message.copy(threadId = newConversation.threadId)
                            )
                        }
                    insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv, new = it
                    )
                }
                if (conv != null) {
                    // FIXME: Scheduled message date is being reset here. Conversations with
                    //  scheduled messages will have their original date.
                    insertOrUpdateConversation(conv)
                }
            }

            val allConversations = conversationsDB.getNonArchived() as ArrayList<Conversation>
            runOnUiThread {
                setupConversations(allConversations)
            }

            if (config.appRunCount == 1) {
                conversations.map { it.threadId }.forEach { threadId ->
                    val messages = getMessages(threadId, includeScheduledMessages = false)
                    messages.chunked(30).forEach { currentMessages ->
                        messagesDB.insertMessages(*currentMessages.toTypedArray())
                    }
                }
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter {
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) }
            )

            binding.conversationsList.adapter = currAdapter
            binding.conversationsList.itemAnimator = DefaultItemAnimator().apply {
                addDuration = 230
                removeDuration = 170
                moveDuration = 260
                changeDuration = 125
                supportsChangeAnimations = false
            }
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun setupConversations(
        conversations: ArrayList<Conversation>,
        cached: Boolean = false,
    ) {
        val unreadThreads = conversations.count { !it.read && it.unreadCount > 0 }
        binding.samsungTabUnreadCount.visibility = if (unreadThreads > 0) View.VISIBLE else View.GONE
        binding.samsungTabUnreadCount.text = when {
            unreadThreads > 99 -> "99+"
            else -> unreadThreads.toString()
        }
        if (lastUnreadTotal >= 0 && lastUnreadTotal != unreadThreads && unreadThreads > 0) {
            binding.samsungTabUnreadCount.post {
                if (!isFinishing && !isDestroyed) UiMotion.bump(binding.samsungTabUnreadCount)
            }
        }
        lastUnreadTotal = unreadThreads
        val sortedConversations = conversations
            .sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }.thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>

        if (cached && config.appRunCount == 1) {
            // there are no cached conversations on the first run so we show the
            // loading placeholder and progress until we are done loading from telephony
            showOrHideProgress(conversations.isEmpty())
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
            binding.noConversationsPlaceholder.beVisible()
            binding.noConversationsPlaceholder.text = getString(R.string.loading_messages)
        } else {
            binding.conversationsProgressBar.hide()
            binding.noConversationsPlaceholder.beGone()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.text = getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        val conversation = any as? Conversation ?: return
        val launch = Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
        }
        val adapter = binding.conversationsList.adapter as? ConversationsAdapter
        val index = adapter?.currentList?.indexOfFirst {
            it.threadId == conversation.threadId
        } ?: -1
        val row = if (index >= 0)
            binding.conversationsList.findViewHolderForAdapterPosition(index)?.itemView
        else null
        if (UiMotion.enabled() && row != null && row.isAttachedToWindow) {
            val coordinates = IntArray(2)
            row.getLocationOnScreen(coordinates)
            launch.putExtra(UiMotion.THREAD_ORIGIN_X, coordinates[0])
            launch.putExtra(UiMotion.THREAD_ORIGIN_Y, coordinates[1])
            launch.putExtra(UiMotion.THREAD_ORIGIN_WIDTH, row.width)
            launch.putExtra(UiMotion.THREAD_ORIGIN_HEIGHT, row.height)
        }
        // This native row-to-window transformation does not create an extra
        // gray shared-element snapshot over the destination content.
        startActivity(launch)
        if (UiMotion.enabled()) overridePendingTransition(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("shared_thread_id", sharedThreadId)
        super.onSaveInstanceState(outState)
    }

    private fun launchNewConversation(origin: View = binding.conversationsFab) {
        composeReturnPending = true
        hideKeyboard()
        val launch = Intent(this, NewConversationActivity::class.java)
        if (UiMotion.enabled() && origin.isAttachedToWindow) {
            ViewCompat.setTransitionName(origin, UiMotion.COMPOSE_SURFACE)
            launch.putExtra(UiMotion.SHARED_COMPOSE_LAUNCH, true)
            try {
                val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                    this, origin, UiMotion.COMPOSE_SURFACE)
                startActivity(launch, options.toBundle())
            } catch (_: IllegalArgumentException) {
                launch.removeExtra(UiMotion.SHARED_COMPOSE_LAUNCH)
                startActivity(launch)
            }
        } else startActivity(launch)
    }

    @SuppressLint("NewApi")
    private fun checkShortcut() {
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor != appIconColor) {
            val newConversation = getCreateNewContactShortcut(appIconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = listOf(newConversation)
                config.lastHandledShortcutColor = appIconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(this, org.fossify.commons.R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(
            org.fossify.commons.R.id.shortcut_plus_background
        ).applyColorFilter(appIconColor)

        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        if (!binding.mainMenu.isSearchOpen && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.length >= 2)
        if (text.length >= 2) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = messagesDB.getMessagesWithText(searchQuery)
                val conversations = conversationsDB.getConversationsWithText(searchQuery)
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val searchResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val date = (conversation.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                snippet = conversation.phoneNumber,
                date = date,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri
            )
            searchResults.add(searchResult)
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val date = (message.date * 1000L).formatDateOrTime(
                context = this,
                hideTimeOnOtherDays = true,
                showCurrentYear = true
            )

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                snippet = message.body,
                date = date,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri
            )
            searchResults.add(searchResult)
        }

        runOnUiThread {
            binding.searchResultsList.beVisibleIf(searchResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(searchResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchResults, binding.searchResultsList, searchedText) {
                    hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchResults, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        hideKeyboard()
        startActivity(Intent(applicationContext, RecycleBinConversationsActivity::class.java))
    }

    private fun launchArchivedConversations() {
        hideKeyboard()
        startActivity(Intent(applicationContext, ArchivedConversationsActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or LICENSE_SMS_MMS or LICENSE_INDICATOR_FAST_SCROLL

        val faqItems = arrayListOf(
            FAQItem(
                title = R.string.faq_2_title,
                text = R.string.faq_2_text
            ),
            FAQItem(
                title = R.string.faq_3_title,
                text = R.string.faq_3_text
            ),
            FAQItem(
                title = R.string.faq_4_title,
                text = R.string.faq_4_text
            ),
            FAQItem(
                title = org.fossify.commons.R.string.faq_9_title_commons,
                text = org.fossify.commons.R.string.faq_9_text_commons
            )
        )

        if (resources.getBoolean(org.fossify.commons.R.bool.is_google_play_build)) {
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_2_title_commons,
                    text = org.fossify.commons.R.string.faq_2_text_commons
                )
            )
            faqItems.add(
                FAQItem(
                    title = org.fossify.commons.R.string.faq_6_title_commons,
                    text = org.fossify.commons.R.string.faq_6_text_commons
                )
            )
        }

        startAboutActivity(
            appNameId = R.string.jacqueb_messages_name,
            licenseMask = licenses,
            versionName = BuildConfig.VERSION_NAME,
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        // Keep existing rows displayed until authoritative telephony data
        // arrives; re-running initMessenger temporarily restored stale cache.
        scheduleInboxSync()
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
