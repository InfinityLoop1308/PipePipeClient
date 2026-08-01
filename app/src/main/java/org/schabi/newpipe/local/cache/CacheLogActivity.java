package org.schabi.newpipe.local.cache;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ActivityCacheLogBinding;
import org.schabi.newpipe.util.external_communication.ShareUtils;

/**
 * Shows the rolling debug log kept by {@link CacheLogger}, so a user hitting a silent failure in
 * the "cache for offline viewing" feature (issue #2782 follow-up) can screenshot or share it
 * without needing adb/logcat access.
 */
public final class CacheLogActivity extends AppCompatActivity {

    private ActivityCacheLogBinding binding;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        org.schabi.newpipe.util.ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);

        binding = ActivityCacheLogBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbarLayout.toolbar);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.cache_debug_log_title);
        }

        CacheLogger.loadFromDisk(getApplicationContext());
        refreshLog();
    }

    private void refreshLog() {
        binding.cacheLogText.setText(CacheLogger.getLogText());
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_cache_log, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (id == R.id.menu_item_cache_log_share) {
            ShareUtils.shareText(this, getString(R.string.cache_debug_log_title),
                    CacheLogger.getLogText());
            return true;
        } else if (id == R.id.menu_item_cache_log_clear) {
            CacheLogger.clear(getApplicationContext());
            refreshLog();
            return true;
        } else if (id == R.id.menu_item_cache_log_refresh) {
            CacheLogger.loadFromDisk(getApplicationContext());
            refreshLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
