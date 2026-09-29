package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The videos and channels the user has told DeArrow to leave alone.
 *
 * <p>Crowdsourced titles are a net win and still wrong sometimes: a channel whose titles are
 * already honest gets nothing from replacement, and a video can attract a submission the user
 * simply disagrees with. Without an opt-out the only remedy is the master switch, which throws
 * away the feature everywhere to fix it in one place. This is the per-item remedy.</p>
 *
 * <p><b>An exclusion suppresses the whole feature for that item, not just the thumbnail.</b>
 * Title and thumbnail are two halves of one replacement — a row showing DeArrow's title over
 * the uploader's clickbait thumbnail is a worse result than either side alone, and "leave this
 * one alone" is what the user actually means.</p>
 *
 * <h2>Why a channel is matched on URL <em>and</em> name</h2>
 *
 * <p>The uploader URL is the stable identity and is the one stored first, but it is not always
 * present: several extractor paths leave {@code uploaderUrl} null on a list row and only fill
 * it in when the item is opened. The name is always there and is what the user recognises, so
 * both are recorded and either one matching is enough. A channel rename therefore drops the
 * name half silently — the URL half still matches — and two channels sharing a display name
 * are excluded together, which is the same trade the existing channel filter makes.</p>
 *
 * <p>Entries are stored as two {@code SharedPreferences} string sets under keys beginning
 * {@code dearrow_}, so the listener in {@link DeArrowSettings} drops the branding and frame
 * caches whenever one changes. That matters: without it, a video already cached with a DeArrow
 * title keeps showing it after being excluded, and the exclusion reads as ignored.</p>
 */
public final class DeArrowExclusions {

    /** Suffix of the companion key holding the excluded videos' titles. */
    private static final String LABELS_SUFFIX = "_labels";

    private static DeArrowExclusions instance;

    private final SharedPreferences prefs;
    private final String videoKey;
    private final String channelKey;

    /**
     * The last read sets, or null when they must be re-read.
     *
     * <p>Cached for the same reason {@link DeArrowSettings} caches its config: this is
     * consulted on every row bind, on the main thread, inside {@code onBindViewHolder}
     * during a fling.</p>
     */
    @Nullable
    private volatile Set<String> videoCache;
    @Nullable
    private volatile Set<String> channelCache;

    /**
     * The channel entries pre-split into the two things a bind actually compares against.
     *
     * <p>Built once per change rather than per row: matching by scanning the raw set would
     * split every stored entry on every bind, so a user with fifty excluded channels would
     * pay fifty string splits per row during a fling.</p>
     */
    @Nullable
    private volatile Set<String> channelUrlCache;
    @Nullable
    private volatile Set<String> channelNameCache;

    private DeArrowExclusions(@NonNull final Context context) {
        final Context app = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(app);
        this.videoKey = app.getString(R.string.dearrow_excluded_videos_key);
        this.channelKey = app.getString(R.string.dearrow_excluded_channels_key);
        this.prefs.registerOnSharedPreferenceChangeListener((p, key) -> {
            if (videoKey.equals(key)) {
                videoCache = null;
            } else if (channelKey.equals(key)) {
                channelCache = null;
                channelUrlCache = null;
                channelNameCache = null;
            }
        });
    }

    public static synchronized DeArrowExclusions getInstance(@NonNull final Context context) {
        if (instance == null) {
            instance = new DeArrowExclusions(context);
        }
        return instance;
    }

    /**
     * Whether DeArrow should do nothing at all for this row.
     *
     * @param videoId     the video's id, or null if the row is not a YouTube video
     * @param uploaderUrl the channel's URL, or null if this row does not carry one
     * @param uploaderName the channel's display name, or null
     * @return true if the user has excluded the video or its channel
     */
    public boolean isExcluded(@Nullable final String videoId,
                              @Nullable final String uploaderUrl,
                              @Nullable final String uploaderName) {
        if (videoId != null && videos().contains(videoId)) {
            return true;
        }
        if (uploaderUrl == null && uploaderName == null) {
            return false;
        }
        return DeArrowExclusionRules.matchesChannel(channelUrls(), channelNames(),
                uploaderUrl, uploaderName);
    }

    /** @return true if this exact video is excluded, ignoring its channel. */
    public boolean isVideoExcluded(@Nullable final String videoId) {
        return videoId != null && videos().contains(videoId);
    }

    /** @return true if the row's channel is excluded, ignoring the video. */
    public boolean isChannelExcluded(@Nullable final String uploaderUrl,
                                     @Nullable final String uploaderName) {
        return isExcluded(null, uploaderUrl, uploaderName);
    }

    /**
     * Adds or removes a single video.
     *
     * @param videoId the video
     * @param title   the title to show in the exclusions list; the uploader's, not DeArrow's,
     *                since a user scanning the list is looking for the video they recognise
     * @param excluded true to exclude, false to allow DeArrow again
     */
    public void setVideoExcluded(@NonNull final String videoId,
                                 @Nullable final String title,
                                 final boolean excluded) {
        final Set<String> updated = new HashSet<>(videos());
        final Set<String> labels = new HashSet<>(rawLabels());
        if (excluded) {
            updated.add(videoId);
            labels.removeIf(entry -> DeArrowExclusionRules.labelIsFor(videoId, entry));
            labels.add(DeArrowExclusionRules.encodeVideoLabel(videoId, title));
        } else {
            updated.remove(videoId);
            labels.removeIf(entry -> DeArrowExclusionRules.labelIsFor(videoId, entry));
        }
        prefs.edit()
                .putStringSet(videoKey, updated)
                .putStringSet(videoKey + LABELS_SUFFIX, labels)
                .apply();
        videoCache = updated;
    }

