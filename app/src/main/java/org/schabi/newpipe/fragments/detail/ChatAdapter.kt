package org.schabi.newpipe.fragments.detail

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem
import org.schabi.newpipe.util.PicassoHelper

/**
 * Binds a chat list: adapter, sticking to the latest message and the "jump to latest" button.
 * Shared by the chat tab and the fullscreen chat panel of the player.
 *
 * @param forceLight draw for a dark background regardless of the theme
 */
class ChatListView(
    private val list: RecyclerView,
    private val jump: Button?,
    forceLight: Boolean,
) {
    private val adapter = ChatAdapter(forceLight)
    var stickToBottom = true
        private set

    init {
        list.layoutManager = LinearLayoutManager(list.context)
        list.itemAnimator = null
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                stickToBottom = !rv.canScrollVertically(1)
                jump?.visibility = if (stickToBottom) View.GONE else View.VISIBLE
            }
        })
        jump?.setOnClickListener {
            stickToBottom = true
            jump.visibility = View.GONE
            scrollToEnd()
        }
    }

    fun submit(items: List<BulletCommentsInfoItem>) {
        adapter.submitList(items) {
            if (stickToBottom) {
                scrollToEnd()
            }
        }
    }

    fun scrollToEnd() {
        if (adapter.itemCount > 0) {
            list.scrollToPosition(adapter.itemCount - 1)
        }
    }
}

