package org.schabi.newpipe.database.feed.model

object FeedGroupContentRules {

    fun effectiveSelection(
        defaultSelection: FeedContentSelection,
        override: FeedContentSelection?
    ): FeedContentSelection = override ?: defaultSelection

    fun normalizedOverride(
        defaultSelection: FeedContentSelection,
        override: FeedContentSelection
    ): FeedContentSelection? {
        return override.takeUnless { it == defaultSelection }
    }

    fun normalizeOverrides(
        defaultSelection: FeedContentSelection,
        subscriptionIds: Set<Long>,
        overrides: Map<Long, FeedContentSelection>
    ): Map<Long, FeedContentSelection> {
        return overrides.mapNotNull { (subscriptionId, override) ->
            if (subscriptionId !in subscriptionIds) {
                null
            } else {
                normalizedOverride(defaultSelection, override)?.let {
                    subscriptionId to it
                }
            }
        }.toMap()
    }
}
