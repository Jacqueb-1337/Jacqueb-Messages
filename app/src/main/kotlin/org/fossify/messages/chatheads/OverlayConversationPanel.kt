package org.fossify.messages.chatheads

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.animation.ValueAnimator
import android.view.WindowInsetsAnimation
import android.view.ViewTreeObserver
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getThreadParticipants
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.ConversationCustomization
import org.fossify.messages.activities.ConversationDetailsActivity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Telephony
import android.view.Gravity
import android.view.WindowInsets
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import org.fossify.messages.messaging.ConversationDrafts
import org.fossify.messages.extensions.getSmsDraft
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.theming.UiMotion
import org.fossify.messages.messaging.PendingMmsDownloads
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.messaging.sendMessageCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Focusable, in-overlay conversation (not an Android Activity or native bubble).
 * SMS list uses the system Telephony provider and SMS sending delegates to the existing
 * Fossify transport; MMS and group recipient actions can open the full conversation.
 */
class OverlayConversationPanel(
    private val host: FloatingChatHeadsService,
    private val head: FloatingChatHeadsService.Head,
    private val onCollapse: () -> Unit,
    private val onClose: () -> Unit,
    private val onSelect: (Long) -> Unit,
    heads: List<FloatingChatHeadsService.Head>
) : FrameLayout(host) {
    private val queryVersion = AtomicInteger()
    // The full MMS query can take longer than the periodic refresh interval.
    // Never obsolete an unfinished query every 1.6 seconds.
    private val refreshInFlight = AtomicBoolean(false)
    private var selectedIndex = heads.indexOfFirst { it.id == head.id }.coerceAtLeast(0)
    // Keep every vertical point in the expanded panel's geometry tied to
    // the card edge. Expanded heads themselves remain at their fixed Y.
    private val cardTopDp = 93
    private val pointerTopDp get() = cardTopDp - 12
    private val genieSourceTopDp get() = cardTopDp - 13

    private var selectedNotch: View? = null
    private var motionCard: View? = null
    private var composeRow: View? = null
    private var genieFraction: Float? = null
    private var genieAnchorX = 0f
    private var genieStartY = 0f
    private var genieAnimator: ValueAnimator? = null
    // Capture the live conversation once and deform its actual pixels into the
    // head. A clip-path alone merely cut off static messages during collapse.
    private var genieSnapshot: Bitmap? = null
    private val genieSourceRect = Rect()
    private val genieDestinationRect = RectF()
    private val geniePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var imeVisible: Boolean? = null
    private val insetLocation = IntArray(2)
    private var insetDisplayBottom = 0
    private var imeAnimating = false
    private var lastAppliedImeInsets: WindowInsets? = null
    private var currentImeInsets: WindowInsets? = null
    private var previousEdge = 0f
    private var previousEdgeTime = 0L
    private var imeTravel = 0f
    private var landingVelocity = 0f
    private var keyboardSpring: androidx.dynamicanimation.animation.SpringAnimation? = null
    private val messages = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(10))
    }
    private val scroller = object : ScrollView(host) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // The keyboard resizes the viewport from the BOTTOM. Keep the
            // distance between the last visible message and that edge constant,
            // instead of preserving the old absolute scrollY (which leaves the
            // last message buried underneath the keyboard).
            val bottomGap = if (oldh > 0) {
                (messages.height - oldh - scrollY).coerceAtLeast(0)
            } else 0
            super.onSizeChanged(w, h, oldw, oldh)
            if (oldh > 0 && h > 0 && h != oldh) {
                alignScrollAfterLayout(bottomGap)
            }
        }
    }.apply {
        isFillViewport = true
        clipToPadding = false
        addView(messages)
    }
    private var pendingScrollAlignment: ViewTreeObserver.OnPreDrawListener? = null
    private var scrollToBottomAfterSend = false
    private var lastSendRequestAt = 0L
    private val input = EditText(host).apply {
        hint = "Message"
        textSize = 16f
        maxLines = 4
        setSingleLine(false)
        setTextColor(if (night()) Color.WHITE else 0xFF202024.toInt())
        setHintTextColor(if (night()) 0xFF9A9AA1.toInt() else 0xFF777781.toInt())
        background = rounded(if (night()) 0xFF303034.toInt() else 0xFFF2F2F4.toInt(), 22)
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    private val send = TextView(host).apply {
        text = "➤"
        contentDescription = "Send SMS"
        gravity = Gravity.CENTER
        textSize = 24f
        setTextColor(Color.WHITE)
        background = rounded(0xFF1674F7.toInt(), 24)
    }
    @Volatile private var disposed = false
    private var applyingSharedDraft = false
    private val sharedDraftListener: (String) -> Unit = { latest ->
        if (!disposed && input.text.toString() != latest) {
            applyingSharedDraft = true
            input.setText(latest)
            input.setSelection(latest.length)
            applyingSharedDraft = false
        }
    }
    @Volatile private var groupConversation = false
    @Volatile private var resolvedRecipients: List<String> = emptyList()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var lastRenderedRows: List<SmsRow>? = null
    // Telephony observer doesn't track all Room cache writes. Poll only while
    // the floating conversation is visible, with bounded 80-message results,
    // and avoid rebuilding message views if contents did not change.
    private val refreshTick = object : Runnable {
        override fun run() {
            if (disposed || !isAttachedToWindow) return
            refreshMessages()
            refreshHandler.postDelayed(this, 1600L)
        }
    }

    init {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        if (Build.VERSION.SDK_INT >= 30) {
            // A stable overlay window plus animated insets lets the bottom edge
            // follow the IME itself. No delayed ripple or guessed duration.
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                    cancelKeyboardImpact()
                    insetDisplayBottom = 0
                    imeAnimating = true
                    previousEdge = (height - paddingBottom).toFloat()
                    previousEdgeTime = SystemClock.uptimeMillis()
                    imeTravel = 0f
                    landingVelocity = 0f
                }
                override fun onProgress(
                    insets: WindowInsets,
                    runningAnimations: MutableList<WindowInsetsAnimation>
                ): WindowInsets {
                    if (runningAnimations.any { it.typeMask and WindowInsets.Type.ime() != 0 })
                        updateKeyboardEdge(insets, trackVelocity = true)
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                    imeAnimating = false
                    lastAppliedImeInsets?.let { updateKeyboardEdge(it) }
                    // Impact belongs to this completed physical travel only.
                    if (imeTravel > dp(40)) playKeyboardImpact(landingVelocity)
                }
            })
            setOnApplyWindowInsetsListener { _, insets ->
                lastAppliedImeInsets = insets
                if (!imeAnimating) updateKeyboardEdge(insets)
                insets
            }
        }
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (night()) 0xFF151517.toInt() else Color.WHITE, 20)
            elevation = dp(14).toFloat()
            clipToOutline = true
        }
        motionCard = card
        addView(card, LayoutParams(-1, -1).apply {
            // Keep the conversation visually attached to the expanded heads.
            // The previous 109dp offset left an oversized empty band.
            topMargin = dp(cardTopDp)
            leftMargin = dp(9)
            rightMargin = dp(9)
            bottomMargin = dp(12)
        })
        // Native content pointer, drawn as a triangle from the selected
        // expanded chat head to the panel. It moves with selection by virtue
        // of rebuilding only the panel when the active thread changes.
        val notchWidth = dp(28)
        val notch = object : View(host) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (night()) 0xFF151517.toInt() else Color.WHITE
                style = Paint.Style.FILL
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val triangle = Path().apply {
                    moveTo(width / 2f, 0f)
                    lineTo(width.toFloat(), height.toFloat())
                    lineTo(0f, height.toFloat())
                    close()
                }
                canvas.drawPath(triangle, paint)
            }
        }
        val selectedHeadCenter = resources.displayMetrics.widthPixels - dp(43 + 68 * selectedIndex)
        selectedNotch = notch
        addView(notch, LayoutParams(notchWidth, dp(15)).apply {
            leftMargin = (selectedHeadCenter - notchWidth / 2)
                .coerceIn(dp(15), resources.displayMetrics.widthPixels - notchWidth - dp(15))
            // Stay 12dp above the panel, overlapping the card by 3dp.
            topMargin = dp(pointerTopDp)
        })
        notch.bringToFront()
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(row, LinearLayout.LayoutParams(-1, dp(67)))
        row.addView(control("‹", "Collapse floating chat") { onCollapse() })
        val titleColumn = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(label(head.title, 17f, true))
        titleColumn.contentDescription="Conversation settings for "+head.title
        UiMotion.press(titleColumn,.97f)
        titleColumn.setOnClickListener {
            host.startActivity(Intent(host,ConversationDetailsActivity::class.java).apply{
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(THREAD_ID,head.id)
            })
        }
        titleColumn.addView(label("SMS · MMS · Tap name for settings", 11f, false).apply { alpha = .58f })
        row.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        row.addView(control("↗", "Open full conversation") { openFullConversation() })
        row.addView(control("×", "Dismiss chat head") { onClose() })

        card.addView(View(host).apply {
            setBackgroundColor(if (night()) 0xFF333338.toInt() else 0xFFEDEDF0.toInt())
        }, LinearLayout.LayoutParams(-1, dp(1)))
        card.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val compose = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(10), dp(10), dp(13))
        }
        compose.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        compose.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
            leftMargin = dp(8)
        })
        composeRow = compose
        card.addView(compose)
        UiMotion.press(send, .91f)
        send.setOnClickListener { submitMessage() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!applyingSharedDraft)
                    ConversationDrafts.edit(host, head.id, s?.toString().orEmpty())
            }
        })
        ConversationDrafts.read(host, head.id)?.let(sharedDraftListener)
        refreshMessages()
    }

    /** A shared, curved reveal: the head moves first and the conversation
     * extrudes below it. Retraction funnels the content upward into the head.
     * The actual message views stay alive, so drafts and scroll position survive. */
    fun beginGenie(startY: Float, headCenterX: Float) {
        cancelKeyboardImpact()
        genieAnimator?.cancel()
        releaseGenieSnapshot()
        genieStartY = startY
        genieAnchorX = headCenterX
        setGenieProgress(0f)
    }

    fun setGenieProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        genieFraction = if (p >= .999f) null else p
        if (genieFraction == null) releaseGenieSnapshot()
        background?.alpha = (255f * p).toInt()
        invalidate()
    }

    fun finishGenie() = setGenieProgress(1f)

    fun retractGenie(onAbsorbed: () -> Unit) {
        cancelKeyboardImpact()
        genieAnimator?.cancel()
        releaseGenieSnapshot()
        // The avatar stays parked above the funnel until every pixel is inside.
        genieAnchorX = resources.displayMetrics.widthPixels - dp(43 + 68 * selectedIndex).toFloat()
        genieStartY = dp(pointerTopDp).toFloat()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setGenieProgress(0f)
            onAbsorbed()
            return
        }
        genieAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 310L
            interpolator = AccelerateInterpolator(1.35f)
            addUpdateListener { setGenieProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (genieAnimator !== this@apply) return
                    genieAnimator = null
                    setGenieProgress(0f)
                    onAbsorbed()
                }
            })
            start()
        }
    }

    private fun releaseGenieSnapshot() {
        val old = genieSnapshot ?: return
        genieSnapshot = null
        // The render thread may still be presenting the previous warped frame.
        // Delay release slightly to avoid recycling its backing texture in use.
        refreshHandler.postDelayed({ if (!old.isRecycled) old.recycle() }, 180L)
    }

    private fun obtainGenieSnapshot(): Bitmap? {
        genieSnapshot?.takeUnless { it.isRecycled }?.let { return it }
        if (width <= 0 || height <= 0) return null
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Only the actual conversation/pointer is captured. The backdrop
            // remains a separate, inexpensive fading background.
            try {
                super.dispatchDraw(Canvas(bitmap))
            } catch (_: RuntimeException) {
                // Glide may provide hardware-only bitmaps; a software bitmap
                // canvas cannot read those. Fall back to a live transform.
                bitmap.recycle()
                return null
            }
            genieSnapshot = bitmap
            bitmap
        } catch (_: OutOfMemoryError) {
            releaseGenieSnapshot()
            null
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val progress = genieFraction
        if (progress == null) {
            super.dispatchDraw(canvas)
            return
        }
        if (width <= 0 || height <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        val p = progress.coerceIn(.001f, 1f)
        val bitmap = obtainGenieSnapshot()
        if (bitmap == null) {
            // Still transform the live content on memory-constrained devices.
            val save = canvas.save()
            canvas.translate(genieAnchorX * (1f - p), genieStartY * (1f - p))
            canvas.scale(p, p)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
            return
        }

        // Piecewise affine image warp approximates the macOS Genie transform.
        // Unlike a changing mask, EVERY horizontal row physically travels up
        // and becomes narrower as the chat is absorbed underneath its avatar.
        // The top converges more sharply than the bottom, forming the funnel.
        // Genie warp must start at the same visual boundary as the pointer.
        val sourceTop = dp(genieSourceTopDp).coerceIn(0, height - 1)
        val sourceHeight = height - sourceTop
        val mouthY = genieStartY * (1f - p) + sourceTop * p
        val compressedHeight = sourceHeight * p.pow(1.12f)
        val centerX = genieAnchorX * (1f - p) + width * .5f * p
        val pieceHeight = dp(19).coerceAtLeast(14)
        var sourceY = sourceTop
        while (sourceY < height) {
            val nextY = (sourceY + pieceHeight).coerceAtMost(height)
            val midFraction = ((sourceY + nextY) * .5f - sourceTop) / sourceHeight
            val taper = p.pow(1.45f - .93f * midFraction)
            val halfWidth = width * .5f * taper
            val from = (sourceY - sourceTop).toFloat() / sourceHeight
            val to = (nextY - sourceTop).toFloat() / sourceHeight
            genieSourceRect.set(0, sourceY, width, nextY)
            genieDestinationRect.set(centerX - halfWidth, mouthY + compressedHeight * from,
                centerX + halfWidth, mouthY + compressedHeight * to + .7f)
            canvas.drawBitmap(bitmap, genieSourceRect, genieDestinationRect, geniePaint)
            sourceY = nextY
        }
    }

    /** Insets are measured from the display bottom; subtract any part already
     * excluded by WindowManager so navigation bars never get counted twice. */
    private fun updateKeyboardEdge(insets: WindowInsets, trackVelocity: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30) return
        currentImeInsets = insets
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        imeVisible = ime > nav + dp(56)
        if (height == 0) return
        getLocationOnScreen(insetLocation)
        if (insetDisplayBottom == 0) {
            val wm = host.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            insetDisplayBottom = wm.currentWindowMetrics.bounds.bottom
        }
        val excluded = (insetDisplayBottom - insetLocation[1] - height).coerceAtLeast(0)
        val extra = (maxOf(ime, nav) - excluded).coerceAtLeast(0)
            .coerceAtMost((height - dp(cardTopDp + 12)).coerceAtLeast(0))
        if (paddingBottom != extra) setPadding(paddingLeft, paddingTop, paddingRight, extra)
        if (trackVelocity) {
            val now = SystemClock.uptimeMillis()
            val edge = (height - extra).toFloat()
            val delta = edge - previousEdge
            val dt = now - previousEdgeTime
            imeTravel += kotlin.math.abs(delta)
            if (dt in 1..80 && kotlin.math.abs(delta) > .5f)
                landingVelocity = (delta * 1000f / dt).coerceIn(-dp(160).toFloat(), dp(160).toFloat())
            previousEdge = edge
            previousEdgeTime = now
        }
    }

    private fun cancelKeyboardImpact() {
        keyboardSpring?.cancel()
        keyboardSpring = null
        motionCard?.translationY = 0f
        selectedNotch?.translationY = 0f
    }

    private fun playKeyboardImpact(velocity: Float) {
        if (!isAttachedToWindow || genieFraction != null || !UiMotion.enabled() ||
            kotlin.math.abs(velocity) < dp(12)) return
        val value = androidx.dynamicanimation.animation.FloatValueHolder(0f)
        keyboardSpring = androidx.dynamicanimation.animation.SpringAnimation(value).apply {
            spring = androidx.dynamicanimation.animation.SpringForce(0f).apply {
                stiffness = 850f
                dampingRatio = .68f
            }
            setStartVelocity(velocity)
            setMinimumVisibleChange(.2f)
            addUpdateListener { _, position, _ ->
                motionCard?.translationY = position
                selectedNotch?.translationY = position
            }
            addEndListener { _, _, _, _ ->
                motionCard?.translationY = 0f
                selectedNotch?.translationY = 0f
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        insetDisplayBottom = 0
        if (Build.VERSION.SDK_INT >= 30) currentImeInsets?.let { updateKeyboardEdge(it) }
    }

    /** Animate the conversation pointer when expanded head order changes
     * without recreating the panel or discarding the user's in-progress draft. */
    fun updateSelectedHeadSlot(index: Int) {
        if (index < 0 || index == selectedIndex) return
        selectedIndex = index
        val notch = selectedNotch ?: return
        val layout = notch.layoutParams as? FrameLayout.LayoutParams ?: return
        val oldLeft = layout.leftMargin
        val newCenter = resources.displayMetrics.widthPixels - dp(43 + 68 * selectedIndex)
        val newLeft = (newCenter - dp(14))
            .coerceIn(dp(15), resources.displayMetrics.widthPixels - dp(43))
        notch.animate().cancel()
        layout.leftMargin = newLeft
        notch.layoutParams = layout
        notch.translationX = (oldLeft-newLeft).toFloat()
        notch.animate().translationX(0f).setDuration(265L)
            .setInterpolator(android.view.animation.OvershootInterpolator(.42f)).start()
    }

    private data class Media(val uri: Uri, val mime: String)
    private data class SmsRow(
        val text: String,
        val sent: Boolean,
        val date: Long,
        val media: List<Media> = emptyList(),
        val mms: Boolean = false,
        val mmsId: Long = -1L,
        val pendingMms: Boolean = false,
        val sender: String = ""
    )

    fun refreshMessages() {
        if (disposed || !refreshInFlight.compareAndSet(false, true)) return
        val serial = queryVersion.incrementAndGet()
        Thread {
          try {
            var isGroup = false
            var recipients = emptyList<String>()
            val rows = ArrayList<SmsRow>()
            // The Android SMS provider is authoritative for outgoing SMS.
            // Room SMS cache often updates only when the full app syncs.
            try {
                isGroup = host.conversationsDB.getConversationWithThreadId(head.id)?.isGroupConversation == true
                recipients = host.getThreadParticipants(head.id,null)
                    .flatMap { it.phoneNumbers }
                    .map { it.normalizedNumber }
                    .filter { it.isNotBlank() }
                    .distinct()
                isGroup = isGroup || recipients.size > 1
                host.contentResolver.query(Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
                    "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ?",
                    arrayOf(head.id.toString(), Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()),
                    "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                    val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val kind = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    while (cursor.moveToNext() && rows.size < 80) {
                        rows.add(SmsRow(cursor.getString(body).orEmpty(),
                            cursor.getInt(kind) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                            cursor.getLong(date)))
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("MessagesHeads", "SMS provider query failed", error)
            }
            val providerHasSms = rows.isNotEmpty()
            // Android Telephony is authoritative for MMS, even when Room hasn't
            // synchronized a group-only conversation yet.
            val representedMms=HashSet<Long>()
            // Read a small, bounded batch of provider IDs. The old code hydrated
            // all MMS in a thread before limiting to 80, which made the refresh
            // perpetually miss its deadline on busy group conversations.
            val recentMmsIds=ArrayList<Long>(24)
            try {
                host.contentResolver.query(Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID), "thread_id = ?",
                    arrayOf(head.id.toString()), "date DESC")?.use { cursor ->
                    val idColumn=cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                    while(cursor.moveToNext() && recentMmsIds.size<24)
                        recentMmsIds.add(cursor.getLong(idColumn))
                }
            } catch(error:Exception) {
                android.util.Log.w("MessagesHeads","MMS ID lookup failed",error)
            }
            val providerMms=recentMmsIds.mapNotNull { id ->
                try {
                    host.getMMS(Uri.withAppendedPath(Telephony.Mms.CONTENT_URI,id.toString()))
                } catch(error:Exception) {
                    android.util.Log.w("MessagesHeads","MMS hydration failed",error)
                    null
                }
            }
            for(msg in providerMms) {
                if(!msg.isMMS || !representedMms.add(msg.id)) continue
                val media=msg.attachment?.attachments.orEmpty().map {
                    Media(it.getUri(),it.mimetype)
                }
                rows.add(SmsRow(msg.body,!msg.isReceivedMessage(),
                    msg.millis(),media,true,msg.id,msg.isMmsDownloadPending,
                    msg.senderName.ifBlank { msg.senderPhoneNumber }))
            }
            // Keep cached messages as fallback and deduplicate provider MMS.
            try {
                for (msg in host.messagesDB.getRecentThreadMessages(head.id, 80)) {
                    if (!msg.isMMS && providerHasSms) continue
                    if (msg.isMMS && !representedMms.add(msg.id)) continue
                    val media=msg.attachment?.attachments.orEmpty().map {
                        Media(it.getUri(),it.mimetype)
                    }
                    rows.add(SmsRow(msg.body,!msg.isReceivedMessage(),
                        msg.millis(),media,msg.isMMS,
                        if(msg.isMMS)msg.id else -1L,
                        msg.isMmsDownloadPending,
                        msg.senderName.ifBlank { msg.senderPhoneNumber }))
                }
            } catch (error:Exception) {
                android.util.Log.w("MessagesHeads", "Message cache query failed", error)
            }
            rows.sortBy { it.date }
            while (rows.size > 80) rows.removeAt(0)
            host.mainExecutor.execute {
                refreshInFlight.set(false)
                if (disposed || serial != queryVersion.get()) return@execute
                groupConversation = isGroup
                resolvedRecipients = recipients
                input.isEnabled = true
                input.hint = if (isGroup) "Message group" else "Message"
                send.contentDescription = if (isGroup) "Send group message" else "Send SMS"
                if (rows != lastRenderedRows) {
                    val before = lastRenderedRows
                    val added = before != null && rows.size > before.size &&
                        rows.take(before.size) == before
                    val atBottom = nearMessageBottom()
                    val sendPending = scrollToBottomAfterSend &&
                        SystemClock.uptimeMillis() - lastSendRequestAt < 10000L
                    // New incoming/outgoing messages pin to the bottom only
                    // when the user was already there, except explicit Send.
                    val keepBottom = before == null || sendPending || (added && atBottom)
                    val previousOffset = scroller.scrollY
                    lastRenderedRows = rows
                    render(rows, animateLast = added,
                        targetBottomGap = if (keepBottom) 0 else
                            (messages.height - scroller.height - previousOffset).coerceAtLeast(0))
                    if (added && sendPending) scrollToBottomAfterSend = false
                }
            }
          } catch (error:Exception) {
            android.util.Log.e("MessagesHeads","Conversation refresh failed",error)
            host.mainExecutor.execute {
                refreshInFlight.set(false)
                if (!disposed && serial == queryVersion.get() && lastRenderedRows == null) {
                    lastRenderedRows = emptyList()
                    render(emptyList())
                }
            }
          }
        }.start()
    }

    private fun maximumMessageScroll(): Int =
        (messages.height - scroller.height + scroller.paddingBottom).coerceAtLeast(0)

    private fun nearMessageBottom(): Boolean =
        maximumMessageScroll() - scroller.scrollY <= dp(28)

    /** Apply scroll alignment after the new viewport and message children have
     * actually been measured. A posted fullScroll(FOCUS_DOWN) runs too early,
     * moves keyboard focus and frequently stops short of the final message. */
    private fun alignScrollAfterLayout(bottomGap: Int) {
        val gap = bottomGap.coerceAtLeast(0)
        pendingScrollAlignment?.let {
            if (scroller.viewTreeObserver.isAlive)
                scroller.viewTreeObserver.removeOnPreDrawListener(it)
        }
        pendingScrollAlignment = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (scroller.viewTreeObserver.isAlive)
                    scroller.viewTreeObserver.removeOnPreDrawListener(this)
                if (pendingScrollAlignment === this) pendingScrollAlignment = null
                if (!disposed) {
                    val bottom = maximumMessageScroll()
                    scroller.scrollTo(0, (bottom - gap).coerceIn(0, bottom))
                }
                return true
            }
        }.also {
            scroller.viewTreeObserver.addOnPreDrawListener(it)
        }
        scroller.invalidate()
    }

    private fun scrollToNewestMessage() = alignScrollAfterLayout(0)

    private fun render(
        rows: List<SmsRow>,
        animateLast: Boolean = false,
        targetBottomGap: Int = 0
    ) {
        messages.removeAllViews()
        if (rows.isEmpty()) {
            messages.addView(label("No messages to display yet. Try reopening this conversation.", 14f, false))
        }
        for ((index,row) in rows.withIndex()) {
            val line = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (row.sent) Gravity.END else Gravity.START
            }
            val holder = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = rounded(if (row.sent) 0xFF1674F7.toInt()
                    else if (night()) 0xFF454545.toInt() else 0xFFF2F2F2.toInt(), 16)
            }
            if (groupConversation && !row.sent && row.sender.isNotBlank()) {
                holder.addView(TextView(host).apply {
                    text=row.sender
                    textSize=12f
                    setTypeface(null,Typeface.BOLD)
                    setTextColor(if(night()) 0xFFB8D2FF.toInt() else 0xFF2564B3.toInt())
                    setPadding(0,0,0,dp(5))
                })
            }
            if (row.text.isNotBlank()) holder.addView(TextView(host).apply {
                text = row.text
                textSize = 15f
                setTextColor(if (row.sent || night()) Color.WHITE else 0xFF252525.toInt())
                setTextIsSelectable(true)
            })
            for (media in row.media.take(4)) {
                if (media.mime.startsWith("image/")) {
                    val thumb = ImageView(host).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "MMS image. Open full conversation."
                        setOnClickListener { openFullConversation() }
                    }
                    holder.addView(thumb, LinearLayout.LayoutParams(dp(208), dp(155)).apply {
                        topMargin = dp(5)
                    })
                    Glide.with(host.applicationContext).load(media.uri)
                        .centerCrop().into(thumb)
                } else {
                    holder.addView(TextView(host).apply {
                        text = "Attachment · " + media.mime.ifBlank { "file" }
                        textSize = 13f
                        setTextColor(if (row.sent || night()) Color.WHITE else Color.DKGRAY)
                        setPadding(0, dp(8), 0, dp(4))
                        setOnClickListener { openFullConversation() }
                    })
                }
            }
            if (row.media.isEmpty() && row.text.isBlank() && row.mms) {
                if(row.pendingMms){
                    holder.addView(org.fossify.messages.messaging.MmsDownloadProgressView.create(
                        host,row.mmsId
                    ){
                        if(PendingMmsDownloads.isDownloading(row.mmsId)) return@create
                        Thread {
                            val error=PendingMmsDownloads.request(host,row.mmsId)
                            post {
                                if(error!=null)android.widget.Toast.makeText(host,
                                    error,android.widget.Toast.LENGTH_LONG).show()
                                refreshMessages()
                            }
                        }.start()
                    })
                } else {
                    holder.addView(label("MMS attachment unavailable",14f,false))
                }
            }
            line.addView(holder, LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = if (row.sent) dp(62) else 0
                rightMargin = if (row.sent) 0 else dp(62)
            })
            line.addView(label(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.date)), 10f, false).apply {
                alpha = .6f
                setPadding(dp(5), dp(3), dp(5), 0)
            })
            messages.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
            if (animateLast && index == rows.lastIndex) {
                line.post { if(line.isAttachedToWindow) UiMotion.enter(line, 13f) }
            }
        }
        alignScrollAfterLayout(targetBottomGap)
    }

    private fun submitMessage() {
        val draftWhenSent = input.text.toString()
        val message = draftWhenSent.trim()
        if (message.isEmpty()) return
        val addresses=resolvedRecipients.ifEmpty {
            if(groupConversation) emptyList()
            else listOf(head.address).filter { it.isNotBlank() }
        }
        if(addresses.isEmpty()) {
            android.widget.Toast.makeText(host,
                "Could not resolve conversation recipients. Open the full conversation.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        send.isEnabled = false
        scrollToBottomAfterSend = true
        lastSendRequestAt = SystemClock.uptimeMillis()
        scrollToNewestMessage()
        Thread {
            var error: String? = null
            try {
                host.sendMessageCompat(message, addresses, null, emptyList())
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Message not sent"
            }
            host.mainExecutor.execute {
                if (error == null && ConversationDrafts.read(host, head.id) == draftWhenSent) {
                    // Clear even if the user closed the floating window while
                    // the carrier accepted the send. Never wipe newer typing.
                    ConversationDrafts.edit(host, head.id, "")
                }
                if (disposed) return@execute
                send.isEnabled = true
                if (error == null) {
                    if (input.text.toString() == draftWhenSent) input.setText("")
                    scrollToNewestMessage()
                    refreshMessages()
                    // The inbox is another view of these same Telephony rows;
                    // refresh its cached snippet even when not foregrounded.
                    org.fossify.messages.helpers.refreshConversations()
                } else {
                    scrollToBottomAfterSend = false
                    android.widget.Toast.makeText(host, error, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openFullConversation() {
        val intent = Intent(host, ThreadActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(THREAD_ID, head.id)
            putExtra(THREAD_TITLE, head.title)
        }
        onCollapse()
        host.startActivity(intent)
    }

    private fun control(textValue: String, description: String, click: () -> Unit) =
        TextView(host).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 27f
            contentDescription = description
            setTextColor(if (night()) Color.WHITE else Color.BLACK)
            UiMotion.press(this, .90f)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(45))
        }

    private fun label(value: String, size: Float, bold: Boolean) = TextView(host).apply {
        text = value
        textSize = size
        setTextColor(if (night()) Color.WHITE else 0xFF282828.toInt())
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun night() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = host.dp(value)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }
    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private var backInvoked: android.window.OnBackInvokedCallback? = null
    private var overlayFocused = true

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) onCollapse()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        overlayFocused = hasWindowFocus
        if (!hasWindowFocus && imeVisible != true) {
            // Home/Recents may remove focus without broadcasting a reason.
            postDelayed({
                if (isAttachedToWindow && !overlayFocused && imeVisible != true) onCollapse()
            }, 160L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        disposed = false
        ConversationDrafts.observe(head.id, sharedDraftListener)
        ConversationDrafts.read(host, head.id)?.let(sharedDraftListener)
            ?: Thread {
                val legacy = host.getSmsDraft(head.id)
                post {
                    if (!disposed && isAttachedToWindow) {
                        ConversationDrafts.migrateLegacy(host, head.id, legacy)
                        ConversationDrafts.read(host, head.id)?.let(sharedDraftListener)
                    }
                }
            }.start()
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val callback = android.window.OnBackInvokedCallback { onCollapse() }
                findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                backInvoked = callback
            } catch (_: Exception) {}
        }
        disposed = false
        if (lastRenderedRows == null && messages.childCount == 0) {
            messages.addView(label("Loading messages...",14f,false))
        }
        refreshHandler.removeCallbacks(refreshTick)
        refreshMessages()
        refreshHandler.postDelayed(refreshTick, 1600L)
    }

    override fun onDetachedFromWindow() {
        if (input.text.isNotEmpty() || ConversationDrafts.read(host, head.id) != null)
            ConversationDrafts.flush(host, head.id, input.text.toString())
        ConversationDrafts.unobserve(head.id, sharedDraftListener)
        if (Build.VERSION.SDK_INT >= 33) {
            backInvoked?.let { callback ->
                try { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback) }
                catch (_: Exception) {}
            }
            backInvoked = null
        }
        cancelKeyboardImpact()
        pendingScrollAlignment?.let {
            if (scroller.viewTreeObserver.isAlive)
                scroller.viewTreeObserver.removeOnPreDrawListener(it)
        }
        pendingScrollAlignment = null
        genieAnimator?.removeAllListeners()
        genieAnimator?.cancel()
        genieAnimator = null
        releaseGenieSnapshot()
        refreshHandler.removeCallbacks(refreshTick)
        disposed = true
        queryVersion.incrementAndGet()
        super.onDetachedFromWindow()
    }
}
