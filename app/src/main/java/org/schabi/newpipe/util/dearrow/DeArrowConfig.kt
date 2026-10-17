package org.schabi.newpipe.util.dearrow

/**
 * The user's DeArrow preferences.
 *
 * Kept a plain value object so the rest of the feature never reads SharedPreferences directly.
 */
data class DeArrowConfig(
    val enabled: Boolean,
    val replaceTitles: Boolean,
    val replaceThumbnails: Boolean,
    val frameFallback: Boolean,
    val autoFormatTitles: Boolean,
    val originalFallback: Boolean,
) {
    companion object {
        /** The shipped default: nothing happens until the user opts in. */
        @JvmField
        val DISABLED = DeArrowConfig(false, false, false, false, false, false)
    }
}
