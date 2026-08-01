package org.schabi.newpipe.local.cache;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.database.cache.model.CachedStreamEntity;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.views.NewPipeTextView;

import java.util.ArrayList;
import java.util.List;

public final class CachedStreamAdapter extends RecyclerView.Adapter<CachedStreamAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(@NonNull CachedStreamEntity entity);

        void onDelete(@NonNull CachedStreamEntity entity);
    }

    private final List<CachedStreamEntity> items = new ArrayList<>();
    private final Listener listener;

    public CachedStreamAdapter(@NonNull final Listener listener) {
        this.listener = listener;
    }

    public void submitList(@NonNull final List<CachedStreamEntity> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_cached_stream, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final CachedStreamEntity entity = items.get(position);
        holder.title.setText(entity.getTitle());

        // Utility.formatBytes is what the download UI uses, and unlike the previous
        // "bytes / 1024 / 1024 MB" it doesn't render every sub-megabyte entry as "0 MB".
        final String size = us.shandian.giga.util.Utility.formatBytes(entity.getTotalSizeBytes());
        final String uploader = entity.getUploaderName() == null ? "" : entity.getUploaderName();
        holder.subtitle.setText(uploader.isEmpty() ? size : uploader + " • " + size);

        PicassoHelper.loadThumbnail(entity.getThumbnailUrl()).into(holder.thumbnail);

        holder.itemView.setOnClickListener(v -> listener.onOpen(entity));
        holder.delete.setOnClickListener(v -> listener.onDelete(entity));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView thumbnail;
        private final NewPipeTextView title;
        private final NewPipeTextView subtitle;
        private final ImageButton delete;

        ViewHolder(@NonNull final View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.cache_item_thumbnail);
            title = itemView.findViewById(R.id.cache_item_title);
            subtitle = itemView.findViewById(R.id.cache_item_subtitle);
            delete = itemView.findViewById(R.id.cache_item_delete);
        }
    }
}
