package org.schabi.newpipe.info_list;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.databinding.PignateFooterBinding;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.holder.ChannelGridInfoItemHolder;
import org.schabi.newpipe.info_list.holder.ChannelInfoItemHolder;
import org.schabi.newpipe.info_list.holder.ChannelMiniInfoItemHolder;
import org.schabi.newpipe.info_list.holder.CommentsInfoItemHolder;
import org.schabi.newpipe.info_list.holder.CommentsMiniInfoItemHolder;
import org.schabi.newpipe.info_list.holder.ComposeInfoItemHolder;
import org.schabi.newpipe.info_list.holder.InfoItemHolder;
import org.schabi.newpipe.info_list.holder.PlaylistCardInfoItemHolder;
import org.schabi.newpipe.info_list.holder.PlaylistGridInfoItemHolder;
import org.schabi.newpipe.info_list.holder.PlaylistInfoItemHolder;
import org.schabi.newpipe.info_list.holder.PlaylistMiniInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StaffInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamCardInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamGridInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamInfoItemHolder;
import org.schabi.newpipe.info_list.holder.StreamMiniInfoItemHolder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.FallbackViewHolder;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.schabi.newpipe.util.ThemeHelper.isGrid;
import static org.schabi.newpipe.util.ThemeHelper.shouldUseExperimentalNewUi;

