package org.schabi.newpipe.player.chat

import androidx.lifecycle.MutableLiveData
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem

/**
 * Process-wide hand-over between the player ([ChatController], producer) and the chat tab of the
 * video detail page (consumer), so the tab does not depend on which player instance is alive.
 */
object ChatStore {
    /** The messages to display, oldest first. */
    val items = MutableLiveData<List<BulletCommentsInfoItem>>(emptyList())

    /** Whether the current stream has a chat: null while unknown. */
    val available = MutableLiveData<Boolean?>(null)

    /** Url of the stream [items] and [available] belong to. */
    @Volatile
    var url: String? = null

    fun reset() {
        url = null
        items.value = emptyList()
        available.value = null
    }
}
