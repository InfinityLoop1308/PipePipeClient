package org.schabi.newpipe.util

import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import org.schabi.newpipe.R

/**
 * Shows a text field in the action bar which locally filters the list of a fragment.
 */
class ToolbarFilterHelper(
    private val activity: AppCompatActivity,
    private val onFilterChanged: (String) -> Unit
) {
    val isActive: Boolean
        get() = activity.supportActionBar?.customView != null

    fun open() {
        val actionBar = activity.supportActionBar ?: return
        val customView = LayoutInflater.from(activity)
            .inflate(R.layout.local_playlist_search_toolbar, null, false)
        actionBar.setDisplayShowTitleEnabled(false)
        actionBar.setCustomView(
            customView,
            ActionBar.LayoutParams(ActionBar.LayoutParams.MATCH_PARENT, ActionBar.LayoutParams.MATCH_PARENT)
        )
        actionBar.setDisplayShowCustomEnabled(true)
        activity.invalidateOptionsMenu()

        val editText = customView.findViewById<EditText>(R.id.toolbar_search_edit_text_local)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onFilterChanged(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        customView.findViewById<View>(R.id.toolbar_search_clear_local).setOnClickListener {
            if (TextUtils.isEmpty(editText.text)) {
                close()
            } else {
                editText.setText("")
            }
        }
        editText.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        val actionBar = activity.supportActionBar ?: return
        if (actionBar.customView == null) {
            return
        }
        actionBar.customView = null
        actionBar.setDisplayShowCustomEnabled(false)
        actionBar.setDisplayShowTitleEnabled(true)
        activity.invalidateOptionsMenu()
        onFilterChanged("")
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }
}