/*
 * Created by Christian Schabesberger on 01.08.16.
 *
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * InfoListAdapter.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class InfoListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = InfoListAdapter.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int HEADER_TYPE = 0;
    private static final int FOOTER_TYPE = 1;

    private static final int COMPOSE_HOLDER_TYPE = 2;
    private static final int MINI_STREAM_HOLDER_TYPE = 0x100;
    private static final int STREAM_HOLDER_TYPE = 0x101;
    private static final int GRID_STREAM_HOLDER_TYPE = 0x102;
    private static final int CARD_STREAM_HOLDER_TYPE = 0x103;
    private static final int MINI_CHANNEL_HOLDER_TYPE = 0x200;
    private static final int CHANNEL_HOLDER_TYPE = 0x201;
    private static final int GRID_CHANNEL_HOLDER_TYPE = 0x202;
    private static final int MINI_PLAYLIST_HOLDER_TYPE = 0x300;
    private static final int PLAYLIST_HOLDER_TYPE = 0x301;
    private static final int GRID_PLAYLIST_HOLDER_TYPE = 0x302;
    private static final int CARD_PLAYLIST_HOLDER_TYPE = 0x303;
    private static final int MINI_COMMENT_HOLDER_TYPE = 0x400;
    private static final int COMMENT_HOLDER_TYPE = 0x401;
    private static final int STAFF_TYPE = 0x816;

    private final LayoutInflater layoutInflater;
    private final InfoItemBuilder infoItemBuilder;
    private final List<InfoItem> infoItemList;
    private final List<InfoItem> filteredItemList = new ArrayList<>();
    private final List<InfoItem> sortedItemList = new ArrayList<>();
    private String filterText = "";
    @Nullable
    private java.util.Comparator<InfoItem> sortComparator = null;
    private boolean reverseOrigin = false;
    private final HistoryRecordManager recordManager;

    private boolean useMiniVariant = false;
    private boolean showFooter = false;

    private ItemViewMode itemMode = ItemViewMode.LIST;

    private Supplier<View> headerSupplier = null;
    private Supplier<View> footerSupplier = null;

    /** Payload used to only refresh the selection highlight of bound items. */
    public static final Object SELECTION_PAYLOAD = new Object();

    /** Tells the adapter which items are selected; null means selection is not supported. */
    public interface SelectionState {
        boolean isSelected(InfoItem item);
    }

    @Nullable
    private SelectionState selectionState;

    public void setSelectionState(@Nullable final SelectionState selectionState) {
        this.selectionState = selectionState;
    }

    /** Refreshes the selection highlight of all items without rebinding their content. */
    public void notifySelectionChanged() {
        notifyItemRangeChanged(0, getItemCount(), SELECTION_PAYLOAD);
    }

    private void bindSelection(final RecyclerView.ViewHolder holder, final InfoItem item) {
        final boolean selected = selectionState != null && selectionState.isSelected(item);
        if (selected) {
            final int accent = ThemeHelper.resolveColorFromAttr(holder.itemView.getContext(),
                    android.R.attr.colorAccent);
            // ~24% alpha overlay keeps the thumbnail and text readable
            holder.itemView.setForeground(new ColorDrawable((accent & 0x00FFFFFF) | 0x3D000000));
        } else {
            holder.itemView.setForeground(null);
        }
    }

    public void setFooterSupplier(@Nullable final Supplier<View> supplier) {
        footerSupplier = supplier;
        if (showFooter) {
            notifyItemChanged(sizeConsideringHeaderOffset());
        }
    }

    public InfoListAdapter(final Context context) {
        layoutInflater = LayoutInflater.from(context);
        recordManager = new HistoryRecordManager(context);
        infoItemBuilder = new InfoItemBuilder(context);
        infoItemList = new ArrayList<>();
    }

    public void setOnStreamSelectedListener(final OnClickGesture<StreamInfoItem> listener) {
        infoItemBuilder.setOnStreamSelectedListener(listener);
    }

    public void setOnChannelSelectedListener(final OnClickGesture<ChannelInfoItem> listener) {
        infoItemBuilder.setOnChannelSelectedListener(listener);
    }

    public void setOnPlaylistSelectedListener(final OnClickGesture<PlaylistInfoItem> listener) {
        infoItemBuilder.setOnPlaylistSelectedListener(listener);
    }

    public void setOnCommentsSelectedListener(final OnClickGesture<CommentsInfoItem> listener) {
        infoItemBuilder.setOnCommentsSelectedListener(listener);
    }

    public void setOnCommentsReplyListener(final OnClickGesture<CommentsInfoItem> listener) {
        infoItemBuilder.setOnCommentsReplyListener(listener);
    }

    public void setUseMiniVariant(final boolean useMiniVariant) {
        this.useMiniVariant = useMiniVariant;
    }

    public void setItemViewMode(final ItemViewMode itemViewMode) {
        this.itemMode = itemViewMode;
    }

    public void addInfoItemList(@Nullable final List<? extends InfoItem> data) {
        if (data == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "addInfoItemList() before > infoItemList.size() = "
                    + infoItemList.size() + ", data.size() = " + data.size());
        }

        if (!filterText.isEmpty() || isSorted()) {
            infoItemList.addAll(data);
            applyFilter();
            notifyDataSetChanged();
            return;
        }

        final int offsetStart = sizeConsideringHeaderOffset();
        infoItemList.addAll(data);

        if (DEBUG) {
            Log.d(TAG, "addInfoItemList() after > offsetStart = " + offsetStart + ", "
                    + "infoItemList.size() = " + infoItemList.size() + ", "
                    + "hasHeader = " + hasHeader() + ", "
                    + "showFooter = " + showFooter);
        }
        notifyItemRangeInserted(offsetStart, data.size());

        if (showFooter) {
            final int footerNow = sizeConsideringHeaderOffset();
            notifyItemMoved(offsetStart, footerNow);

            if (DEBUG) {
                Log.d(TAG, "addInfoItemList() footer from " + offsetStart
                        + " to " + footerNow);
            }
        }
    }

    public void setInfoItemList(final List<? extends InfoItem> data) {
        infoItemList.clear();
        infoItemList.addAll(data);
        applyFilter();
        notifyDataSetChanged();
    }

    /** Locally filters the loaded items by name (and uploader for streams). */
    public void filter(@NonNull final String text) {
        filterText = text.trim();
        applyFilter();
        notifyDataSetChanged();
    }

    /** Sorts loaded items with the comparator; null restores the original order. */
    public void setSortComparator(@Nullable final java.util.Comparator<InfoItem> comparator,
                                  final boolean reverseOriginOrder) {
        sortComparator = comparator;
        reverseOrigin = reverseOriginOrder;
        applyFilter();
        notifyDataSetChanged();
    }

    private boolean isSorted() {
        return sortComparator != null || reverseOrigin;
    }

    private void applyFilter() {
        filteredItemList.clear();
        sortedItemList.clear();
        if (sortComparator != null || reverseOrigin) {
            sortedItemList.addAll(infoItemList);
            if (reverseOrigin) {
                java.util.Collections.reverse(sortedItemList);
            } else {
                java.util.Collections.sort(sortedItemList, sortComparator);
            }
        }
        if (filterText.isEmpty()) {
            if (!sortedItemList.isEmpty()) {
                filteredItemList.addAll(sortedItemList);
            }
            return;
        }
        final String needle = filterText.toLowerCase();
        for (final InfoItem item : isSorted() ? sortedItemList : infoItemList) {
            final String name = item.getName();
            if (name != null && name.toLowerCase().contains(needle)) {
                filteredItemList.add(item);
            } else if (item instanceof StreamInfoItem) {
                final String uploader = ((StreamInfoItem) item).getUploaderName();
                if (uploader != null && uploader.toLowerCase().contains(needle)) {
                    filteredItemList.add(item);
                }
            }
        }
    }

    private List<InfoItem> shownList() {
        return filterText.isEmpty() && !isSorted() ? infoItemList : filteredItemList;
    }

    public void clearStreamItemList() {
        if (infoItemList.isEmpty()) {
            return;
        }
        infoItemList.clear();
        filteredItemList.clear();
        sortedItemList.clear();
        notifyDataSetChanged();
    }

    public void setHeaderSupplier(@Nullable final Supplier<View> headerSupplier) {
        final boolean changed = headerSupplier != this.headerSupplier;
        this.headerSupplier = headerSupplier;
        if (changed) {
            notifyDataSetChanged();
        }
    }

    protected boolean hasHeader() {
        return this.headerSupplier != null;
    }

    public void showFooter(final boolean show) {
        if (DEBUG) {
            Log.d(TAG, "showFooter() called with: show = [" + show + "]");
        }
        if (show == showFooter) {
            return;
        }

        showFooter = show;
        if (show) {
            notifyItemInserted(sizeConsideringHeaderOffset());
        } else {
            notifyItemRemoved(sizeConsideringHeaderOffset());
        }
    }

    private int sizeConsideringHeaderOffset() {
        final int i = shownList().size() + (hasHeader() ? 1 : 0);
        if (DEBUG) {
            Log.d(TAG, "sizeConsideringHeaderOffset() called → " + i);
        }
        return i;
    }

    public int getShownCount() {
        return shownList().size();
    }

    public List<InfoItem> getItemsList() {
        return isSorted() ? sortedItemList : infoItemList;
    }

    @Override
    public int getItemCount() {
        int count = shownList().size();
        if (hasHeader()) {
            count++;
        }
        if (showFooter) {
            count++;
        }

        if (DEBUG) {
            Log.d(TAG, "getItemCount() called with: "
                    + "count = " + count + ", infoItemList.size() = " + infoItemList.size() + ", "
                    + "hasHeader = " + hasHeader() + ", "
                    + "showFooter = " + showFooter);
        }
        return count;
    }

    @SuppressWarnings("FinalParameters")
    @Override
    public int getItemViewType(int position) {
        if (DEBUG) {
            Log.d(TAG, "getItemViewType() called with: position = [" + position + "]");
        }

        if (hasHeader() && position == 0) {
            return HEADER_TYPE;
        } else if (hasHeader()) {
            position--;
        }
        if (position == shownList().size() && showFooter) {
            return FOOTER_TYPE;
        }
        final InfoItem item = shownList().get(position);
        if (shouldUseExperimentalNewUi(layoutInflater.getContext())
                && item.getInfoType() != InfoItem.InfoType.COMMENT) {
            return COMPOSE_HOLDER_TYPE;
        }
        switch (item.getInfoType()) {
            case STREAM:
                if (itemMode == ItemViewMode.CARD) {
                    return CARD_STREAM_HOLDER_TYPE;
                } else if (isGrid(itemMode)) {
                    return GRID_STREAM_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_STREAM_HOLDER_TYPE;
                } else {
                    return STREAM_HOLDER_TYPE;
                }
            case CHANNEL:
                if (isGrid(itemMode)) {
                    return GRID_CHANNEL_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_CHANNEL_HOLDER_TYPE;
                } else {
                    return CHANNEL_HOLDER_TYPE;
                }
            case PLAYLIST:
                if (itemMode == ItemViewMode.CARD) {
                    return CARD_PLAYLIST_HOLDER_TYPE;
                } else if (isGrid(itemMode)) {
                    return GRID_PLAYLIST_HOLDER_TYPE;
                } else if (useMiniVariant) {
                    return MINI_PLAYLIST_HOLDER_TYPE;
                } else {
                    return PLAYLIST_HOLDER_TYPE;
                }
            case COMMENT:
                return useMiniVariant ? MINI_COMMENT_HOLDER_TYPE : COMMENT_HOLDER_TYPE;
            case STAFF:
                return STAFF_TYPE;
            default:
                return -1;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                      final int type) {
        if (DEBUG) {
            Log.d(TAG, "onCreateViewHolder() called with: "
                    + "parent = [" + parent + "], type = [" + type + "]");
        }
        switch (type) {
            // #4475 and #3368
            // Always create a new instance otherwise the same instance
            // is sometimes reused which causes a crash
            case HEADER_TYPE:
                return new HFHolder(headerSupplier.get());
            case FOOTER_TYPE:
                return new HFHolder(new android.widget.FrameLayout(parent.getContext()));
            case COMPOSE_HOLDER_TYPE:
                return new ComposeInfoItemHolder(infoItemBuilder, parent, itemMode);
            case MINI_STREAM_HOLDER_TYPE:
                return new StreamMiniInfoItemHolder(infoItemBuilder, parent);
            case STREAM_HOLDER_TYPE:
                return new StreamInfoItemHolder(infoItemBuilder, parent);
            case GRID_STREAM_HOLDER_TYPE:
                return new StreamGridInfoItemHolder(infoItemBuilder, parent);
            case CARD_STREAM_HOLDER_TYPE:
                return new StreamCardInfoItemHolder(infoItemBuilder, parent);
            case MINI_CHANNEL_HOLDER_TYPE:
                return new ChannelMiniInfoItemHolder(infoItemBuilder, parent);
            case CHANNEL_HOLDER_TYPE:
                return new ChannelInfoItemHolder(infoItemBuilder, parent);
            case GRID_CHANNEL_HOLDER_TYPE:
                return new ChannelGridInfoItemHolder(infoItemBuilder, parent);
            case MINI_PLAYLIST_HOLDER_TYPE:
                return new PlaylistMiniInfoItemHolder(infoItemBuilder, parent);
            case PLAYLIST_HOLDER_TYPE:
                return new PlaylistInfoItemHolder(infoItemBuilder, parent);
            case GRID_PLAYLIST_HOLDER_TYPE:
                return new PlaylistGridInfoItemHolder(infoItemBuilder, parent);
            case CARD_PLAYLIST_HOLDER_TYPE:
                return new PlaylistCardInfoItemHolder(infoItemBuilder, parent);
            case MINI_COMMENT_HOLDER_TYPE:
                return new CommentsMiniInfoItemHolder(infoItemBuilder, parent);
            case COMMENT_HOLDER_TYPE:
                return new CommentsInfoItemHolder(infoItemBuilder, parent);
            case STAFF_TYPE:
                return new StaffInfoItemHolder(infoItemBuilder, parent);
            default:
                return new FallbackViewHolder(new View(parent.getContext()));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                 final int position) {
        if (DEBUG) {
            Log.d(TAG, "onBindViewHolder() called with: "
                    + "holder = [" + holder.getClass().getSimpleName() + "], "
                    + "position = [" + position + "]");
        }
        if (getItemViewType(position) == FOOTER_TYPE) {
            final ViewGroup container = (ViewGroup) holder.itemView;
            container.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            container.removeAllViews();
            container.addView(footerSupplier == null
                    ? PignateFooterBinding.inflate(layoutInflater, container, false).getRoot()
                    : footerSupplier.get());
        }
        if (holder instanceof InfoItemHolder) {
            // If header is present, offset the items by -1
            final InfoItem item = shownList().get(hasHeader() ? position - 1 : position);
            ((InfoItemHolder) holder).updateFromItem(item, recordManager);
            bindSelection(holder, item);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder,
                                 final int position,
                                 @NonNull final List<Object> payloads) {
        if (payloads.contains(SELECTION_PAYLOAD)) {
            if (holder instanceof InfoItemHolder) {
                final int index = hasHeader() ? position - 1 : position;
                if (index >= 0 && index < shownList().size()) {
                    bindSelection(holder, shownList().get(index));
                }
            }
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    public GridLayoutManager.SpanSizeLookup getSpanSizeLookup(final int spanCount) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(final int position) {
                final int type = getItemViewType(position);
                return type == HEADER_TYPE || type == FOOTER_TYPE ? spanCount : 1;
            }
        };
    }

    static class HFHolder extends RecyclerView.ViewHolder {
        HFHolder(final View v) {
            super(v);
        }
    }
}
