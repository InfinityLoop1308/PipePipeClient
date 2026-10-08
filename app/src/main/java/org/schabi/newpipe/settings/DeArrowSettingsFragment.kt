package org.schabi.newpipe.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import org.schabi.newpipe.R
import org.schabi.newpipe.util.dearrow.DeArrowImages
import org.schabi.newpipe.util.dearrow.DeArrowRepository

/**
 * Settings for DeArrow, the crowdsourced replacement for clickbait titles and thumbnails.
 *
 * Every switch depends on the master toggle, which is off by default: an untouched install makes
 * no DeArrow request at all.
 */
class DeArrowSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        findPreference<Preference>(getString(R.string.dearrow_home_page_key))
            ?.setOnPreferenceClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.dearrow_homepage_url)))
                )
                true
            }

        findPreference<Preference>(getString(R.string.dearrow_clear_cache_key))
            ?.setOnPreferenceClickListener {
                DeArrowRepository.clear()
                DeArrowImages.clear()
                Toast.makeText(context, R.string.dearrow_cache_cleared_toast, Toast.LENGTH_SHORT)
                    .show()
                true
            }
    }
}