class ChatAdapter(private val forceLight: Boolean) :
    ListAdapter<BulletCommentsInfoItem, RecyclerView.ViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<BulletCommentsInfoItem>() {
        override fun areItemsTheSame(a: BulletCommentsInfoItem, b: BulletCommentsInfoItem) =
            a === b

        override fun areContentsTheSame(a: BulletCommentsInfoItem, b: BulletCommentsInfoItem) =
            a === b
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).position == BulletCommentsInfoItem.Position.SUPERCHAT) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            SuperChatHolder(inflater.inflate(R.layout.item_chat_super_chat, parent, false))
        } else {
            MessageHolder(
                inflater.inflate(R.layout.item_chat_message, parent, false),
                forceLight,
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is SuperChatHolder) holder.bind(item) else (holder as MessageHolder).bind(item)
    }

    private class MessageHolder(view: View, private val forceLight: Boolean) :
        RecyclerView.ViewHolder(view) {
        fun bind(item: BulletCommentsInfoItem) {
            val name = item.authorName?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.chat_anonymous)
            val text = SpannableStringBuilder(name)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                ForegroundColorSpan(if (forceLight) 0xFFBBBBBB.toInt() else 0xFF888888.toInt()),
                0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.append("  ").append(item.commentText.orEmpty())
            (itemView as TextView).apply {
                if (forceLight) setTextColor(Color.WHITE)
                this.text = text
            }
        }
    }

    class SuperChatHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val header = view.findViewById<View>(R.id.chat_sc_header)
        private val avatar = view.findViewById<ImageView>(R.id.chat_sc_avatar)
        private val name = view.findViewById<TextView>(R.id.chat_sc_name)
        private val amount = view.findViewById<TextView>(R.id.chat_sc_amount)
        private val text = view.findViewById<TextView>(R.id.chat_sc_text)

        fun bind(item: BulletCommentsInfoItem) {
            val body = item.argbColor or 0xFF000000.toInt()
            val headerColor = ColorUtils.blendARGB(body, Color.BLACK, 0.2f)
            val fg = if (ColorUtils.calculateLuminance(body) > 0.5) Color.BLACK else Color.WHITE
            val radius = itemView.resources.displayMetrics.density * 8
            itemView.background = GradientDrawable().apply {
                setColor(body)
                cornerRadius = radius
            }
            header.background = GradientDrawable().apply {
                setColor(headerColor)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            val headerFg =
                if (ColorUtils.calculateLuminance(headerColor) > 0.5) Color.BLACK else Color.WHITE
            name.setTextColor(headerFg)
            amount.setTextColor(headerFg)
            text.setTextColor(fg)
            name.text = item.authorName?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.chat_anonymous)
            amount.text = item.amountText.orEmpty()
            amount.visibility = if (item.amountText.isNullOrEmpty()) View.GONE else View.VISIBLE
            val avatarUrl = item.authorAvatarUrl
            if (avatarUrl.isNullOrEmpty()) {
                avatar.visibility = View.GONE
            } else {
                avatar.visibility = View.VISIBLE
                PicassoHelper.loadAvatar(avatarUrl).into(avatar)
            }
            text.text = item.commentText.orEmpty()
            text.visibility = if (item.commentText.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}

/**
 * Horizontally scrolling strip of the pinned super chats, like the ticker of YouTube: each chip
 * is drawn in the colour of its tier and drains while its pin time runs out. Tapping a chip
 * shows the whole message below the strip.
 */
class PinnedScView(private val root: View) {
    private val list = root.findViewById<RecyclerView>(R.id.chat_pinned_list)
    private val detail = root.findViewById<android.widget.FrameLayout>(R.id.chat_pinned_detail)
    private val adapter = ChipAdapter { toggle(it) }
    private var detailHolder: ChatAdapter.SuperChatHolder? = null
    private var selected: BulletCommentsInfoItem? = null

    init {
        list.layoutManager =
            LinearLayoutManager(list.context, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        list.adapter = adapter
        // let the horizontal strip scroll without the parent (pager, bottom sheet) stealing it
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    rv.parent.requestDisallowInterceptTouchEvent(true)
                }
                return false
            }
        })
    }

    fun submit(items: List<org.schabi.newpipe.player.chat.PinnedSc>) {
        root.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (selected != null && items.none { it.item === selected }) {
            selected = null
        }
        adapter.submitList(items)
        showDetail()
    }

    private fun toggle(item: BulletCommentsInfoItem) {
        selected = if (selected === item) null else item
        showDetail()
    }

    private fun showDetail() {
        val item = selected
        if (item == null) {
            detail.visibility = View.GONE
            return
        }
        if (detailHolder == null) {
            val view = LayoutInflater.from(detail.context)
                .inflate(R.layout.item_chat_super_chat, detail, false)
            detail.addView(view)
            detailHolder = ChatAdapter.SuperChatHolder(view)
        }
        detailHolder!!.bind(item)
        detail.visibility = View.VISIBLE
    }

    private class ChipAdapter(private val onClick: (BulletCommentsInfoItem) -> Unit) :
        ListAdapter<org.schabi.newpipe.player.chat.PinnedSc, ChipHolder>(
            object : DiffUtil.ItemCallback<org.schabi.newpipe.player.chat.PinnedSc>() {
                override fun areItemsTheSame(
                    a: org.schabi.newpipe.player.chat.PinnedSc,
                    b: org.schabi.newpipe.player.chat.PinnedSc,
                ) = a.item === b.item

                override fun areContentsTheSame(
                    a: org.schabi.newpipe.player.chat.PinnedSc,
                    b: org.schabi.newpipe.player.chat.PinnedSc,
                ) = a == b
            },
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ChipHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_pinned_chip, parent, false),
            onClick,
        )

        override fun onBindViewHolder(holder: ChipHolder, position: Int) =
            holder.bind(getItem(position))
    }

    private class ChipHolder(view: View, private val onClick: (BulletCommentsInfoItem) -> Unit) :
        RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.chat_chip_avatar)
        private val amount = view.findViewById<TextView>(R.id.chat_chip_amount)

        fun bind(pinned: org.schabi.newpipe.player.chat.PinnedSc) {
            val item = pinned.item
            val body = item.argbColor or 0xFF000000.toInt()
            val radius = itemView.resources.displayMetrics.density * 16
            val dim = GradientDrawable().apply {
                setColor(ColorUtils.blendARGB(body, Color.BLACK, 0.55f))
                cornerRadius = radius
            }
            val bright = GradientDrawable().apply {
                setColor(body)
                cornerRadius = radius
            }
            val clip = android.graphics.drawable.ClipDrawable(
                bright, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL,
            )
            clip.level = (pinned.fraction.coerceIn(0f, 1f) * 10000).toInt()
            itemView.background = android.graphics.drawable.LayerDrawable(arrayOf(dim, clip))
            amount.setTextColor(if (ColorUtils.calculateLuminance(body) > 0.5) Color.BLACK else Color.WHITE)
            amount.text = item.amountText?.takeIf { it.isNotEmpty() }
                ?: item.authorName.orEmpty()
            val url = item.authorAvatarUrl
            if (url.isNullOrEmpty()) {
                avatar.visibility = View.GONE
            } else {
                avatar.visibility = View.VISIBLE
                PicassoHelper.loadAvatar(url).into(avatar)
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
