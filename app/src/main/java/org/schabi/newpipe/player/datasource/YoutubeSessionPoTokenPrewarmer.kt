package org.schabi.newpipe.player.datasource

import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

internal data class YoutubeSessionPoTokenContext(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String?,
    val localization: Localization,
    val contentCountry: ContentCountry,
    val loggedIn: Boolean,
    val credentialIdentity: String,
)

internal class ContextBoundSingleFlight<K, V>(private val executor: Executor) {
    private data class Entry<K, V>(val key: K, val task: FutureTask<V>)

    private val lock = Any()
    private var current: Entry<K, V>? = null

    fun start(key: K, operation: () -> V): Boolean {
        val created = object : FutureTask<V>(operation) {
            override fun done() {
                synchronized(lock) {
                    if (current?.task === this) {
                        current = null
                    }
                }
            }
        }
        val replaced = synchronized(lock) {
            val existing = current
            if (existing != null && existing.key == key && !existing.task.isDone) {
                return false
            }
            current = Entry(key, created)
            existing?.task
        }
        replaced?.cancel(true)
        try {
            executor.execute(created)
        } catch (error: RuntimeException) {
            synchronized(lock) {
                if (current?.task === created) {
                    current = null
                }
            }
            throw error
        }
        return true
    }

    fun inFlight(key: K): Future<V>? = synchronized(lock) {
        current?.takeIf { it.key == key && !it.task.isCancelled }?.task
    }

    fun cancel() {
        val task = synchronized(lock) {
            current?.task.also { current = null }
        }
        task?.cancel(true)
    }
}
