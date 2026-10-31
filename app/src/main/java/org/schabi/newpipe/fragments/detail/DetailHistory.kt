package org.schabi.newpipe.fragments.detail

import org.schabi.newpipe.player.playqueue.PlayQueue
import java.io.Serializable
import java.util.LinkedList

/** An entry of [DetailHistory]. */
class StackItem(
    val serviceId: Int,
    var url: String?,
    var title: String?,
    var playQueue: PlayQueue,
) : Serializable {
    override fun toString() = "$serviceId:$url > $title"
}

/**
 * The navigation history of the video detail page. The top is the current stream.
 *
 * It belongs to the process rather than to a fragment instance: a new detail fragment (e.g. when
 * the player is reopened from the notification) continues the history of the previous one. It is
 * cleared when the detail page is swiped away and when the activity finishes.
 */
object DetailHistory {
    private val stack = LinkedList<StackItem>()

    val size: Int get() = stack.size

    fun isEmpty() = stack.isEmpty()

    fun peek(): StackItem? = stack.peek()

    fun push(item: StackItem) = stack.push(item)

    fun pop(): StackItem = stack.pop()

    fun clear() = stack.clear()

    /** The oldest entry playing [queue]. */
    fun find(queue: PlayQueue): StackItem? =
        stack.descendingIterator().asSequence().firstOrNull { it.playQueue == queue }
}
