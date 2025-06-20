package org.schabi.newpipe.views

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.util.ThemeHelper.getGridHeight
import org.schabi.newpipe.util.ThemeHelper.getGridWidth

class DynamicGridImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatImageView(context, attrs, defStyleAttr) {

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == context.getString(R.string.list_view_mode_key)) {
            applyPreferenceDimensions()
        }
    }

    init {
        applyPreferenceDimensions()
        // Register preference listener
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun applyPreferenceDimensions() {
        val width = getGridWidth(context)
        val height = getGridHeight(context)
        layoutParams = layoutParams?.apply {
            this.width = width
            this.height = height
        } ?: ViewGroup.LayoutParams(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ensure dimensions are applied when view is attached/reused
        applyPreferenceDimensions()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Unregister listener to prevent memory leaks
        PreferenceManager.getDefaultSharedPreferences(context)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}
