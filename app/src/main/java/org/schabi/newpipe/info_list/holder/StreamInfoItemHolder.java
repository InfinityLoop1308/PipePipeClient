package org.schabi.newpipe.info_list.holder;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.local.cache.CacheManager;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.util.concurrent.TimeUnit;

import static org.schabi.newpipe.MainActivity.DEBUG;

/*
 * Created by Christian Schabesberger on 01.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * StreamInfoItemHolder.java is part of NewPipe.
 * </p>
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * </p>
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * </p?
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe. If not, see <http://www.gnu.org/licenses/>.
 * </p>
 */

public class StreamInfoItemHolder extends InfoItemHolder {
    public final ImageView itemThumbnailView;
    public final TextView itemVideoTitleView;
    public final TextView itemUploaderView;
    public final TextView itemDurationView;
    private final AnimatedProgressBar itemProgressView;
    public final TextView itemAdditionalDetails;
    private final ImageView itemCacheStatusView;
    private final TextView itemCacheProgressView;

    public StreamInfoItemHolder(final InfoItemBuilder infoItemBuilder, final ViewGroup parent) {
        this(infoItemBuilder, R.layout.list_stream_item, parent);
    }

    public StreamInfoItemHolder(final InfoItemBuilder infoItemBuilder, final int layoutId,
                                final ViewGroup parent) {
        super(infoItemBuilder, layoutId, parent);
        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemVideoTitleView = itemView.findViewById(R.id.itemVideoTitleView);
        itemUploaderView = itemView.findViewById(R.id.itemUploaderView);
        itemDurationView = itemView.findViewById(R.id.itemDurationView);
        itemProgressView = itemView.findViewById(R.id.itemProgressView);
        itemAdditionalDetails = itemView.findViewById(R.id.itemAdditionalDetails);
        itemCacheStatusView = itemView.findViewById(R.id.itemCacheStatusView);
        itemCacheProgressView = itemView.findViewById(R.id.itemCacheProgressView);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        itemVideoTitleView.setText(item.getName());
        itemUploaderView.setText(item.getUploaderName());

        if (item.requiresMembership()) {
            itemDurationView.setText(R.string.paid_video);
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.paid_video_background_color));
            itemDurationView.setVisibility(View.VISIBLE);
            itemProgressView.setVisibility(View.GONE);
        } else if (item.getDuration() > 0) {
            itemDurationView.setText(Localization.getDurationString(item.getDuration()));
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);

            final StreamStateEntity state = historyRecordManager.loadStreamState(infoItem)
                    .blockingGet()[0];
            if (state != null) {
                itemProgressView.setVisibility(View.VISIBLE);
                itemProgressView.setMax((int) item.getDuration());
                itemProgressView.setProgress((int) TimeUnit.MILLISECONDS
                        .toSeconds(state.getProgressMillis()));
            } else {
                itemProgressView.setVisibility(View.GONE);
            }
        } else if (item.getStreamType() == StreamType.LIVE_STREAM
                || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            itemDurationView.setText(R.string.duration_live);
            itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.getContext(),
                    R.color.live_duration_background_color));
            itemDurationView.setVisibility(View.VISIBLE);
            itemProgressView.setVisibility(View.GONE);
        } else {
            itemDurationView.setVisibility(View.GONE);
            itemProgressView.setVisibility(View.GONE);
        }

        PicassoHelper.loadScaledDownThumbnail(itemThumbnailView.getContext(), item.getThumbnailUrl())
                .into(itemThumbnailView);

        itemView.setOnClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().selected(item);
            }
        });

        switch (item.getStreamType()) {
            case AUDIO_STREAM:
            case VIDEO_STREAM:
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
                enableLongClick(item);
                break;
            case NONE:
            default:
                disableLongClick();
                break;
        }

        itemAdditionalDetails.setText(getStreamInfoDetailLine(item));

        if (itemCacheStatusView != null) {
            bindCacheStatus(itemCacheStatusView, itemCacheProgressView, item);
        }
    }

    static void bindCacheStatus(final ImageView cacheStatusView, final StreamInfoItem item) {
        bindCacheStatus(cacheStatusView, null, item);
    }

    /**
     * Shows the cache state on a list row: the offline pin once cached, or - while a cache
     * download is running - the live percentage, because a bare download arrow gives no sense of
     * whether anything is actually happening.
     *
     * @param cacheProgressView optional text badge for the percentage; when absent the icon is
     *                          used for the downloading state too
     */
    static void bindCacheStatus(final ImageView cacheStatusView,
                                final TextView cacheProgressView,
                                final StreamInfoItem item) {
        final Context context = cacheStatusView.getContext();
        final CacheManager.CacheDisplayState state = CacheManager.getCacheDisplayState(
                context, item.getServiceId(), item.getUrl());
        switch (state) {
            case CACHED:
                cacheStatusView.setVisibility(View.VISIBLE);
                cacheStatusView.setImageResource(R.drawable.ic_offline_pin);
                cacheStatusView.setContentDescription(
                        context.getString(R.string.cache_offline_badge_desc));
                if (cacheProgressView != null) {
                    cacheProgressView.setVisibility(View.GONE);
                }
                break;
            case DOWNLOADING:
                final int percent = CacheManager.getProgressBlocking(
                        item.getServiceId(), item.getUrl());
                final String label = percent == CacheManager.PROGRESS_PROCESSING
                        ? context.getString(R.string.cache_processing_short)
                        : percent + "%";
                if (cacheProgressView != null) {
                    // Percentage badge replaces the icon so the two don't overlap in the corner.
                    cacheStatusView.setVisibility(View.GONE);
                    cacheProgressView.setVisibility(View.VISIBLE);
                    cacheProgressView.setText(label);
                    cacheProgressView.setContentDescription(
                            context.getString(R.string.cache_downloading_badge_desc));
                } else {
                    cacheStatusView.setVisibility(View.VISIBLE);
                    cacheStatusView.setImageResource(R.drawable.ic_file_download);
                    cacheStatusView.setContentDescription(
                            context.getString(R.string.cache_downloading_badge_desc));
                }
                break;
            case NONE:
            default:
                cacheStatusView.setVisibility(View.GONE);
                if (cacheProgressView != null) {
                    cacheProgressView.setVisibility(View.GONE);
                }
                break;
        }
    }

    @Override
    public void updateState(final InfoItem infoItem,
                            final HistoryRecordManager historyRecordManager) {
        final StreamInfoItem item = (StreamInfoItem) infoItem;

        final StreamStateEntity state = historyRecordManager.loadStreamState(infoItem).blockingGet()[0];
        if (state != null && item.getDuration() > 0
                && item.getStreamType() != StreamType.LIVE_STREAM) {
            itemProgressView.setMax((int) item.getDuration());
            if (itemProgressView.getVisibility() == View.VISIBLE) {
                itemProgressView.setProgressAnimated((int) TimeUnit.MILLISECONDS
                        .toSeconds(state.getProgressMillis()));
            } else {
                itemProgressView.setProgress((int) TimeUnit.MILLISECONDS
                        .toSeconds(state.getProgressMillis()));
                ViewUtils.animate(itemProgressView, true, 500);
            }
        } else if (itemProgressView.getVisibility() == View.VISIBLE) {
            ViewUtils.animate(itemProgressView, false, 500);
        }
    }

    private String getStreamInfoDetailLine(final StreamInfoItem infoItem) {
        String viewsAndDate = "";
        if (infoItem.getViewCount() >= 0) {
            if (infoItem.getStreamType().equals(StreamType.AUDIO_LIVE_STREAM)) {
                viewsAndDate = Localization
                        .listeningCount(itemBuilder.getContext(), infoItem.getViewCount());
            } else if (infoItem.getStreamType().equals(StreamType.LIVE_STREAM)) {
                viewsAndDate = Localization
                        .shortWatchingCount(itemBuilder.getContext(), infoItem.getViewCount());
            } else {
                viewsAndDate = Localization
                        .shortViewCount(itemBuilder.getContext(), infoItem.getViewCount());
            }
        }

        final String uploadDate = getFormattedRelativeUploadDate(infoItem);
        if (!TextUtils.isEmpty(uploadDate)) {
            if (viewsAndDate.isEmpty()) {
                return uploadDate;
            }

            return Localization.concatenateStrings(viewsAndDate, uploadDate);
        }

        return viewsAndDate;
    }

    private String getFormattedRelativeUploadDate(final StreamInfoItem infoItem) {
        if (infoItem.getUploadDate() != null) {
            String formattedRelativeTime = Localization
                    .relativeTime(infoItem.getUploadDate().offsetDateTime());

            if (DEBUG && PreferenceManager.getDefaultSharedPreferences(itemBuilder.getContext())
                    .getBoolean(itemBuilder.getContext()
                            .getString(R.string.show_original_time_ago_key), false)) {
                formattedRelativeTime += " (" + infoItem.getTextualUploadDate() + ")";
            }
            return formattedRelativeTime;
        } else {
            return infoItem.getTextualUploadDate();
        }
    }

    private void enableLongClick(final StreamInfoItem item) {
        itemView.setLongClickable(true);
        itemView.setOnLongClickListener(view -> {
            if (itemBuilder.getOnStreamSelectedListener() != null) {
                itemBuilder.getOnStreamSelectedListener().held(item);
            }
            return true;
        });
    }

    private void disableLongClick() {
        itemView.setLongClickable(false);
        itemView.setOnLongClickListener(null);
    }
}