    /**
     * Adds or removes a whole channel.
     *
     * @param uploaderUrl  the channel URL, or null if the row did not carry one
     * @param uploaderName the channel's display name, or null
     * @param excluded     true to exclude, false to allow DeArrow again
     */
    public void setChannelExcluded(@Nullable final String uploaderUrl,
                                   @Nullable final String uploaderName,
                                   final boolean excluded) {
        if (DeArrowExclusionRules.normalizeUrl(uploaderUrl) == null
                && DeArrowExclusionRules.normalizeName(uploaderName) == null) {
            return;
        }
        final Set<String> updated = new HashSet<>(channels());
        // Removed first in both branches, so re-excluding a channel whose display name has
        // changed replaces the old entry instead of leaving two that match the same channel.
        updated.removeIf(entry ->
                DeArrowExclusionRules.entryIsChannel(uploaderUrl, uploaderName, entry));
        if (excluded) {
            updated.add(DeArrowExclusionRules.encodeChannel(uploaderUrl, uploaderName));
        }
        prefs.edit().putStringSet(channelKey, updated).apply();
        channelCache = updated;
        channelUrlCache = null;
        channelNameCache = null;
    }

    /** @return every excluded video, as {@code videoId} → the title to display. */
    @NonNull
    public List<Entry> listVideos() {
        final List<Entry> out = new ArrayList<>();
        final Set<String> labels = rawLabels();
        for (final String videoId : videos()) {
            out.add(new Entry(videoId, DeArrowExclusionRules.labelFor(videoId, labels)));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** @return every excluded channel, keyed by its stored entry so it can be removed again. */
    @NonNull
    public List<Entry> listChannels() {
        final List<Entry> out = new ArrayList<>();
        for (final String entry : channels()) {
            out.add(new Entry(entry, DeArrowExclusionRules.displayNameOf(entry)));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** Removes an entry returned by {@link #listVideos()}. */
    public void removeVideo(@NonNull final Entry entry) {
        setVideoExcluded(entry.id, null, false);
    }

    /** Removes an entry returned by {@link #listChannels()}, by its exact stored form. */
    public void removeChannel(@NonNull final Entry entry) {
        final Set<String> updated = new HashSet<>(channels());
        updated.remove(entry.id);
        prefs.edit().putStringSet(channelKey, updated).apply();
        channelCache = updated;
        channelUrlCache = null;
        channelNameCache = null;
    }

    /** Forgets every exclusion. */
    public void clear() {
        prefs.edit()
                .remove(videoKey)
                .remove(videoKey + LABELS_SUFFIX)
                .remove(channelKey)
                .apply();
        videoCache = null;
        channelCache = null;
        channelUrlCache = null;
        channelNameCache = null;
    }

    /** @return true when nothing is excluded, so the settings entry can say so. */
    public boolean isEmpty() {
        return videos().isEmpty() && channels().isEmpty();
    }

    @NonNull
    private Set<String> videos() {
        Set<String> snapshot = videoCache;
        if (snapshot == null) {
            snapshot = prefs.getStringSet(videoKey, Collections.emptySet());
            videoCache = snapshot;
        }
        return snapshot;
    }

    @NonNull
    private Set<String> channels() {
        Set<String> snapshot = channelCache;
        if (snapshot == null) {
            snapshot = prefs.getStringSet(channelKey, Collections.emptySet());
            channelCache = snapshot;
        }
        return snapshot;
    }

    @NonNull
    private Set<String> channelUrls() {
        Set<String> snapshot = channelUrlCache;
        if (snapshot == null) {
            snapshot = fieldsOf(0);
            channelUrlCache = snapshot;
        }
        return snapshot;
    }

    @NonNull
    private Set<String> channelNames() {
        Set<String> snapshot = channelNameCache;
        if (snapshot == null) {
            snapshot = fieldsOf(1);
            channelNameCache = snapshot;
        }
        return snapshot;
    }

    /**
     * @param index which field of a stored channel entry to collect
     * @return every non-empty value of that field across the stored channels
     */
    @NonNull
    private Set<String> fieldsOf(final int index) {
        return DeArrowExclusionRules.fieldsOf(channels(), index);
    }

    /**
     * The video titles, kept in their own set.
     *
     * <p>Separate from the id set so that a lookup — the hot path, run per row — is a plain
     * {@code Set.contains} on ids rather than a scan that has to split every entry.</p>
     *
     * @return raw {@code id + separator + title} strings
     */
    @NonNull
    private Set<String> rawLabels() {
        return prefs.getStringSet(videoKey + LABELS_SUFFIX, Collections.emptySet());
    }


    /** One row of the exclusions list: what to remove, and what to call it on screen. */
    public static final class Entry {
        /** The stored form, which is what removal matches on. */
        @NonNull
        public final String id;
        /** What to show the user. */
        @NonNull
        public final String label;

        Entry(@NonNull final String id, @NonNull final String label) {
            this.id = id;
            this.label = label;
        }
    }
}
