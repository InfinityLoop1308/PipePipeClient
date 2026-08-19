package org.schabi.newpipe.local.cache;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.databinding.ActivityCacheBinding;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ThemeHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Lists everything cached via the "cache for offline viewing" feature (issue #2782), so users
 * can jump back into a cached video or free up space by removing one.
 */
public final class CacheActivity extends AppCompatActivity implements CachedStreamAdapter.Listener {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private CachedStreamAdapter adapter;
    private ActivityCacheBinding binding;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        ThemeHelper.setTheme(this);
        super.onCreate(savedInstanceState);

        binding = ActivityCacheBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbarLayout.toolbar);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.cached_videos_title);
        }

        adapter = new CachedStreamAdapter(this);
        binding.cacheList.setLayoutManager(new LinearLayoutManager(this));
        binding.cacheList.setAdapter(adapter);

        disposables.add(org.schabi.newpipe.NewPipeDatabase.getInstance(getApplicationContext())
                .cachedStreamDAO()
                .getAll()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(list -> {
                    adapter.submitList(list);
                    binding.cacheEmptyView.setVisibility(
                            list.isEmpty() ? View.VISIBLE : View.GONE);
                    binding.cacheList.setVisibility(
                            list.isEmpty() ? View.GONE : View.VISIBLE);
                }));

        // Rows for in-progress downloads show a percentage, which only the in-memory progress
        // map knows about - the database row doesn't change as bytes arrive, so Room won't
        // re-emit. Re-bind on progress updates so the percentage actually counts up.
        disposables.add(CacheManager.cacheProgress
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(event -> adapter.notifyDataSetChanged()));
    }

    @Override
    public void onOpen(@NonNull final CachedStreamEntity entity) {
        NavigationHelper.openVideoDetail(this,
                entity.getServiceId(), entity.getUrl(), entity.getTitle(), null, false);
    }

    @Override
    public void onDelete(@NonNull final CachedStreamEntity entity) {
        disposables.add(Completable
                .fromAction(() -> CacheManager.removeCache(this, entity))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe());
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }
}
