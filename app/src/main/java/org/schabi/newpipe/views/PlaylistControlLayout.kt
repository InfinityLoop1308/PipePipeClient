package org.schabi.newpipe.views

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R

/**
 * Control bar (background / play all / popup). Call [configure] with the page it is shown on
 * so that it can be hidden according to the appearance setting.
 */
class PlaylistControlLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var disabled = false

    /** @param pageKey one of the values in R.array.list_control_pages_value_list */
    fun configure(pageKey: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = prefs.getStringSet(
            context.getString(R.string.list_control_pages_key),
            resources.getStringArray(R.array.list_control_pages_value_list).toSet()
        )
        disabled = enabled?.contains(pageKey) != true
        if (disabled) super.setVisibility(GONE)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(if (disabled) GONE else visibility)
    }
}
