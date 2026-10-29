package org.fossify.messages.adapters

import android.annotation.SuppressLint
import android.graphics.Typeface
import org.fossify.messages.theming.UiTheme
import org.fossify.messages.helpers.ConversationCustomization
import org.fossify.messages.theming.UiMotion
import android.graphics.Color
import androidx.core.content.ContextCompat
import org.fossify.messages.R
import android.os.Parcelable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ItemConversationBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getAllDrafts
import org.fossify.messages.models.Conversation

@Suppress("LeakingThis")
abstract class BaseConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit,
) : MyRecyclerViewListAdapter<Conversation>(
    activity = activity,
    recyclerView = recyclerView,
    diffUtil = ConversationDiffCallback(),
    itemClick = itemClick,
    onRefresh = onRefresh
),
    RecyclerViewFastScroller.OnPopupTextUpdate {
    private var fontSize = activity.getTextSize()
    private var drafts = HashMap<Long, String>()

    private var recyclerViewState: Parcelable? = null

    init {
        setupDragListener(true)
        setHasStableIds(true)
        updateDrafts()

        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restoreRecyclerViewState()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                restoreRecyclerViewState()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                restoreRecyclerViewState()
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateFontSize() {
        fontSize = activity.getTextSize()
        notifyDataSetChanged()
    }

    fun updateConversations(
        newConversations: ArrayList<Conversation>,
        commitCallback: (() -> Unit)? = null,
    ) {
        saveRecyclerViewState()
        submitList(newConversations.toList(), commitCallback)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateDrafts() {
        ensureBackgroundThread {
            val newDrafts = HashMap<Long, String>()
            fetchDrafts(newDrafts)
            activity.runOnUiThread {
                if (drafts.hashCode() != newDrafts.hashCode()) {
                    drafts = newDrafts
                    notifyDataSetChanged()
                }
            }
        }
    }

    override fun getSelectableItemCount() = itemCount

    protected fun getSelectedItems() = currentList.filter {
        selectedKeys.contains(it.hashCode())
    } as ArrayList<Conversation>

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = currentList.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = getItem(position)
        holder.bindView(
            conversation,
            allowSingleClick = true,
            allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, conversation)
        }
        bindViewHolder(holder)
    }

    override fun getItemId(position: Int) = getItem(position).threadId

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val itemView = ItemConversationBinding.bind(holder.itemView)
            Glide.with(activity).clear(itemView.conversationImage)
            UiMotion.clear(holder.itemView)
            androidx.core.view.ViewCompat.setTransitionName(holder.itemView, null)
        }
    }

    private fun fetchDrafts(drafts: HashMap<Long, String>) {
        drafts.clear()
        for ((threadId, draft) in activity.getAllDrafts()) {
            drafts[threadId] = draft
        }
    }

    private fun setupView(view: View, conversation: Conversation) {
        ItemConversationBinding.bind(view).apply {
            root.setupViewBackground(activity)
            UiMotion.press(root, .986f)
            conversationImage.setOnClickListener {
                val actions = if (conversation.isGroupConversation)
                    arrayOf("Open conversation", "Open Chat Head", "Customize conversation", "Move to Safe")
                else arrayOf("Open conversation", "Open Chat Head", "Customize conversation", "Call", "Move to Safe")
                org.fossify.messages.theming.FluidSheets.actions(
                    activity, conversation.title, actions) { choice ->
                    when (choice) {
                        0 -> root.performClick()
                        1 -> org.fossify.messages.chatheads.ChatHeadsSettingsActivity.openThread(
                            activity, conversation.threadId, conversation.title, conversation.phoneNumber)
                        2 -> activity.startActivity(android.content.Intent(
                            activity, org.fossify.messages.activities.ConversationDetailsActivity::class.java)
                            .putExtra(org.fossify.messages.helpers.THREAD_ID,conversation.threadId))
                        4 -> activity.startActivity(android.content.Intent(activity,
                            org.fossify.messages.safe.SafeActivity::class.java)
                            .putExtra("safe_add_thread",conversation.threadId))

                        3 -> if(conversation.isGroupConversation) activity.startActivity(android.content.Intent(
                            activity,org.fossify.messages.safe.SafeActivity::class.java).putExtra("safe_add_thread",conversation.threadId))
                             else activity.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            android.net.Uri.fromParts("tel", conversation.phoneNumber, null)))
                    }
                }
            }
            // All geometry derives from the selected theme. The click/selection
            // and contact image paths remain the original functional ones.
            val themedPadding=UiTheme.dp(activity,UiTheme.metric(activity,"row_padding"))
            conversationFrame.setPadding(UiTheme.dp(activity,20),themedPadding,
                UiTheme.dp(activity,24),themedPadding)
            conversationFrame.minimumHeight=UiTheme.dp(activity,UiTheme.metric(activity,"row_height"))
            val avatarSize=UiTheme.dp(activity,UiTheme.metric(activity,"avatar_size"))
            conversationImage.layoutParams=conversationImage.layoutParams.apply {
                width=avatarSize;height=avatarSize
            }
            // ConstraintLayout title and preview both anchor after the avatar.
            val textStart=UiTheme.dp(activity,UiTheme.metric(activity,"row_text_gap"))
            conversationAddress.layoutParams=conversationAddress.layoutParams.apply {
                if (this is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                    marginStart=textStart
            }
            conversationBodyShort.layoutParams=conversationBodyShort.layoutParams.apply {
                if (this is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                    marginStart=textStart
            }
            val smsDraft = drafts[conversation.threadId]
            draftIndicator.beVisibleIf(!smsDraft.isNullOrEmpty())
            draftIndicator.setTextColor(UiTheme.color(activity,"draft_text"))

            pinIndicator.beVisibleIf(
                activity.config.pinnedConversations.contains(conversation.threadId.toString())
            )
            pinIndicator.applyColorFilter(textColor)

            conversationFrame.isSelected = selectedKeys.contains(conversation.hashCode())

            conversationAddress.apply {
                text = conversation.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }

            conversationBodyShort.apply {
                text = smsDraft ?: conversation.snippet
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }

            conversationDate.apply {
                text = (conversation.date * 1000L).formatDateOrTime(
                    context = context,
                    hideTimeOnOtherDays = true,
                    showCurrentYear = false
                )

                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }

            val isUnread = !conversation.read && conversation.unreadCount > 0
            val style = if (isUnread) {
                conversationBodyShort.alpha = 1f
                if (conversation.isScheduled) Typeface.BOLD_ITALIC else Typeface.BOLD
            } else {
                conversationBodyShort.alpha = 0.7f
                if (conversation.isScheduled) Typeface.ITALIC else Typeface.NORMAL
            }
            val customTypeface = FontHelper.getTypeface(activity)
            conversationAddress.setTypeface(customTypeface, style)
            conversationBodyShort.setTypeface(customTypeface, style)
            conversationDate.setTypeface(customTypeface, style)

            conversationAddress.setTextColor(UiTheme.color(activity,"title"))
            conversationBodyShort.setTextColor(UiTheme.color(activity,"preview"))
            conversationDate.setTextColor(UiTheme.color(activity,"date"))
            if (selectedKeys.contains(conversation.hashCode())) {
                conversationFrame.background=UiTheme.shape(activity,"selected","card_radius")
            } else {
                // Clear recycled selection state. A rectangular default
                // selectable background ignored the theme's corner radius.
                conversationFrame.background=android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(UiTheme.color(activity,"selected")),
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    UiTheme.shape(activity,"card","card_radius"))
            }

            setupBadgeCount(unreadCountBadge, isUnread, conversation.unreadCount)
            // at group conversations we use an icon as the placeholder, not any letter
            val placeholder = if (conversation.isGroupConversation) {
                SimpleContactsHelper(activity).getColoredGroupIcon(conversation.title)
            } else {
                null
            }

            val avatarKind=if(conversation.isGroupConversation)"group" else "avatar"
            val avatarRadius=if(conversation.isGroupConversation)"group_radius" else "avatar_radius"
            conversationImage.background=UiTheme.shape(activity,avatarKind,avatarRadius)
            conversationImage.clipToOutline=true
            val picture=ConversationCustomization.photoFile(activity,conversation.threadId)
            when {
                picture!=null -> {
                    if(ConversationCustomization.isAnimatedPhoto(picture))
                        Glide.with(activity).asGif().load(picture).centerCrop().into(conversationImage)
                    else Glide.with(activity).load(picture).centerCrop().into(conversationImage)
                }
                conversation.photoUri.isNullOrBlank() -> {
                    Glide.with(activity).clear(conversationImage)
                    conversationImage.setImageBitmap(UiTheme.avatarBitmap(
                        activity,conversation.title,conversation.isGroupConversation))
                }
                else -> SimpleContactsHelper(activity).loadContactImage(
                    path=conversation.photoUri,
                    imageView=conversationImage,
                    placeholderName=conversation.title,
                    placeholderImage=placeholder)
            }
        }
    }

    private fun setupBadgeCount(view: TextView, isUnread: Boolean, count: Int) {
        view.apply {
            beVisibleIf(isUnread && count > 0)
            if (isUnread && count > 0) {
                text = when {
                    count > MAX_UNREAD_BADGE_COUNT -> "$MAX_UNREAD_BADGE_COUNT+"
                    count == 0 -> ""
                    else -> count.toString()
                }
                setTextColor(UiTheme.color(activity,"badge_text"))
                background=UiTheme.shape(activity,"badge","badge_radius")
            }
        }
    }

    override fun onChange(position: Int) = currentList.getOrNull(position)?.title ?: ""

    private fun saveRecyclerViewState() {
        recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
    }

    private fun restoreRecyclerViewState() {
        recyclerView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    private class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return Conversation.areItemsTheSame(oldItem, newItem)
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return Conversation.areContentsTheSame(oldItem, newItem)
        }
    }

    companion object {
        private const val MAX_UNREAD_BADGE_COUNT = 99
    }
}
