package org.fossify.messages.chatheads

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import org.fossify.messages.extensions.markThreadMessagesRead
import android.net.Uri
import android.widget.ImageView
import org.fossify.messages.theming.UiTheme
import org.fossify.messages.helpers.ConversationCustomization
import com.bumptech.glide.Glide
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Legacy-style overlay chat heads. This intentionally uses WindowManager application
 * overlays, never Android's native BubbleMetadata APIs.
 *
 * Mechanics: 5dp offset stack, touch slop drag recognition, velocity-aware side selection,
 * a spring-driven position state, attraction to a dismiss target, and a separate
 * focusable expanded conversation overlay.
 */
class FloatingChatHeadsService : Service() {
    data class Head(val id: Long, val title: String, val address: String)
    private val heads = LinkedHashMap<Long, Head>()
    private val avatarCache = HashMap<String, String?>()
    // A chat-head view can live for hours. Refresh the existing image in place
    // when its conversation picture changes rather than replacing the view.
    private val conversationAvatarPrefs by lazy {
        getSharedPreferences("conversation_personalization_v1", MODE_PRIVATE)
    }
    private val avatarPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val id = key?.takeIf { it.startsWith("t_") && it.endsWith("_photo_updated") }
            ?.removePrefix("t_")?.removeSuffix("_photo_updated")?.toLongOrNull()
        if (id != null) main.post {
            heads[id]?.let { refreshAvatarPhotoForHead(it) }
        }
    }
    private var selectedId = -1L
    private var unreadHeadCount = 0
    private var badgeView: TextView? = null
    private var badgeQuerySerial = 0
    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var headView: FrameLayout? = null
    private var headParams: WindowManager.LayoutParams? = null
    private var closeTarget: FrameLayout? = null
    private var closeParams: WindowManager.LayoutParams? = null
    private var panel: OverlayConversationPanel? = null
    private var retractingPanel: OverlayConversationPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // Alias to headView while expanded. THE SAME view instance stays attached
    // to WindowManager through expand -> collapse; no replacement at the dock.
    private var expandedStrip: FrameLayout? = null
    private val avatarViews = LinkedHashMap<Long, FrameLayout>()
    // Android clips a TYPE_APPLICATION_OVERLAY at its WINDOW boundary even if
    // the child FrameLayout sets clipChildren=false. A non-touchable transparent
    // full-screen visual layer draws followers beyond the 79dp touch window.
    // The actual, touchable lead head remains in its original small window.
    private var trailVisualLayer: FrameLayout? = null
    private val trailVisualAvatars = LinkedHashMap<Long, FrameLayout>()
    private val trailSprings = HashMap<Long, Pair<MessengerSpring, MessengerSpring>>()
    private var trailAnimator: ValueAnimator? = null
    private var lastTrailNanos = 0L
    private var expandedDragId = -1L
    private var expandedDragActive = false
    private var expandedDragStartRawX = 0f
    private var expandedDragStartRawY = 0f
    private var expandedDragStartWindowX = 0
    private var expandedDragStartWindowY = 0
    private var expandedDragStartParamX = 0
    private var expandedDragStartParamY = 0
    private var expandedDragLatestDx = 0f
    private var expandedDragLatestDy = 0f
    private var expandedDragPreviewOrder: List<Long> = emptyList()
    private var expandedDragGhost: FrameLayout? = null
    private var expandedDragGhostParams: WindowManager.LayoutParams? = null
    private var expandedDragView: View? = null
    // Use one opacity for the docked lead and the touch-through follower layer.
    // Android 12+ may block game touches when a non-touchable overlay is > 0.8.
    private val collapsedStackOpacity = .78f
    private val leadWindowLocation = IntArray(2)
    private val visualWindowLocation = IntArray(2)
    private var expandedStripParams: WindowManager.LayoutParams? = null
    private var expandedAnimator: ValueAnimator? = null
    private val followers = ArrayList<Triple<View, MessengerSpring, MessengerSpring>>()
    private var closingExpanded = false
    private var expandedSpringX = MessengerSpring(40.0, 7.0, .3, .3)
    private var expandedSpringY = MessengerSpring(40.0, 7.0, .3, .3)
    private var savedDockX = 0
    private var savedDockY = 0
    private var flyout: FrameLayout? = null
    private var flyoutParams: WindowManager.LayoutParams? = null
    private var flyoutGeneration = 0
    private var flyoutAnimator: ValueAnimator? = null
    private var incomingGeneration = 0
    private var entryAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null
    // Position-following preset recovered from Messenger's RUt.A08 = (40, 7).
    private val spring = MessengerSpring(40.0, 7.0, restDisplacement = .3, restVelocity = .3)
    private val verticalSpring = MessengerSpring(40.0, 7.0, restDisplacement = .3, restVelocity = .3)
    // Attraction activation uses Messenger's RV0 opacity spring family.
    private val attractionSpring = MessengerSpring(40.0, 7.0, restDisplacement = .005, restVelocity = .005)
    private var previousTouchMs = 0L
    private var prevFrameNanos = 0L
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var withinDismissCatch = false
    private var velocity: VelocityTracker? = null
    private var observing = false
    private var foregroundStarted = false
    private var systemReceiverRegistered = false
    private val systemNavigationReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> collapseFromSystemNavigation()
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason")
                    if (reason == "homekey" || reason == "recentapps" ||
                        reason == "assist" || reason == "globalactions")
                        collapseFromSystemNavigation()
                }
            }
        }
    }

    private fun collapseFromSystemNavigation() {
        if (expandedStrip != null && !closingExpanded) main.post { collapse() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        collapseFromSystemNavigation()
        super.onTaskRemoved(rootIntent)
    }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { panel?.refreshMessages() }
    }
    private val unreadObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { refreshUnreadHeadCount() }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        activeService = java.lang.ref.WeakReference(this)
        conversationAvatarPrefs.registerOnSharedPreferenceChangeListener(avatarPreferenceListener)
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(systemNavigationReceiver, filter,
                RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(systemNavigationReceiver, filter)
            systemReceiverRegistered = true
        } catch (_: Exception) {
            // Android may block system-dialog broadcasts from unprivileged apps.
        }
        // Unread state can change when the full messaging app is opened or a
        // notification is marked read. Keep the badge in sync while collapsed.
        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, unreadObserver)
            contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, unreadObserver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        if (intent?.action == ACTION_CLEAR) clearAllHeads()
        if (intent?.action == ACTION_REMOVE_SAFE || intent?.action == ACTION_REMOVE_MUTED) {
            val removed=intent.getLongExtra(EXTRA_THREAD_ID,-1L)
            if(removed>0 && heads.remove(removed)!=null) {
                if(selectedId==removed) {
                    panel?.let {
                        it.animate().cancel()
                        try { windows.removeViewImmediate(it) } catch (_:Exception) {}
                    }
                    panel=null
                    panelParams=null
                    selectedId=heads.keys.lastOrNull() ?: -1L
                }
                refreshUnreadHeadCount();showHeads();if(expandedStrip!=null)rebuildExpandedStrip()
            }
        }
        if (intent?.action == ACTION_ADD) {
            val id = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
            val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
            if (id > 0L && !org.fossify.messages.safe.SafeStore.isSafe(this,id) &&
                (!intent.getBooleanExtra(EXTRA_INCOMING,false) ||
                    org.fossify.messages.helpers.ConversationCustomization.notificationsEnabled(this,id))) {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { address }
                val expanded = expandedStrip != null
                val previous = selectedId
                heads.remove(id)
                heads[id] = Head(id, title, address)
                if (!expanded) selectedId = id
                while (heads.size > 5) heads.remove(heads.keys.first())
                refreshUnreadHeadCount()
                if (expanded) {
                    if (!heads.containsKey(previous)) selectedId = id
                    // Update the existing views, but honor an explicit
                    // "Open Chat Head" request from the main conversation UI.
                    showHeads()
                    rebuildExpandedStrip()
                    if (intent.getBooleanExtra(EXTRA_EXPAND, false) && id != previous)
                        switchExpanded(id)
                    else
                        panel?.refreshMessages()
                } else {
                    val incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)
                    showHeads(animateEntry = incoming)
                    if (intent.getBooleanExtra(EXTRA_EXPAND, false)) expand()
                    else if (incoming) {
                        val serial = ++incomingGeneration
                        val preview = intent.getStringExtra(EXTRA_PREVIEW).orEmpty()
                        main.postDelayed({
                            if (serial == incomingGeneration && expandedStrip == null)
                                showFlyout(preview)
                        }, 350L)
                    }
                }
            }
        }
        // Keep an opted-in foreground service armed, even without a visible head.
        // Android 15/16 can deny cold foreground-service launches from an SMS broadcast.
        if (heads.isEmpty() && !shouldStayArmed()) stopSelf()
        // Restore the user-opted-in foreground service if Android reclaims its process.
        return if (shouldStayArmed()) START_STICKY else START_NOT_STICKY
    }

    private fun shouldStayArmed(): Boolean {
        val prefs = getSharedPreferences(ChatHeadsSettingsActivity.PREFS, MODE_PRIVATE)
        return prefs.getBoolean(ChatHeadsSettingsActivity.ENABLED, false) &&
            prefs.getBoolean(ChatHeadsSettingsActivity.AUTO_SHOW, true) &&
            Settings.canDrawOverlays(this)
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Floating chat heads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps opted-in floating conversations available"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, ChatHeadsSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FloatingChatHeadsService::class.java).apply { action = ACTION_CLEAR },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Chat heads active")
            .setContentText("Ready for incoming SMS/MMS chat heads")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close all", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(HEAD_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(HEAD_NOTIFICATION, notification)
        foregroundStarted = true
    }

    private fun params(width: Int, height: Int, focusable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (focusable) 0 else
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (focusable) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    // The panel consumes animated IME insets; WM must not also resize it.
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                } else softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }

    private fun showHeads(animateEntry: Boolean = false) {
        if (headView == null) {
            // Create the visual-only layer BEFORE the touch window so the front
            // head draws above the followers. This window passes touches through.
            ensureTrailVisualLayer()
            val settings = getSharedPreferences(POSITION_PREFS, MODE_PRIVATE)
            headParams = params(dp(79), dp(79), false).apply {
                x = if (settings.getBoolean("right", true)) max(0, screenWidth() - dp(79)) else 0
                val yFraction = settings.getFloat("yFraction", .20f).coerceIn(0f, 1f)
                y = (yFraction * (screenHeight() - dp(79))).toInt()
                    .coerceIn(dp(16), max(dp(16), screenHeight() - dp(110)))
            }
            headView = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                isClickable = true
                setOnTouchListener { _, event -> touch(event) }
            }.also { windows.addView(it, headParams) }
        }
        syncAvatarViews()
        if (expandedStrip == null) {
            headView?.alpha = collapsedStackOpacity
            val order = orderedHeads()
            val root = headView ?: return
            val right = (headParams?.x ?: 0) >= screenWidth()/2
            for ((index, head) in order.withIndex()) {
                val view = avatarViews[head.id] ?: continue
                view.translationX = dp(if (right) 7-index*5 else 3+index*5).toFloat()
                view.translationY = dp(2).toFloat()
                view.setOnClickListener(null)
                view.isClickable = true
                // Child avatars consume touches before the root FrameLayout.
                // Forward their raw MotionEvent to the same drag/tap handler.
                view.setOnTouchListener { _, event ->
                    if (expandedStrip == null && !closingExpanded) touch(event) else false
                }
                if (animateEntry && head.id == selectedId && index == 0)
                    animateIncomingAvatar(view)
            }
            root.contentDescription = "Floating conversations, $unreadHeadCount unread"
            setupTrailingHeads()
            showTrailVisuals()
        }
        updateUnreadBadge()
    }

    private fun syncAvatarViews() {
        val root = headView ?: return
        val order = orderedHeads().take(5)
        val activeIds = order.map { it.id }.toSet()
        for (oldId in avatarViews.keys.toList()) {
            if (oldId !in activeIds) {
                avatarViews.remove(oldId)?.let(root::removeView)
            }
        }
        // Place oldest first, newest LAST, so the newest always draws on top
        // both during expanded transitions and in the collapsed stack.
        for (head in order.asReversed()) {
            val avatar = avatarViews[head.id] ?: makeHeadAvatar(head).also { avatarViews[head.id] = it }
            bindAvatarPhoto(head, avatar)
            if (avatar.parent !== root) {
                (avatar.parent as? android.view.ViewGroup)?.removeView(avatar)
                root.addView(avatar, FrameLayout.LayoutParams(dp(68), dp(75)))
            } else {
                avatar.bringToFront()
            }
        }
        if (badgeView?.parent !== root) {
            badgeView = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                background = oval(0xFF1877F2.toInt())
                isClickable = false
            }.also {
                root.addView(it, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            }
        }
        badgeView?.bringToFront()
    }

    private fun ensureTrailVisualLayer() {
        if (trailVisualLayer != null) return
        val visual = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }
        val lp = params(-1, -1, false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12+ blocks pass-through touches from an untrusted
            // fullscreen overlay when its window opacity exceeds 0.8.
            // Match the lead head while allowing touches to pass to games.
            alpha = collapsedStackOpacity
        }
        try {
            windows.addView(visual, lp)
            trailVisualLayer = visual
        } catch (e: Exception) {
            android.util.Log.w("FloatingChatHeads", "Unable to add follower visual layer", e)
        }
    }

    private fun syncTrailVisualAvatars() {
        val layer = trailVisualLayer ?: return
        val active = orderedHeads().drop(1).map { it.id }.toSet()
        for (id in trailVisualAvatars.keys.toList()) {
            if (id !in active) {
                trailVisualAvatars.remove(id)?.let { layer.removeView(it) }
            }
        }
        // Draw the nearest follower last, above older followers, but below
        // the separate lead window at all times.
        for (head in orderedHeads().drop(1).asReversed()) {
            val visual = trailVisualAvatars[head.id] ?: makeHeadAvatar(head).also {
                trailVisualAvatars[head.id] = it
            }
            bindAvatarPhoto(head, visual)
            if (visual.parent !== layer) {
                (visual.parent as? android.view.ViewGroup)?.removeView(visual)
                layer.addView(visual, FrameLayout.LayoutParams(dp(68), dp(75)))
            } else visual.bringToFront()
            visual.isClickable = false
            visual.setOnTouchListener(null)
        }
    }

    private fun positionTrailVisualAvatars() {
        val lead = headView ?: return
        val visual = trailVisualLayer ?: return
        if (!lead.isAttachedToWindow || !visual.isAttachedToWindow) return
        // WindowManager lp.x/lp.y aren't guaranteed to share the coordinate
        // origin across the small touch overlay and the full-screen visual
        // overlay (status-bar cutouts / OEM inset handling). Convert using
        // actual measured screen origins so every head docks at the same Y.
        lead.getLocationOnScreen(leadWindowLocation)
        visual.getLocationOnScreen(visualWindowLocation)
        val offsetX = (leadWindowLocation[0] - visualWindowLocation[0]).toFloat()
        val offsetY = (leadWindowLocation[1] - visualWindowLocation[1]).toFloat()
        for (head in orderedHeads().drop(1)) {
            val original = avatarViews[head.id] ?: continue
            trailVisualAvatars[head.id]?.apply {
                translationX = offsetX + original.translationX
                translationY = offsetY + original.translationY
            }
        }
    }

    private fun showTrailVisuals() {
        val layer = trailVisualLayer ?: return
        syncTrailVisualAvatars()
        if (expandedStrip != null || heads.size < 2) {
            hideTrailVisuals()
            return
        }
        positionTrailVisualAvatars()
        layer.post {
            if (trailVisualLayer === layer && expandedStrip == null) positionTrailVisualAvatars()
        }
        layer.visibility = View.VISIBLE
        for (head in orderedHeads().drop(1)) {
            avatarViews[head.id]?.visibility = View.INVISIBLE
        }
    }

    private fun hideTrailVisuals() {
        trailVisualLayer?.visibility = View.GONE
        for (original in avatarViews.values) original.visibility = View.VISIBLE
    }

    private fun removeTrailVisualLayer() {
        trailVisualLayer?.let {
            try { windows.removeViewImmediate(it) } catch (_: Exception) {}
        }
        trailVisualLayer = null
        trailVisualAvatars.clear()
    }

    private fun setupTrailingHeads() {
        val order = orderedHeads()
        val rootX = headParams?.x ?: return
        val rootY = headParams?.y ?: return
        val right = rootX >= screenWidth()/2
        for ((i, head) in order.withIndex()) {
            val view = avatarViews[head.id] ?: continue
            if (i==0) continue // Frontmost head follows finger directly.
            if (!trailSprings.containsKey(head.id)) {
                val x = MessengerSpring(220.0, 15.0, .3, .3)
                val y = MessengerSpring(220.0, 15.0, .3, .3)
                x.jump(rootX.toDouble() + dp(if (right) 7-i*5 else 3+i*5))
                y.jump(rootY.toDouble() + dp(2))
                trailSprings[head.id] = x to y
            }
        }
        trailSprings.keys.retainAll(order.drop(1).map { it.id }.toSet())
    }

    /**
     * A true sequential 220/15 spring chain, not several independent springs
     * driven by the lead's position. Each follower receives the preceding
     * follower's INTEGRATED position on this same animation frame. This makes
     * acceleration, reversals and settling propagate through the entire stack.
     */
    private fun updateTrailTargets() {
        if (expandedStrip != null || trailSprings.isEmpty()) return
        ensureTrailAnimator()
    }

    private fun ensureTrailAnimator() {
        if (trailAnimator?.isRunning == true || trailSprings.isEmpty()) return
        lastTrailNanos = 0L
        trailAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Safety ceiling, not the end of the physics: every input restarts
            // the chain, and convergence ends a running animation earlier.
            duration = 1800L
            addUpdateListener {
                if (expandedStrip != null) { cancel(); return@addUpdateListener }
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val elapsed = if (lastTrailNanos == 0L) 0.0
                    else ((now-lastTrailNanos)/1.0e9).coerceIn(0.0, .064)
                lastTrailNanos = now
                val rootX = headParams?.x ?: return@addUpdateListener
                val rootY = headParams?.y ?: return@addUpdateListener
                val right = rootX >= screenWidth()/2
                val step = dp(if (right) -5 else 5).toDouble()
                var previousX = rootX.toDouble() + dp(if (right) 7 else 3)
                var previousY = rootY.toDouble() + dp(2)
                var allSettled = true
                for (head in orderedHeads().drop(1)) {
                    val view = avatarViews[head.id] ?: continue
                    val (x, y) = trailSprings[head.id] ?: continue
                    // Parent is the *updated* previous node, not root plus i*5dp.
                    x.animateTo(previousX + step)
                    y.animateTo(previousY)
                    previousX = x.update(elapsed)
                    previousY = y.update(elapsed)
                    view.translationX = (previousX-rootX).toFloat()
                    view.translationY = (previousY-rootY).toFloat()
                    if (!x.atRest() || !y.atRest()) allSettled = false
                }
                positionTrailVisualAvatars()
                if (allSettled) cancel()
            }
            start()
        }
    }

    private fun makeHeadAvatar(head: Head): FrameLayout {
        val wrapper = FrameLayout(this)
        val group=head.address.contains(',') || head.title.contains(',')
        val circle = FrameLayout(this).apply {
            background = UiTheme.shape(this@FloatingChatHeadsService,
                if(group)"group" else "avatar",if(group)"group_radius" else "avatar_radius")
            clipToOutline = true
            elevation = dp(5).toFloat()
        }
        circle.addView(TextView(this).apply {
            text = head.title.firstOrNull()?.uppercase() ?: "?"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, FrameLayout.LayoutParams(-1, -1))
        val photo = ImageView(this).apply {
            tag = "chat-head-photo"
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = head.title
        }
        circle.addView(photo, FrameLayout.LayoutParams(-1, -1))
        wrapper.addView(circle, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            leftMargin = dp(4); topMargin = dp(2)
        })
        bindAvatarPhoto(head, wrapper)
        wrapper.contentDescription = head.title
        wrapper.isClickable = true
        wrapper.setOnTouchListener { _, event ->
            if (expandedStrip == null && !closingExpanded) touch(event) else false
        }
        return wrapper
    }

    /** The canonical image binding is used by the docked bubble, expanded
     * strip, touch-through followers, and the temporary drag ghost. */
    private fun refreshAvatarPhotoForHead(head: Head) {
        avatarViews[head.id]?.let { bindAvatarPhoto(head, it) }
        trailVisualAvatars[head.id]?.let { bindAvatarPhoto(head, it) }
        if (expandedDragId == head.id) expandedDragGhost?.let { bindAvatarPhoto(head, it) }
    }

    private fun bindAvatarPhoto(head: Head, wrapper: FrameLayout) {
        val image = wrapper.findViewWithTag<ImageView>("chat-head-photo") ?: return
        val custom = ConversationCustomization.photoFile(this, head.id)
        val revision = conversationAvatarPrefs.getLong("t_${head.id}_photo_updated", 0L)
        // Glide caches files by URI. Revision/signature invalidates both
        // PNG and GIF requests when a conversation's image changes.
        val bindingKey = if (custom != null)
            "custom:${head.id}:$revision:${custom.lastModified()}:${custom.length()}"
        else "contact:${head.id}:${head.address}"
        if (wrapper.tag == bindingKey) return
        Glide.with(applicationContext).clear(image)
        image.setImageDrawable(null)
        wrapper.tag = bindingKey
        if (custom != null) {
            if(ConversationCustomization.isAnimatedPhoto(custom))
                Glide.with(applicationContext).asGif().load(custom)
                    .signature(com.bumptech.glide.signature.ObjectKey(bindingKey))
                    .centerCrop().into(image)
            else Glide.with(applicationContext).load(custom)
                .signature(com.bumptech.glide.signature.ObjectKey(bindingKey))
                .centerCrop().into(image)
        } else {
            loadContactAvatar(head.address, image, wrapper, bindingKey)
        }
    }

    private fun updateUnreadBadge() {
        badgeView?.apply {
            visibility = if (unreadHeadCount > 0 && expandedStrip == null) View.VISIBLE else View.GONE
            text = if (unreadHeadCount > 99) "99+" else unreadHeadCount.toString()
        }
    }

    private fun refreshUnreadHeadCount() {
        val ids = heads.keys.toList()
        val serial = ++badgeQuerySerial
        if (ids.isEmpty()) {
            unreadHeadCount = 0
            updateUnreadBadge()
            return
        }
        Thread {
            val unread = HashSet<Long>()
            val selectionIds = ids.joinToString(",") { "?" }
            try {
                contentResolver.query(Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.THREAD_ID),
                    "${Telephony.Sms.THREAD_ID} IN ($selectionIds) AND ${Telephony.Sms.READ}=0 AND ${Telephony.Sms.TYPE}=?",
                    ids.map(Long::toString).plus(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()).toTypedArray(),
                    null)?.use { cursor ->
                    while (cursor.moveToNext()) unread.add(cursor.getLong(0))
                }
                contentResolver.query(Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms.THREAD_ID),
                    "${Telephony.Mms.THREAD_ID} IN ($selectionIds) AND ${Telephony.Mms.READ}=0 AND ${Telephony.Mms.MESSAGE_BOX}=?",
                    ids.map(Long::toString).plus(Telephony.Mms.MESSAGE_BOX_INBOX.toString()).toTypedArray(),
                    null)?.use { cursor ->
                    while (cursor.moveToNext()) unread.add(cursor.getLong(0))
                }
            } catch (_: Exception) {}
            main.post {
                if (badgeQuerySerial != serial) return@post
                unreadHeadCount = unread.size
                updateUnreadBadge()
            }
        }.start()
    }

    /**
     * REA RTA.A0H uses targetX ±78dp, y+1dp and initial velocities
     * of ±1000dp/s (toward the edge target) and -1500dp/s vertically.
     * The leading icon uses the recovered RUt 40/7 positioning spring.
     */
    private fun animateIncomingAvatar(view: View) {
        entryAnimator?.cancel()
        val dockRight = (headParams?.x ?: 0) >= screenWidth() / 2
        val initialX = dp(if (dockRight) 78 else -78).toDouble()
        val xSpring = MessengerSpring(40.0, 7.0, .3, .3)
        val ySpring = MessengerSpring(40.0, 7.0, .3, .3)
        xSpring.jump(initialX)
        xSpring.impartVelocity(dp(if (dockRight) -1000 else 1000).toDouble())
        xSpring.animateTo(0.0)
        ySpring.jump(dp(1).toDouble())
        ySpring.impartVelocity(-dp(1500).toDouble())
        ySpring.animateTo(0.0)
        view.translationX = initialX.toFloat()
        view.translationY = dp(1).toFloat()
        var lastNanos = 0L
        entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            addUpdateListener {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val delta = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1.0e9
                lastNanos = now
                view.translationX = xSpring.update(delta).toFloat()
                view.translationY = ySpring.update(delta).toFloat()
                if (xSpring.atRest() && ySpring.atRest()) cancel()
            }
            start()
        }
    }

    /** Contact photos are resolved off the main thread and cached per address. */
    private fun loadContactAvatar(address: String, image: ImageView, wrapper: FrameLayout, bindingKey: String) {
        if (address.isBlank() ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        if (avatarCache.containsKey(address)) {
            avatarCache[address]?.let {
                if (wrapper.tag == bindingKey)
                    Glide.with(applicationContext).load(Uri.parse(it)).circleCrop().into(image)
            }
            return
        }
        Thread {
            var uri: String? = null
            try {
                val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) uri = cursor.getString(0)
                }
            } catch (_: SecurityException) { }
            val resolved = uri
            main.post {
                avatarCache[address] = resolved
                if (image.parent != null && resolved != null && wrapper.tag == bindingKey)
                    Glide.with(applicationContext).load(Uri.parse(resolved)).circleCrop().into(image)
            }
        }.start()
    }

    private fun touch(event: MotionEvent): Boolean {
        val p = headParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hideFlyout()
                snapAnimator?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { addRawMovement(it, event) }
                dragStartX = event.rawX
                dragStartY = event.rawY
                startX = p.x
                startY = p.y
                dragged = false
                withinDismissCatch = false
                headView?.scaleX = .91f
                headView?.scaleY = .91f
                attractionSpring.jump(0.0)
                previousTouchMs = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.let { addRawMovement(it, event) }
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (hypot(dx, dy) >= dp(8)) dragged = true
                if (dragged) {
                    headView?.scaleX = 1f
                    headView?.scaleY = 1f
                    p.x = (startX + dx.toInt()).coerceIn(0, max(0, screenWidth() - dp(79)))
                    p.y = (startY + dy.toInt()).coerceIn(dp(16), max(dp(16), screenHeight() - dp(105)))
                    showCloseTarget()
                    // Decompiled ChatHeadCloseTargetView.A0e: rectangular bottom region.
                    val active = nearCloseTarget(event.rawX, event.rawY)
                    attractionSpring.animateTo(if (active) 1.0 else 0.0)
                    val delta = ((event.eventTime - previousTouchMs).coerceAtLeast(0L)) / 1000.0
                    previousTouchMs = event.eventTime
                    val attraction = attractionSpring.update(delta).coerceIn(0.0, 1.0)
                    // Decompiled RTA.A0J: interpolate dragged center towards dismiss center.
                    val centerX = p.x + dp(30)
                    val centerY = p.y + dp(30)
                    val (screenTargetX, screenTargetY) = dismissCenterOnScreen()
                    val lead = headView
                    val headOrigin = IntArray(2)
                    lead?.getLocationOnScreen(headOrigin)
                    val offsetX = headOrigin[0] - p.x
                    val offsetY = headOrigin[1] - p.y
                    val targetX = screenTargetX - offsetX
                    val targetY = screenTargetY - offsetY
                    p.x = (centerX + (targetX - centerX) * attraction - dp(30)).toInt()
                    p.y = (centerY + (targetY - centerY) * attraction - dp(30)).toInt()
                    updateHead()
                    // Messenger X.Ra9.A01: GOD.performHapticFeedback(0) exactly
                    // once when RTA.A0J changes into C0OD.A01 (close-target
                    // capture state, squared-distance <= 30dp radius).
                    val captured = isInsideCatchRadius(event.rawX, event.rawY)
                    if (captured && !withinDismissCatch) {
                        headView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    withinDismissCatch = captured
                    closeTarget?.scaleX = if (active) 1.18f else 1f
                    closeTarget?.scaleY = if (active) 1.18f else 1f
                    headView?.alpha = if (active) .7f else collapsedStackOpacity
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                withinDismissCatch = false
                headView?.scaleX = 1f
                headView?.scaleY = 1f
                velocity?.let { addRawMovement(it, event) }
                velocity?.computeCurrentVelocity(1000)
                val flingX = velocity?.xVelocity ?: 0f
                val flingY = velocity?.yVelocity ?: 0f
                velocity?.recycle()
                velocity = null
                if (dragged) {
                    val dismiss = isInsideCatchRadius(event.rawX, event.rawY) ||
                        slingToDismiss(event.rawX, event.rawY, flingX, flingY)
                    hideCloseTarget()
                    if (dismiss) dismissCollapsedStack() else snap(flingX, flingY)
                } else expand()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                withinDismissCatch = false
                headView?.scaleX = 1f
                headView?.scaleY = 1f
                velocity?.recycle()
                velocity = null
                hideCloseTarget()
                snap(0f)
                return true
            }
        }
        return false
    }

    /** Use raw screen coordinates; moving the overlay invalidates local event velocity. */
    private fun addRawMovement(tracker: VelocityTracker, source: MotionEvent) {
        val copy = MotionEvent.obtain(source)
        try {
            copy.offsetLocation(source.rawX - source.x, source.rawY - source.y)
            tracker.addMovement(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun snap(flingX: Float, flingY: Float = 0f) {
        val p = headParams ?: return
        val right = when {
            flingX > 500f -> true
            flingX < -500f -> false
            else -> p.x >= (screenWidth() - dp(79)) / 2
        }
        // Dock the 60dp visible circle about 7dp past either display edge.
        // Wrapper is 79dp with the circle inset 4dp; the wrapper must move
        // farther than the icon itself to create the subtle Messenger crop.
        val dest=(if(right) max(0,screenWidth()-dp(79))+dp(26)
                  else -dp(12)).toDouble()
        spring.jump(p.x.toDouble())
        spring.impartVelocity(flingX.toDouble())
        spring.animateTo(dest)
        // Both axes in Messenger's moving-head RUt 40/7 spring family.
        // A fling should retain the measured vertical velocity and damp
        // naturally instead of freezing the head's Y coordinate on release.
        val y = p.y.toDouble()
        verticalSpring.jump(y)
        verticalSpring.impartVelocity(flingY.coerceIn(-dp(4500).toFloat(),
            dp(4500).toFloat()).toDouble())
        verticalSpring.animateTo(y)
        snapAnimator?.cancel()
        var lastTime = 0L
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950
            addUpdateListener {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val delta = if (lastTime == 0L) 0.0 else (now - lastTime) / 1_000_000_000.0
                lastTime = now
                // Small overshoot past the dock edge is intentional: the
                // spring friction should remain visible instead of being
                // hard-clamped as soon as the head reaches the screen edge.
                p.x = spring.update(delta).toInt().coerceIn(-dp(18),
                    max(0,screenWidth()-dp(79))+dp(30))
                p.y = verticalSpring.update(delta).toInt().coerceIn(dp(16),
                    max(dp(16), screenHeight() - dp(105)))
                updateHead()
                if (spring.atRest() && verticalSpring.atRest()) {
                    cancel()
                    savePosition()
                }
            }
            start()
        }
        main.postDelayed({ savePosition() }, 1000L)
    }

    private fun updateHead() {
        updateTrailTargets()
        val view = headView ?: return
        val p = headParams ?: return
        try { windows.updateViewLayout(view, p) } catch (_: IllegalArgumentException) {}
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        closeParams = params(dp(90), dp(90), false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = (screenWidth() - dp(90)) / 2
            y = screenHeight() - dp(130)
        }
        closeTarget = FrameLayout(this).apply {
            background = oval(0xCC33343B.toInt())
            addView(TextView(this@FloatingChatHeadsService).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = 37f
                setTextColor(Color.WHITE)
            }, FrameLayout.LayoutParams(-1, -1))
            alpha = 0f
            windows.addView(this, closeParams)
            animate().alpha(1f).setDuration(120).start()
        }
    }

    /** World-space center of the visible dismiss target, independent of
     * displayMetrics/window inset differences on custom Android ROMs. */
    private fun dismissCenterOnScreen(): Pair<Float, Float> {
        val target = closeTarget
        if (target != null && target.isAttachedToWindow) {
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            return location[0] + target.width/2f to location[1] + target.height/2f
        }
        val lp = closeParams
        return if(lp!=null) lp.x + dp(45).toFloat() to lp.y + dp(45).toFloat()
               else screenWidth()/2f to (screenHeight()-dp(85)).toFloat()
    }

    private fun nearCloseTarget(rawX: Float, rawY: Float): Boolean {
        val (targetX, targetY) = dismissCenterOnScreen()
        // The pointer itself is authoritative while moving: the WindowManager
        // view's location may lag the latest params by one rendered frame.
        if (kotlin.math.abs(rawX-targetX) <= dp(98) &&
            kotlin.math.abs(rawY-targetY) <= dp(110)) return true
        val p = headParams ?: return false
        val view = headView ?: return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + dp(30)
        val cy = loc[1] + dp(30)
        return kotlin.math.abs(cx - targetX) <= dp(98) &&
            kotlin.math.abs(cy - targetY) <= dp(110)
    }

    private fun isInsideCatchRadius(rawX: Float, rawY: Float): Boolean {
        val (tx, ty) = dismissCenterOnScreen()
        if (kotlin.math.hypot(rawX-tx, rawY-ty) <= dp(55)) return true
        val view = headView ?: return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val (targetX, targetY) = dismissCenterOnScreen()
        val dx = loc[0] + dp(30) - targetX
        val dy = loc[1] + dp(30) - targetY
        return dx*dx + dy*dy <= dp(44).toFloat()*dp(44)
    }

    /**
     * Decompiled RTA.A1I:
     * speed >= controller threshold, downward velocity > 0,
     * projected impactX = touchX + vx * (screenHeight - touchY)/vy,
     * abs(impactX - screenWidth/2) < 180dp/2.
     *
     * RTA.A1U resolves resource 0x7f1a0069 (500dp) via getDimensionPixelSize.
     * This produces a density-scaled velocity threshold in px/sec.
     */
    private fun slingToDismiss(rawX: Float, rawY: Float, vx: Float, vy: Float): Boolean {
        if (vy <= 0f || hypot(vx, vy) < dp(500).toFloat()) return false
        val impactX = rawX + vx * ((screenHeight() - rawY) / vy)
        return abs(impactX - screenWidth() / 2f) < dp(90)
    }

    private fun hideCloseTarget() {
        val v = closeTarget ?: return
        closeTarget = null
        v.animate().cancel()
        try { windows.removeViewImmediate(v) } catch (_: IllegalArgumentException) {}
        headView?.alpha = if (expandedStrip != null) 1f else collapsedStackOpacity
    }

    /** Collapsed mode: drag the stack to X to dismiss all visible heads. */
    private fun dismissCollapsedStack() {
        if (expandedStrip != null) return
        snapAnimator?.cancel()
        trailAnimator?.cancel()
        val root=headView
        val trail=trailVisualLayer
        if (root == null) {
            clearAllHeads()
            return
        }
        // Short coordinated shrink: no remaining ghost heads or stale physics.
        trail?.animate()?.cancel()
        root.animate().cancel()
        root.pivotX = root.width/2f
        root.pivotY = root.height/2f
        root.animate().alpha(0f).scaleX(.55f).scaleY(.55f)
            .setDuration(155L).withEndAction { clearAllHeads() }.start()
        trail?.animate()?.alpha(0f)?.scaleX(.90f)?.scaleY(.90f)
            ?.setDuration(155L)?.start()
    }

    /** Common teardown for notification Clear and collapsed X dismissal.
     * Drops *all* physics and overlay state, preserving SMS/MMS data. */
    private fun clearAllHeads() {
        snapAnimator?.cancel(); snapAnimator=null
        trailAnimator?.cancel(); trailAnimator=null
        entryAnimator?.cancel(); entryAnimator=null
        expandedAnimator?.removeAllListeners()
        expandedAnimator?.cancel(); expandedAnimator=null
        hideFlyout()
        hideCloseTarget()
        removeExpandedDragGhost()
        retractingPanel?.let {
            try { windows.removeViewImmediate(it) } catch (_: Exception) {}
        }
        retractingPanel = null
        panel?.let {
            it.animate().cancel()
            try { windows.removeViewImmediate(it) } catch (_: Exception) {}
        }
        panel=null; panelParams=null
        expandedStrip=null; expandedStripParams=null
        closingExpanded=false
        trailSprings.clear()
        followers.clear()
        heads.clear()
        selectedId=-1L
        unreadHeadCount=0
        avatarViews.clear()
        badgeView=null
        headView?.let { try { windows.removeViewImmediate(it) } catch (_: Exception) {} }
        headView=null
        headParams=null
        removeTrailVisualLayer()
    }

    private fun removeHead() {
        if (expandedStrip != null && !closingExpanded) {
            removeExpandedHead(selectedId)
            return
        }
        hideFlyout()
        val hadExpansion = expandedStrip != null
        if (hadExpansion) collapse()
        heads.remove(selectedId)
        selectedId = heads.keys.lastOrNull() ?: -1L
        refreshUnreadHeadCount()
        if (heads.isEmpty()) {
            expandedAnimator?.removeAllListeners()
            expandedAnimator?.cancel()
            expandedAnimator = null
            expandedStrip = null
            closingExpanded = false
            headView?.let { view ->
                try { windows.removeViewImmediate(view) } catch (_: IllegalArgumentException) {}
            }
            headView = null
            headParams = null
            avatarViews.clear()
            badgeView = null
            removeTrailVisualLayer()
            if (!shouldStayArmed()) stopSelf()
        } else showHeads()
    }

    /**
     * Messenger's stack uses RaE while expanded heads use RV3. The latter places
     * the first (newest) head at screen-right and offsets subsequent heads ~68dp
     * leftward with zero vertical separation in portrait.
     */
    private fun orderedHeads(): List<Head> = heads.values.toList().asReversed()

    private fun removeExpandedDragGhost() {
        val ghost = expandedDragGhost
        expandedDragGhost = null
        expandedDragGhostParams = null
        if (ghost != null) {
            ghost.animate().cancel()
            try { windows.removeViewImmediate(ghost) } catch (_: Exception) {}
        }
        expandedDragView?.alpha = 1f
        expandedDragView = null
        expandedDragId = -1L
        expandedDragActive = false
        expandedDragPreviewOrder = emptyList()
    }

    /** The floating touch window clips children to its window bounds, even
     * with clipChildren=false. A separate non-touchable *small* window is the
     * dragged avatar's visual, while its original view owns the gesture. */
    private fun beginExpandedHeadDrag(id: Long) {
        val original = avatarViews[id] ?: return
        val head = heads[id] ?: return
        expandedDragActive = true
        expandedDragView = original
        val pos = IntArray(2)
        original.getLocationOnScreen(pos)
        expandedDragStartWindowX = pos[0]
        expandedDragStartWindowY = pos[1]
        expandedDragStartParamX = pos[0]
        expandedDragStartParamY = pos[1]
        expandedDragLatestDx = 0f
        expandedDragLatestDy = 0f
        refreshAvatarPhotoForHead(head)
        val ghost = makeHeadAvatar(head).apply {
            isClickable = false
            elevation = dp(20).toFloat()
            pivotX = dp(34).toFloat()
            pivotY = dp(36).toFloat()
            scaleX = 1.08f
            scaleY = 1.08f
        }
        val params = params(dp(68), dp(75), false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = pos[0]
            y = pos[1]
        }
        try {
            windows.addView(ghost, params)
            expandedDragGhost = ghost
            expandedDragGhostParams = params
            original.alpha = 0f
            ghost.post {
                if (expandedDragGhost !== ghost) return@post
                // Different Android window types and ROMs can have different
                // top insets. Adjust the ghost to match the source head exactly.
                val actual = IntArray(2)
                ghost.getLocationOnScreen(actual)
                val rawDx = expandedDragStartWindowX - actual[0]
                val rawDy = expandedDragStartWindowY - actual[1]
                expandedDragStartParamX += rawDx
                expandedDragStartParamY += rawDy
                params.x = (expandedDragStartParamX + expandedDragLatestDx).toInt()
                params.y = (expandedDragStartParamY + expandedDragLatestDy).toInt()
                try { windows.updateViewLayout(ghost, params) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            expandedDragActive = false
            expandedDragView = null
            return
        }
        showCloseTarget()
        headView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun expandedHeadNearTarget(dx: Float, dy: Float): Boolean {
        val (targetX,targetY) = dismissCenterOnScreen()
        val cx = expandedDragStartWindowX + dx + dp(34)
        val cy = expandedDragStartWindowY + dy + dp(35)
        return kotlin.math.hypot(cx-targetX,cy-targetY) < dp(76)
    }

    private fun expandedHeadCaught(dx: Float, dy: Float): Boolean {
        val (targetX,targetY) = dismissCenterOnScreen()
        val cx = expandedDragStartWindowX + dx + dp(34)
        val cy = expandedDragStartWindowY + dy + dp(35)
        return kotlin.math.hypot(cx-targetX,cy-targetY) <= dp(50)
    }

    private fun previewExpandedReorder(id: Long, dx: Float) {
        val root = headParams ?: return
        val order=orderedHeads().map { it.id }
        if (id !in order) return
        val all=order.toMutableList()
        all.remove(id)
        val absoluteCenter=expandedDragStartWindowX + dx + dp(34)
        val rootWorld=IntArray(2)
        headView?.getLocationOnScreen(rootWorld)
        val newIndex=(0 until order.size).minByOrNull { i ->
            val slotCenter=rootWorld[0] + expandedWidth()-dp(72)-i*dp(68)+dp(34)
            kotlin.math.abs(slotCenter-absoluteCenter)
        } ?: 0
        all.add(newIndex,id)
        if (expandedDragPreviewOrder == all) return
        expandedDragPreviewOrder=all
        all.forEachIndexed { index, headId ->
            if (headId == id) return@forEachIndexed
            avatarViews[headId]?.let { view ->
                view.animate().cancel()
                view.animate()
                    .translationX((expandedWidth()-dp(72)-index*dp(68)).toFloat())
                    .translationY(0f)
                    .setInterpolator(android.view.animation.PathInterpolator(.2f,.72f,.25f,1f))
                    .setDuration(240L).start()
            }
        }
    }

    private fun commitExpandedOrder() {
        val newOrder=expandedDragPreviewOrder
        if (newOrder.isEmpty()) return
        // LinkedHashMap insertion order is reverse of the on-screen order.
        val items = newOrder.mapNotNull { heads[it] }
        if (items.size != heads.size) return
        heads.clear()
        items.asReversed().forEach { heads[it.id]=it }
        panel?.updateSelectedHeadSlot(orderedHeads().indexOfFirst { it.id == selectedId })
    }

    private fun expandedHeadTouch(id: Long, event: MotionEvent): Boolean {
        if (closingExpanded || expandedStrip == null) return false
        if (expandedAnimator?.isRunning == true) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (expandedDragId != -1L && expandedDragId != id) return true
                expandedDragId = id
                expandedDragActive = false
                expandedDragStartRawX = event.rawX
                expandedDragStartRawY = event.rawY
                expandedDragPreviewOrder = emptyList()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (expandedDragId != id) return true
                val dx=event.rawX-expandedDragStartRawX
                val dy=event.rawY-expandedDragStartRawY
                if (!expandedDragActive && kotlin.math.hypot(dx,dy)>dp(9))
                    beginExpandedHeadDrag(id)
                if (expandedDragActive) {
                    val ghost=expandedDragGhost
                    val lp=expandedDragGhostParams
                    if(ghost!=null && lp!=null) {
                        expandedDragLatestDx=dx
                        expandedDragLatestDy=dy
                        lp.x=(expandedDragStartParamX+dx).toInt()
                        lp.y=(expandedDragStartParamY+dy).toInt()
                        try { windows.updateViewLayout(ghost,lp) } catch (_: Exception) {}
                        val near=expandedHeadNearTarget(dx,dy)
                        ghost.scaleX=if(near) .85f else 1.08f
                        ghost.scaleY=if(near) .85f else 1.08f
                        closeTarget?.scaleX=if(near) 1.18f else 1f
                        closeTarget?.scaleY=if(near) 1.18f else 1f
                    }
                    previewExpandedReorder(id,dx)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (expandedDragId != id) return true
                val dx=event.rawX-expandedDragStartRawX
                val dy=event.rawY-expandedDragStartRawY
                if (!expandedDragActive) {
                    expandedDragId=-1L
                    if (selectedId == id) collapse() else switchExpanded(id)
                    return true
                }
                val dismiss=expandedHeadCaught(dx,dy)
                hideCloseTarget()
                if(dismiss) {
                    expandedDragGhost?.let { ghost ->
                        ghost.animate().alpha(0f).scaleX(.35f).scaleY(.35f)
                            .setDuration(170L).withEndAction {
                                removeExpandedDragGhost()
                                removeExpandedHead(id)
                            }.start()
                    } ?: run {
                        removeExpandedDragGhost()
                        removeExpandedHead(id)
                    }
                } else {
                    commitExpandedOrder()
                    removeExpandedDragGhost()
                    drawExpandedStrip()
                    orderedHeads().forEachIndexed { index, head ->
                        avatarViews[head.id]?.let { view ->
                            view.animate().cancel()
                            view.animate()
                                .translationX((expandedWidth()-dp(72)-index*dp(68)).toFloat())
                                .translationY(0f)
                                .setDuration(300L)
                                .setInterpolator(android.view.animation.OvershootInterpolator(.45f))
                                .start()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideCloseTarget()
                removeExpandedDragGhost()
                positionExpandedHeadsAnimated()
                return true
            }
        }
        return true
    }

    private fun positionExpandedHeadsAnimated() {
        orderedHeads().forEachIndexed { index, head ->
            avatarViews[head.id]?.let { view ->
                view.animate().cancel()
                view.animate().translationX((expandedWidth()-dp(72)-index*dp(68)).toFloat())
                    .translationY(0f).setDuration(280L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(.45f))
                    .start()
            }
        }
    }

    private fun removeExpandedHead(id: Long) {
        if (!heads.containsKey(id)) return
        if (expandedStrip == null) return
        val removedWasSelected=id==selectedId
        heads.remove(id)
        trailSprings.remove(id)
        trailVisualAvatars.remove(id)?.let { trailVisualLayer?.removeView(it) }
        avatarViews.remove(id)?.let { avatar ->
            avatar.animate().cancel()
            (avatar.parent as? android.view.ViewGroup)?.removeView(avatar)
        }
        if (heads.isEmpty()) {
            clearAllHeads()
            if (!shouldStayArmed()) stopSelf()
            return
        }
        if (removedWasSelected) {
            selectedId=orderedHeads().first().id
            panel?.let {
                it.animate().cancel()
                try { windows.removeViewImmediate(it) } catch (_: Exception) {}
            }
            panel=null
            attachConversationPanel()
        }
        headParams?.let { lp ->
            lp.width=expandedWidth()
            lp.x=expandedX()
            try { windows.updateViewLayout(headView,lp) } catch (_: Exception) {}
        }
        drawExpandedStrip()
        positionExpandedHeadsAnimated()
        panel?.updateSelectedHeadSlot(orderedHeads().indexOfFirst{it.id==selectedId})
        refreshUnreadHeadCount()
    }

    private fun expandedWidth(): Int = max(dp(79), dp(72 + ((heads.size.coerceAtMost(5) - 1).coerceAtLeast(0) * 68)))
    private fun expandedX(): Int = max(0, screenWidth() - expandedWidth() - dp(5))
    private fun expandedY(): Int = dp(43)

    private fun drawExpandedStrip() {
        syncAvatarViews()
        val order = orderedHeads().take(5)
        followers.clear()
        val width = expandedWidth()
        for ((i, head) in order.withIndex()) {
            val view = avatarViews[head.id] ?: continue
            view.isClickable = true
            view.setOnClickListener(null)
            view.setOnTouchListener { _, event -> expandedHeadTouch(head.id, event) }
            // No blue selected oval: the conversation pointer already identifies
            // the selected avatar, and an extra dot is visually distracting.
            view.findViewWithTag<View>("active-indicator")?.visibility = View.GONE
            followers.add(Triple(view,
                MessengerSpring(220.0, 15.0, .3, .3),
                MessengerSpring(220.0, 15.0, .3, .3)))
        }
        updateUnreadBadge()
    }

    private fun rebuildExpandedStrip() {
        if (expandedStrip == null) return
        drawExpandedStrip()
        val p = headParams ?: return
        // Only widen on incoming messages; do not replace the window.
        p.width = expandedWidth()
        p.x = expandedX()
        windows.updateViewLayout(headView, p)
        positionExpandedHeads()
    }

    private fun positionExpandedHeads() {
        val width = expandedWidth()
        for ((i, head) in orderedHeads().withIndex()) {
            val view = avatarViews[head.id] ?: continue
            view.translationX = (width - dp(72) - i * dp(68)).toFloat()
            view.translationY = 0f
        }
    }


    /**
     * Continuous expanded-to-dock motion, with no last-frame geometry swap.
     *
     * Previously the expanded window remained full-width throughout collapse,
     * then jumped from (x=screenWidth-expandedWidth, width=expandedWidth)
     * straight to (x=savedDockX, width=79dp) at animation end. Those updates
     * are asynchronous relative to child translations and caused the observed
     * far-left flash. Shrink the very same input window continuously, while
     * each head follows a world-space spring toward its final dock coordinate.
     */
    private fun showTrailVisualsForCollapse() {
        ensureTrailVisualLayer()
        syncTrailVisualAvatars()
        positionTrailVisualAvatars()
        trailVisualLayer?.visibility = View.VISIBLE
        orderedHeads().drop(1).forEach { avatarViews[it.id]?.visibility = View.INVISIBLE }
    }

    private fun animateCollapseInPlace() {
        val root = headView ?: return
        val lp = headParams ?: return
        val right = savedDockX >= screenWidth() / 2
        val avatarSize = dp(79)
        expandedAnimator?.removeAllListeners()
        expandedAnimator?.cancel()
        closingExpanded = true
        trailAnimator?.cancel()
        val widthSpring = MessengerSpring(220.0, 15.0, .3, .3)
        val ySpring = MessengerSpring(220.0, 15.0, .3, .3)
        widthSpring.jump(lp.width.toDouble())
        widthSpring.animateTo(avatarSize.toDouble())
        ySpring.jump(lp.y.toDouble())
        ySpring.animateTo(savedDockY.toDouble())

        val order = orderedHeads()
        val headMotion = order.mapIndexedNotNull { index, head ->
            val view = avatarViews[head.id] ?: return@mapIndexedNotNull null
            val worldX = lp.x + view.translationX
            val targetX = savedDockX + dp(if (right) 7 - index * 5 else 3 + index * 5)
            val worldY = lp.y + view.translationY
            val targetY = savedDockY + dp(2)
            val sx = MessengerSpring(220.0, 15.0, .3, .3)
            val sy = MessengerSpring(220.0, 15.0, .3, .3)
            sx.jump(worldX.toDouble())
            sy.jump(worldY.toDouble())
            sx.animateTo(targetX.toDouble())
            sy.animateTo(targetY.toDouble())
            Triple(view, sx, sy)
        }
        // Keep the follower drawings alive through the collapsing spring.
        // The originals remain attached to the stable main head window, but
        // are visually replaced by their identical unclipped mirrors.
        showTrailVisualsForCollapse()
        var previousFrameNanos = 0L
        expandedAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Only a safety ceiling; actual end is the springs' rest condition.
            duration = 1400L
            addUpdateListener {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val delta = if (previousFrameNanos == 0L) 0.0
                    else (now - previousFrameNanos) / 1.0e9
                previousFrameNanos = now
                val nextWidth = widthSpring.update(delta).toInt()
                    .coerceIn(avatarSize, max(avatarSize, expandedWidth()))
                lp.width = nextWidth
                lp.x = if (right) savedDockX - (nextWidth - avatarSize) else savedDockX
                lp.y = ySpring.update(delta).toInt()
                for ((view, sx, sy) in headMotion) {
                    view.translationX = (sx.update(delta) - lp.x).toFloat()
                    view.translationY = (sy.update(delta) - lp.y).toFloat()
                }
                positionTrailVisualAvatars()
                try { windows.updateViewLayout(root, lp) }
                catch (_: IllegalArgumentException) {}
                if (widthSpring.atRest() && ySpring.atRest() &&
                    headMotion.all { it.second.atRest() && it.third.atRest() }) {
                    cancel()
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (expandedAnimator !== this@apply) return
                    // By settling the bounds and child positions in every
                    // frame, this final correction is subpixel, not a 200dp jump.
                    lp.width = avatarSize
                    lp.height = avatarSize
                    lp.x = savedDockX
                    lp.y = savedDockY
                    order.forEachIndexed { i, head ->
                        avatarViews[head.id]?.apply {
                            translationX = dp(if (right) 7 - i * 5 else 3 + i * 5).toFloat()
                            translationY = dp(2).toFloat()
                            setOnClickListener(null)
                            isClickable = true
                            setOnTouchListener { _, event ->
                                if (expandedStrip == null && !closingExpanded) touch(event)
                                else false
                            }
                            findViewWithTag<View>("active-indicator")?.visibility = View.GONE
                        }
                    }
                    try { windows.updateViewLayout(root, lp) }
                    catch (_: IllegalArgumentException) {}
                    expandedStrip = null
                    expandedStripParams = null
                    closingExpanded = false
                    trailSprings.clear()
                    setupTrailingHeads()
                    showTrailVisuals()
                    updateUnreadBadge()
                    expandedAnimator = null
                }
            })
            start()
        }
    }

    private fun animateExpandedStrip(x: Int, y: Int, closing: Boolean) {
        val root = headView ?: return
        val params = headParams ?: return
        expandedAnimator?.removeAllListeners()
        expandedAnimator?.cancel()
        closingExpanded = closing
        val right = savedDockX >= screenWidth()/2
        val width = expandedWidth()
        val startX = if (closing) params.x
            else if (right) savedDockX - width + dp(79) else savedDockX
        val startY = if (closing) params.y else savedDockY
        params.width = width
        params.height = dp(79)
        if (!closing) {
            params.x = startX
            params.y = startY
            windows.updateViewLayout(root, params)
        }
        // A stronger launch for the opening rocket motion; return remains
        // handled by the existing independent landing springs.
        expandedSpringX = MessengerSpring(if (closing) 220.0 else 190.0,
            if (closing) 15.0 else 13.0, .3, .3)
        expandedSpringY = MessengerSpring(if (closing) 220.0 else 190.0,
            if (closing) 15.0 else 13.0, .3, .3)
        expandedSpringX.jump(params.x.toDouble())
        expandedSpringY.jump(params.y.toDouble())
        expandedSpringX.animateTo(x.toDouble())
        expandedSpringY.animateTo(y.toDouble())
        val order = orderedHeads()
        val springs = order.mapIndexedNotNull { i, head ->
            val view = avatarViews[head.id] ?: return@mapIndexedNotNull null
            val colX = (if (right) width-dp(72)-i*dp(5) else dp(3+i*5)).toDouble()
            val expandedX = (width-dp(72)-i*dp(68)).toDouble()
            val xSpring = MessengerSpring(220.0, 15.0, .3, .3)
            val ySpring = MessengerSpring(220.0, 15.0, .3, .3)
            xSpring.jump(if (closing) expandedX else colX)
            ySpring.jump(0.0)
            xSpring.animateTo(if (closing) colX else expandedX)
            ySpring.animateTo(0.0)
            view.translationX = xSpring.value.toFloat()
            view.translationY = 0f
            Triple(view, xSpring, ySpring)
        }
        var lastNanos = 0L
        expandedAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (closing) 540L else 630L
            addUpdateListener {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val delta = if (lastNanos == 0L) 0.0 else (now-lastNanos)/1.0e9
                lastNanos = now
                params.x = expandedSpringX.update(delta).toInt()
                params.y = expandedSpringY.update(delta).toInt()
                for ((view, sx, sy) in springs) {
                    view.translationX = sx.update(delta).toFloat()
                    view.translationY = sy.update(delta).toFloat()
                }
                try { windows.updateViewLayout(root, params) } catch (_: Exception) {}
                if (!closing) {
                    val travelY = abs(startY - y).coerceAtLeast(1)
                    val travelX = abs(startX - x).coerceAtLeast(1)
                    // X may travel only a few pixels on a right-edge dock.
                    // Do not let that tiny distance reveal the entire panel
                    // before the head has actually rocketed upward.
                    val progress = (if (travelY > dp(36))
                        1f - abs(params.y - y).toFloat() / travelY
                    else 1f - abs(params.x - x).toFloat() / travelX
                    ).coerceIn(0f, 1f)
                    panel?.setGenieProgress(progress)
                }
                if (expandedSpringX.atRest() && expandedSpringY.atRest() &&
                    springs.all { it.second.atRest() }) cancel()
            }
            addListener(object: android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (expandedAnimator !== this@apply) return
                    if (closing) {
                        // SAME ROOT AND AVATARS. Resize only the WindowManager
                        // bounds, with a screen-coordinate-preserving swap.
                        params.width = dp(79)
                        params.height = dp(79)
                        params.x = savedDockX
                        params.y = savedDockY
                        try { windows.updateViewLayout(root, params) } catch (_: Exception) {}
                        for ((i, head) in order.withIndex()) {
                            avatarViews[head.id]?.apply {
                                translationX = dp(if (right) 7-i*5 else 3+i*5).toFloat()
                                translationY = dp(2).toFloat()
                                isClickable = false
                                setOnClickListener(null)
                                findViewWithTag<View>("active-indicator")?.visibility = View.GONE
                            }
                        }
                        expandedStrip = null
                        expandedStripParams = null
                        closingExpanded = false
                        trailSprings.clear()
                        setupTrailingHeads()
                        updateUnreadBadge()
                    } else {
                        positionExpandedHeads()
                        panel?.finishGenie()
                    }
                    expandedAnimator = null
                }
            })
            start()
        }
    }

    private fun attachConversationPanel(entrance: Boolean = false) {
        val head = heads[selectedId] ?: return
        Thread {
            try { markThreadMessagesRead(head.id) } catch (_: Exception) {}
            main.post { refreshUnreadHeadCount() }
        }.start()
        val overlay = OverlayConversationPanel(this, head,
            onCollapse = { collapse() },
            onClose = { removeHead() },
            onSelect = { id -> switchExpanded(id) },
            heads = orderedHeads()
        )
        panel = overlay
        if (entrance) overlay.beginGenie(
            (savedDockY + dp(79)).toFloat(),
            (savedDockX + dp(40)).toFloat()
        )
        // API 30+ tracks animated IME insets in a stable overlay window.
        // Older Android versions retain the platform adjustResize fallback.
        panelParams = params(-1, -1, true)
        try {
            windows.addView(overlay, panelParams)
            // In WindowManager the last added APPLICATION_OVERLAY sits in
            // front. When switching conversations reattach the same head root
            // (without recreating its children) to keep buttons clickable.
            headView?.let { root ->
                val params = headParams
                if (params != null) {
                    try {
                        windows.removeViewImmediate(root)
                        windows.addView(root, params)
                    } catch (_: Exception) {}
                }
            }
            if (!entrance) overlay.finishGenie()
            if (!observing) {
                contentResolver.registerContentObserver(android.net.Uri.parse("content://sms"), true, observer)
                observing = true
            }
        } catch (_: Exception) {
            panel = null
        }
    }

    private fun switchExpanded(id: Long) {
        if (closingExpanded) return
        if (id == selectedId) { collapse(); return }
        if (!heads.containsKey(id)) return
        selectedId = id
        panel?.let {
            it.animate().cancel()
            try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) {}
        }
        panel = null
        attachConversationPanel()
        // Keep the SAME head window; refreshing indicators doesn't reattach it.
        drawExpandedStrip()
    }

    private fun expand() {
        trailAnimator?.cancel()
        trailAnimator = null
        hideTrailVisuals()
        entryAnimator?.cancel()
        if (expandedStrip != null || heads.isEmpty()) return
        hideFlyout()
        val dock = headParams ?: return
        savedDockX = dock.x
        savedDockY = dock.y
        expandedStrip = headView
        expandedStripParams = dock
        drawExpandedStrip()
        attachConversationPanel(entrance = true)
        // attachConversationPanel() already places the stable head root in
        // front. There is no additional remove/re-add during this transition.
        animateExpandedStrip(expandedX(), expandedY(), closing = false)
    }

    private fun collapse() {
        if (closingExpanded) return
        hideFlyout()
        val old = panel
        panel = null
        if (observing) {
            contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        // Do not fly the avatar home until the entire conversation has
        // narrowed and disappeared upward into its underside.
        if (old != null && expandedStrip != null) {
            closingExpanded = true
            retractingPanel = old
            old.retractGenie {
                if (retractingPanel === old) {
                    retractingPanel = null
                    try { windows.removeViewImmediate(old) } catch (_: IllegalArgumentException) {}
                    animateCollapseInPlace()
                }
            }
        } else {
            if (old != null) try { windows.removeViewImmediate(old) } catch (_: Exception) {}
            if (expandedStrip != null) animateCollapseInPlace()
        }
        if (heads.isEmpty() && !shouldStayArmed()) stopSelf()
    }

    /**
     * Messenger's RaK flyout is a *separate* temporary window, not the conversation.
     * RTA.A0t positions it on the open side of the collapsed stack, and RaK
     * initializes A08 = 5000ms. It is suppressed on keyguard and screen-off.
     */
    private fun showFlyout(preview: String) {
        hideFlyout()
        if (expandedStrip != null) return
        val keyguard = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (keyguard.isKeyguardLocked || !power.isInteractive) return
        val head = heads[selectedId] ?: return
        val dock = headParams ?: return
        // APK RaK uses a side-specific speech-bubble mask. Draw an independent
        // pointer instead of redistributing Meta's proprietary drawable.
        val width = dp(240)
        val tailWidth = dp(12)
        val right = dock.x >= screenWidth()/2
        val flyoutRoot = FrameLayout(this)
        val textBox = TextView(this).apply {
            text = head.title + "\n" + preview.ifBlank { "New message" }
            textSize = 14f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF34363B.toInt())
            }
        }
        flyoutRoot.addView(textBox, FrameLayout.LayoutParams(width-tailWidth, -2).apply {
            leftMargin = if (right) 0 else tailWidth
        })
        val pointer = object : View(this) {
            private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF34363B.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = getWidth().toFloat()
                val h = getHeight().toFloat()
                val outline = android.graphics.Path().apply {
                    if (right) {
                        moveTo(0f, 0f)
                        lineTo(w, h/2f)
                        lineTo(0f, h)
                    } else {
                        moveTo(w, 0f)
                        lineTo(0f, h/2f)
                        lineTo(w, h)
                    }
                    close()
                }
                canvas.drawPath(outline, ink)
            }
        }
        flyoutRoot.addView(pointer, FrameLayout.LayoutParams(tailWidth, dp(22), Gravity.CENTER_VERTICAL).apply {
            leftMargin = if (right) width-tailWidth else 0
        })
        val p = params(width, -2, false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = if (right) max(0, dock.x - width - dp(6))
                else min(screenWidth()-width, dock.x + dp(80))
            y = dock.y + dp(8)
        }
        try {
            windows.addView(flyoutRoot, p)
        } catch (_: Exception) { return }
        flyout = flyoutRoot
        flyoutParams = p
        flyoutAnimator?.cancel()
        flyoutRoot.alpha = 0f
        flyoutRoot.scaleX = .75f
        flyoutRoot.pivotX = if (right) width.toFloat() else 0f
        val alphaSpring = MessengerSpring(150.0, 9.0, .001, .001)
        val scaleSpring = MessengerSpring(150.0, 9.0, .001, .001)
        alphaSpring.jump(0.0)
        alphaSpring.animateTo(1.0)
        scaleSpring.jump(.75)
        scaleSpring.animateTo(1.0)
        var lastNanos = 0L
        flyoutAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650L
            addUpdateListener {
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                val delta = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1.0e9
                lastNanos = now
                flyoutRoot.alpha = alphaSpring.update(delta).toFloat().coerceIn(0f, 1f)
                flyoutRoot.scaleX = scaleSpring.update(delta).toFloat()
                if (alphaSpring.atRest() && scaleSpring.atRest()) cancel()
            }
            start()
        }
        val token = ++flyoutGeneration
        main.postDelayed({
            if (flyoutGeneration == token && flyout === flyoutRoot)
                hideFlyout()
        }, 5000L)
    }

    private fun hideFlyout() {
        flyoutGeneration++
        flyoutAnimator?.cancel()
        flyoutAnimator = null
        val old = flyout ?: return
        flyout = null
        flyoutParams = null
        old.animate().cancel()
        try { windows.removeViewImmediate(old) } catch (_: IllegalArgumentException) {}
    }

    private fun savePosition() {
        val p = headParams ?: return
        val dockRight = p.x >= max(0, screenWidth() - dp(79)) / 2
        val fraction = (p.y.toFloat() / max(1, screenHeight() - dp(79))).coerceIn(0f, 1f)
        getSharedPreferences(POSITION_PREFS, MODE_PRIVATE).edit()
            .putBoolean("right", dockRight).putFloat("yFraction", fraction).apply()
    }

    override fun onDestroy() {
        if (systemReceiverRegistered) {
            try { unregisterReceiver(systemNavigationReceiver) } catch (_: Exception) {}
            systemReceiverRegistered = false
        }
        conversationAvatarPrefs.unregisterOnSharedPreferenceChangeListener(avatarPreferenceListener)
        removeExpandedDragGhost()
        trailAnimator?.cancel()
        trailAnimator = null
        entryAnimator?.cancel()
        hideFlyout()
        // Teardown must be immediate. Starting a collapse spring here would
        // leave live WindowManager overlays after the service has stopped.
        closingExpanded = false
        expandedAnimator?.removeAllListeners()
        expandedAnimator?.cancel()
        expandedAnimator = null
        retractingPanel?.let {
            try { windows.removeViewImmediate(it) } catch (_: Exception) {}
        }
        retractingPanel = null
        panel?.let {
            it.animate().cancel()
            try { windows.removeViewImmediate(it) } catch (_: Exception) {}
        }
        panel = null
        if (observing) {
            contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        expandedStrip = null
        expandedStripParams = null
        followers.clear()
        snapAnimator?.cancel()
        velocity?.recycle()
        hideCloseTarget()
        headView?.let { try { windows.removeViewImmediate(it) } catch (_: Exception) {} }
        headView = null
        removeTrailVisualLayer()
        try { contentResolver.unregisterContentObserver(unreadObserver) } catch (_: Exception) {}
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    fun avatarColor(id: Long): Int {
        val palette = intArrayOf(0xFF377DFF.toInt(), 0xFF6A5AE0.toInt(), 0xFF008E85.toInt(), 0xFFAB5A97.toInt())
        return palette[((id xor (id ushr 32)) and Long.MAX_VALUE).rem(palette.size.toLong()).toInt()]
    }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    companion object {
        @Volatile private var activeService: java.lang.ref.WeakReference<FloatingChatHeadsService>? = null

        /** Direct in-process handoff if this service is already running. */
        fun dispatchToRunning(intent: Intent): Boolean {
            val service = activeService?.get() ?: return false
            if (!service.foregroundStarted || !service.shouldStayArmed() ||
                !Settings.canDrawOverlays(service)) return false
            service.main.post {
                if (activeService?.get() === service) service.onStartCommand(intent, 0, 0)
            }
            return true
        }

        const val ACTION_REMOVE_SAFE = "com.jacqueb.messages.chatheads.REMOVE_SAFE"
        const val ACTION_REMOVE_MUTED = "com.jacqueb.messages.chatheads.REMOVE_MUTED"
        const val ACTION_ADD = "me.jacqueb.messages.chatheads.ADD"
        const val ACTION_STOP = "me.jacqueb.messages.chatheads.STOP"
        const val ACTION_ARM = "me.jacqueb.messages.chatheads.ARM"
        const val ACTION_DISARM = "me.jacqueb.messages.chatheads.DISARM"
        const val ACTION_CLEAR = "me.jacqueb.messages.chatheads.CLEAR"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_EXPAND = "expand_on_tap"
        const val EXTRA_INCOMING = "incoming_event"
        const val EXTRA_PREVIEW = "incoming_preview"
        private const val CHANNEL = "overlay_chat_heads"
        private const val HEAD_NOTIFICATION = 581
        private const val POSITION_PREFS = "overlay_head_position"
    }
}