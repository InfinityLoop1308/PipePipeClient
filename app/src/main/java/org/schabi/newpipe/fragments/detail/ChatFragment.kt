package org.schabi.newpipe.fragments.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import org.schabi.newpipe.R
import org.schabi.newpipe.player.chat.ChatStore

/** Chat tab of the video detail page, showing what [ChatStore] provides. */
class ChatFragment : Fragment() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var jump: Button
    private lateinit var chat: ChatListView
    private var appBar: AppBarLayout? = null
    private val offsetListener = AppBarLayout.OnOffsetChangedListener { bar, offset ->
        applyBottomInset(bar.totalScrollRange + offset)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.chat_list)
        empty = view.findViewById(R.id.chat_empty)
        jump = view.findViewById(R.id.chat_jump_to_latest)
        chat = ChatListView(list, jump, forceLight = false)
        val pinned = PinnedScView(view.findViewById(R.id.chat_pinned_root))
        ChatStore.pinned.observe(viewLifecycleOwner) { pinned.submit(it) }
        ChatStore.items.observe(viewLifecycleOwner) { items ->
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            chat.submit(items)
        }
    }

    override fun onStart() {
        super.onStart()
        // The tab is laid out as if the app bar was fully collapsed, so while it is not the lower
        // part of the list is below the screen. Pad the list by that amount to keep the latest
        // message visible.
        var parent = view?.parent
        while (parent != null && parent !is CoordinatorLayout) {
            parent = parent.parent
        }
        val coordinator = parent as? CoordinatorLayout ?: return
        val bar = (0 until coordinator.childCount)
            .map { coordinator.getChildAt(it) }
            .firstOrNull { it is AppBarLayout } as? AppBarLayout ?: return
        appBar = bar
        bar.addOnOffsetChangedListener(offsetListener)
        bar.post { applyBottomInset(bar.totalScrollRange + bar.top) }
    }

    override fun onStop() {
        appBar?.removeOnOffsetChangedListener(offsetListener)
        appBar = null
        super.onStop()
    }

    private fun applyBottomInset(inset: Int) {
        val bottom = maxOf(0, inset)
        if (list.paddingBottom != bottom) {
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, bottom)
            if (chat.stickToBottom) {
                list.post { chat.scrollToEnd() }
            }
        }
    }
}
