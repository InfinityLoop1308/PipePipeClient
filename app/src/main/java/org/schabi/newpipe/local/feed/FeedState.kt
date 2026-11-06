package org.schabi.newpipe.local.feed

import androidx.annotation.StringRes
import org.schabi.newpipe.local.feed.item.StreamItem
import java.time.OffsetDateTime

sealed class FeedState {
    data class ProgressState(
        val currentProgress: Int = -1,
        val maxProgress: Int = -1,
        @StringRes val progressMessage: Int = 0,
        val previousContent: LoadedState? = null
    ) : FeedState()

    data class LoadedState(
        val items: List<StreamItem>,
        val oldestUpdate: OffsetDateTime? = null,
        val notLoadedCount: Long,
        val itemsErrors: List<Throwable> = emptyList()
    ) : FeedState()

    data class ErrorState(
        val error: Throwable? = null,
        val previousContent: LoadedState? = null
    ) : FeedState()
}

internal object FeedRefreshUiPolicy {
    fun shouldKeepContentVisible(
        displayedItemCount: Int,
        previousItemCount: Int
    ): Boolean = displayedItemCount > 0 || previousItemCount > 0

    fun isIndeterminate(currentProgress: Int, maxProgress: Int): Boolean =
        currentProgress < 0 || maxProgress <= 0
}

internal class FeedRefreshStateCache {
    private var latestLoadedState: FeedState.LoadedState? = null
    private var latestShowPlayedItems: Boolean? = null

    fun needsSnapshot(showPlayedItems: Boolean): Boolean =
        latestLoadedState == null || latestShowPlayedItems != showPlayedItems

    fun remember(
        loadedState: FeedState.LoadedState,
        showPlayedItems: Boolean
    ): FeedState.LoadedState {
        latestLoadedState = loadedState
        latestShowPlayedItems = showPlayedItems
        return loadedState
    }

    fun progress(
        currentProgress: Int,
        maxProgress: Int,
        @StringRes progressMessage: Int
    ) = FeedState.ProgressState(
        currentProgress,
        maxProgress,
        progressMessage,
        latestLoadedState
    )

    fun error(error: Throwable?) = FeedState.ErrorState(error, latestLoadedState)
}
