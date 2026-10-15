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

    /** Super chats currently pinned, in display order. */
    val pinned = MutableLiveData<List<PinnedSc>>(emptyList())

    /** Url of the stream [items] and [available] belong to. */
    @Volatile
    var url: String? = null

    fun reset() {
        url = null
        items.value = emptyList()
        available.value = null
        pinned.value = emptyList()
    }
}

/** A super chat that is still pinned; [fraction] is the share of its pin time left (0..1). */
data class PinnedSc(val item: BulletCommentsInfoItem, val fraction: Float)

/** Pin time of a super chat, derived from its colour tier like YouTube does. */
object ScTiers {
    // body colour (RGB) -> pin time in seconds
    private val tiers = listOf(
        0x1565C0 to 0, // blue: not pinned
        0x00B8D4 to 120,
        0x00BFA5 to 120,
        0xFFB300 to 300,
        0xE65100 to 600,
        0xC2185B to 1800,
        0xD00000 to 3600,
    )
    private const val DEFAULT_SECONDS = 60

    fun pinMillis(argb: Int): Long {
        val rgb = argb and 0xFFFFFF
        if (argb ushr 24 == 0 && argb == 0) return DEFAULT_SECONDS * 1000L
        fun d(a: Int, b: Int) = (a - b) * (a - b)
        val best = tiers.minByOrNull {
            d(rgb shr 16, it.first shr 16) + d(rgb shr 8 and 0xFF, it.first shr 8 and 0xFF) +
                d(rgb and 0xFF, it.first and 0xFF)
        }!!
        return best.second * 1000L
    }
}
