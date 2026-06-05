package project.pipepipe.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import project.pipepipe.app.platform.PlatformMediaController
import project.pipepipe.app.platform.PlatformMediaItem

class QueueManager {
    private val mutableQueue = MutableStateFlow<List<PlatformMediaItem>>(emptyList())
    val queue: StateFlow<List<PlatformMediaItem>> = mutableQueue.asStateFlow()

    private var controller: PlatformMediaController? = null
    private var backup: MutableList<PlatformMediaItem>? = null

    fun attachController(controller: PlatformMediaController?) {
        this.controller = controller
    }

    fun getCurrentQueue(): List<PlatformMediaItem> = mutableQueue.value

    fun getIndexOfItemUuid(uuid: String): Int =
        mutableQueue.value.indexOfFirst { it.uuid == uuid }

    fun isShuffled(): Boolean = backup != null

    fun setQueue(items: List<PlatformMediaItem>, startIndex: Int = 0, notifyOnly: Boolean = false) {
        mutableQueue.value = items.toList()
        backup = null
        if (!notifyOnly) {
            controller?.setQueue(items, startIndex)
        }
    }

    fun addItem(item: PlatformMediaItem) {
        backup?.add(item)
        mutableQueue.value += item
        controller?.syncQueueAppend(item)
    }

    fun removeItemByUuid(uuid: String) {
        val index = getIndexOfItemUuid(uuid)
        if (index < 0) {
            return
        }
        mutableQueue.value = mutableQueue.value.toMutableList().apply { removeAt(index) }
        backup?.removeAll { it.uuid == uuid }
        controller?.syncQueueRemove(index)
    }

    fun updateItemExtras(uuid: String, newExtras: Map<String, Any?>) {
        val index = getIndexOfItemUuid(uuid)
        if (index < 0) {
            return
        }
        val item = mutableQueue.value[index]
        val updatedItem = item.copy(extras = ((item.extras ?: emptyMap()) + newExtras).ifEmpty { null })
        mutableQueue.value = mutableQueue.value.toMutableList().apply { set(index, updatedItem) }
        backup?.indexOfFirst { it.uuid == uuid }?.takeIf { it >= 0 }?.let { backup?.set(it, updatedItem) }
    }

    fun moveItem(from: Int, to: Int) {
        if (from !in mutableQueue.value.indices || to !in mutableQueue.value.indices || from == to) {
            return
        }
        mutableQueue.value = mutableQueue.value.toMutableList().apply {
            add(to, removeAt(from))
        }
        backup?.let {
            val item = it.removeAt(from)
            it.add(to, item)
        }
        controller?.syncQueueMove(from, to)
    }

    fun clear() {
        mutableQueue.value = emptyList()
        backup = null
        controller?.syncQueueClear()
    }

    fun shuffle(currentUuid: String?) {
        if (mutableQueue.value.size <= 1 || currentUuid == null) {
            return
        }
        if (backup == null) {
            backup = mutableQueue.value.toMutableList()
        }
        val currentItem = mutableQueue.value.firstOrNull { it.uuid == currentUuid } ?: return
        mutableQueue.value = mutableQueue.value.shuffled().filterNot { it.uuid == currentUuid }
            .toMutableList().apply { add(0, currentItem) }
        controller?.syncQueueShuffle()
    }

    fun unshuffle() {
        val restoredQueue = backup ?: return
        mutableQueue.value = restoredQueue.toList()
        backup = null
        controller?.syncQueueShuffle()
    }
}
