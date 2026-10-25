package org.schabi.newpipe.database.feed.model

enum class FeedContentType(val mask: Int) {
    VIDEOS(1),
    SHORTS(2),
    LIVE(4)
}

class FeedContentSelection private constructor(val mask: Int) {

    fun contains(contentType: FeedContentType): Boolean = mask and contentType.mask != 0

    fun intersects(other: FeedContentSelection): Boolean = mask and other.mask != 0

    fun union(other: FeedContentSelection): FeedContentSelection = fromMask(mask or other.mask)

    override fun equals(other: Any?): Boolean {
        return other is FeedContentSelection && mask == other.mask
    }

    override fun hashCode(): Int = mask

    override fun toString(): String = "FeedContentSelection(mask=$mask)"

    companion object {
        private const val KNOWN_MASK = 7

        val VIDEOS = of(FeedContentType.VIDEOS)
        val SHORTS = of(FeedContentType.SHORTS)
        val LIVE = of(FeedContentType.LIVE)
        val ALL = fromMask(KNOWN_MASK)

        fun of(vararg contentTypes: FeedContentType): FeedContentSelection {
            return fromMask(contentTypes.fold(0) { mask, contentType ->
                mask or contentType.mask
            })
        }

        fun fromMask(mask: Int): FeedContentSelection {
            require(mask != 0 && mask and KNOWN_MASK == mask) {
                "Feed content mask must contain at least one known content type: $mask"
            }

            return FeedContentSelection(mask)
        }
    }
}
