package org.schabi.newpipe.fragments.detail

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem
import org.schabi.newpipe.player.chat.ChatStore
import org.schabi.newpipe.util.PicassoHelper

/** Chat tab of the video detail page, showing what [ChatStore] provides. */
class ChatFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var jump: Button
    private val adapter = ChatAdapter()
    private var stickToBottom = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.chat_list)
        empty = view.findViewById(R.id.chat_empty)
        jump = view.findViewById(R.id.chat_jump_to_latest)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.itemAnimator = null
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                stickToBottom = !rv.canScrollVertically(1)
                jump.visibility = if (stickToBottom) View.GONE else View.VISIBLE
            }
        })
        jump.setOnClickListener {
            stickToBottom = true
            jump.visibility = View.GONE
            scrollToEnd()
        }
        ChatStore.items.observe(viewLifecycleOwner) { items ->
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(items) {
                if (stickToBottom) {
                    scrollToEnd()
                }
            }
        }
    }

    private fun scrollToEnd() {
        if (adapter.itemCount > 0) {
            list.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private class ChatAdapter :
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
                MessageHolder(inflater.inflate(R.layout.item_chat_message, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            if (holder is SuperChatHolder) holder.bind(item) else (holder as MessageHolder).bind(item)
        }
    }

    private class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind(item: BulletCommentsInfoItem) {
            val name = item.authorName?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.chat_anonymous)
            val text = SpannableStringBuilder(name)
            text.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                ForegroundColorSpan(0xFF888888.toInt()), 0, name.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.append("  ").append(item.commentText.orEmpty())
            (itemView as TextView).text = text
        }
    }

    private class SuperChatHolder(view: View) : RecyclerView.ViewHolder(view) {
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
