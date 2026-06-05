package project.pipepipe.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.player.playqueue.PlayQueue
import project.pipepipe.app.service.PlaybackService

object ExperimentalPlaybackRouter {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @JvmStatic
    fun play(context: Context, queue: PlayQueue, mode: PlaybackMode, shuffle: Boolean) {
        context.startService(Intent(context, PlaybackService::class.java))
        scope.launch {
            val controller = awaitController() ?: return@launch
            val items = LegacyPlayQueueAdapter.convert(queue)
            SharedContext.updatePlaybackMode(mode)
            SharedContext.queueManager.setQueue(items, queue.index)
            if (shuffle) {
                SharedContext.queueManager.shuffle(items.getOrNull(queue.index)?.uuid)
            }
            controller.prepare()
            controller.play()
        }
    }

    @JvmStatic
    fun enqueue(context: Context, queue: PlayQueue, next: Boolean) {
        context.startService(Intent(context, PlaybackService::class.java))
        scope.launch {
            val controller = awaitController() ?: return@launch
            val items = LegacyPlayQueueAdapter.convert(queue)
            if (SharedContext.queueManager.getCurrentQueue().isEmpty()) {
                SharedContext.queueManager.setQueue(items, queue.index)
                controller.prepare()
                return@launch
            }
            items.forEach(SharedContext.queueManager::addItem)
            if (next) {
                val currentIndex = controller.currentItemIndex.value
                repeat(items.size) {
                    val from = SharedContext.queueManager.getCurrentQueue().lastIndex
                    SharedContext.queueManager.moveItem(from, currentIndex + 1 + it)
                }
            }
        }
    }

    private suspend fun awaitController(): project.pipepipe.app.platform.PlatformMediaController? {
        repeat(50) {
            SharedContext.platformMediaController?.let { return it }
            delay(100)
        }
        return null
    }
}
