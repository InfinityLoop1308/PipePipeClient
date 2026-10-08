package org.schabi.newpipe.util.dearrow

/**
 * What DeArrow's database says about one video.
 *
 * Both fields are nullable and independent: a video often has a community title but no community
 * thumbnail. A null field means "keep what YouTube gave us", never "blank".
 */
data class DeArrowBranding(
    val title: String?,
    val thumbnailTimestamp: Double?,
) {
    val isEmpty: Boolean
        get() = title == null && thumbnailTimestamp == null

    companion object {
        @JvmField
        val NONE = DeArrowBranding(null, null)
    }
}
