package org.schabi.newpipe.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import org.schabi.newpipe.BuildConfig
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.ActivityAboutBinding
import org.schabi.newpipe.databinding.FragmentAboutBinding
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper
import org.schabi.newpipe.util.external_communication.ShareUtils

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.title_activity_about_new)

        val aboutBinding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(aboutBinding.root)
        setSupportActionBar(aboutBinding.aboutToolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val mAboutStateAdapter = AboutStateAdapter(this)
        aboutBinding.aboutViewPager2.adapter = mAboutStateAdapter
        TabLayoutMediator(
            aboutBinding.aboutTabLayout,
            aboutBinding.aboutViewPager2
        ) { tab, position ->
            tab.setText(mAboutStateAdapter.getPageTitle(position))
        }.attach()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class AboutFragment : Fragment() {
        private fun Button.openLink(url: Int) {
            setOnClickListener {
                ShareUtils.openUrlInBrowser(
                    context,
                    requireContext().getString(url),
                    false
                )
            }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            FragmentAboutBinding.inflate(inflater, container, false).apply {
                aboutAppVersion.text = BuildConfig.VERSION_NAME
                aboutGithubLink.openLink(R.string.github_url)
                aboutDonationLink.openLink(R.string.donation_url)
                return root
            }
        }
    }

    private class AboutStateAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val posAbout = 0
        private val totalCount = 1

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                posAbout -> AboutFragment()
                else -> throw IllegalArgumentException("Unknown position for ViewPager2")
            }
        }

        override fun getItemCount(): Int {
            return totalCount
        }

        fun getPageTitle(position: Int): Int {
            return when (position) {
                posAbout -> R.string.tab_about
                else -> throw IllegalArgumentException("Unknown position for ViewPager2")
            }
        }
    }
}
