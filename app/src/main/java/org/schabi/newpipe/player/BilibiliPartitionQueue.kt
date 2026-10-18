package org.schabi.newpipe.player

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Helpers for appending the next part of a Bilibili multi-part video. */
internal object BilibiliPartitionQueue {
    fun nextPageToAppend(
        queueUrls: List<String>,
        currentUrl: String,
        partitionCount: Int,
    ): Int? {
        if (partitionCount < 2 || queueUrls.isEmpty()) {
            return null
        }

        val queueParts = queueUrls.map { parsePart(it) ?: return null }
        val first = queueParts.first()
        if (queueParts.any { it.videoKey != first.videoKey }
            || queueParts.zipWithNext().any { (previous, next) -> next.page != previous.page + 1 }
        ) {
            return null
        }

        val currentPart = parsePart(currentUrl) ?: return null
        if (currentPart.videoKey != first.videoKey || queueParts.last().page != currentPart.page) {
            return null
        }

        return (currentPart.page + 1).takeIf { it <= partitionCount }
    }

    private fun parsePart(url: String): Part? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) {
            return null
        }
        val path = uri.path ?: return null
        if (!path.startsWith("/video/")) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }

        val pageValue = uri.rawQuery
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && decode(it[0]) == "p" }
            ?.get(1)
        val page = if (pageValue == null) 1 else decode(pageValue).toIntOrNull() ?: return null
        if (page < 1) {
            return null
        }

        val port = if (uri.port == -1) "" else ":${uri.port}"
        val videoKey = "$scheme://$host$port$path"
        return Part(videoKey, page)
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private data class Part(val videoKey: String, val page: Int)
}
