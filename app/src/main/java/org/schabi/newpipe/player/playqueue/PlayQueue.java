package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.events.AppendEvent;
import org.schabi.newpipe.player.playqueue.events.ErrorEvent;
import org.schabi.newpipe.player.playqueue.events.InitEvent;
import org.schabi.newpipe.player.playqueue.events.MoveEvent;
import org.schabi.newpipe.player.playqueue.events.PlayQueueEvent;
import org.schabi.newpipe.player.playqueue.events.RecoveryEvent;
import org.schabi.newpipe.player.playqueue.events.RemoveEvent;
import org.schabi.newpipe.player.playqueue.events.ReorderEvent;
import org.schabi.newpipe.player.playqueue.events.SelectEvent;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;

/**
 * PlayQueue is responsible for keeping track of a list of streams and the index of
 * the stream that should be currently playing.
 * <p>
 * This class contains basic manipulation of a playlist while also functions as a
 * message bus, providing all listeners with new updates to the play queue.
 * </p>
 * <p>
 * This class can be serialized for passing intents, but in order to start the
 * message bus, it must be initialized.
 * </p>
 */
public abstract class PlayQueue implements Serializable {
    public static final boolean DEBUG = MainActivity.DEBUG;

    /**
     * The recovery position of an entry that has no saved playback progress.
     */
    public static final long RECOVERY_UNSET = Long.MIN_VALUE;

    @NonNull
    private final AtomicInteger queueIndex;
    private final List<PlayerMediaItem> history = new ArrayList<>();

    private List<PlayerMediaItem> backup;
    private List<PlayerMediaItem> streams;

    /**
     * Recovery positions of the queue entries, keyed by their stable uuid. This is per-slot
     * playback state, so it is owned by the queue instead of the immutable media item.
     */
    private final Map<String, Long> recoveryPositions = new HashMap<>();

    /**
     * The uuid of the entry that was enqueued automatically, or null when the queue tail was
     * added by the user. Auto-enqueuing only ever appends a single entry at the tail.
     */
    @Nullable
    private String autoQueuedUuid;

    private transient BehaviorSubject<PlayQueueEvent> eventBroadcast;
    private transient Flowable<PlayQueueEvent> broadcastReceiver;
    private transient boolean disposed = false;

    PlayQueue(final int index, final List<PlayerMediaItem> startWith) {
        streams = new ArrayList<>(startWith);

        if (streams.size() > index) {
            history.add(streams.get(index));
        }

        queueIndex = new AtomicInteger(index);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playlist actions
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Initializes the play queue message buses.
     * <p>
     * Also starts a self reporter for logging if debug mode is enabled.
     * </p>
     */
    public void init() {
        eventBroadcast = BehaviorSubject.create();

        broadcastReceiver = eventBroadcast.toFlowable(BackpressureStrategy.BUFFER)
                .observeOn(AndroidSchedulers.mainThread())
                .startWithItem(new InitEvent());
    }

    /**
     * Dispose the play queue by stopping all message buses.
     */
    public void dispose() {
        if (eventBroadcast != null) {
            eventBroadcast.onComplete();
        }

        eventBroadcast = null;
        broadcastReceiver = null;
        disposed = true;
    }

    /**
     * Checks if the queue is complete.
     * <p>
     * A queue is complete if it has loaded all items in an external playlist
     * single stream or local queues are always complete.
     * </p>
     *
     * @return whether the queue is complete
     */
    public abstract boolean isComplete();

    /**
     * Load partial queue in the background, does nothing if the queue is complete.
     */
    public abstract void fetch();

    /*//////////////////////////////////////////////////////////////////////////
    // Readonly ops
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * @return the current index that should be played
     */
    public int getIndex() {
        return queueIndex.get();
    }

    /**
     * Changes the current playing index to a new index.
     * <p>
     * This method is guarded using in a circular manner for index exceeding the play queue size.
     * </p>
     * <p>
     * Will emit a {@link SelectEvent} if the index is not the current playing index.
     * </p>
     *
     * @param index the index to be set
     */
    public synchronized void setIndex(final int index) {
        final int oldIndex = getIndex();

        final int newIndex;

        if (index < 0) {
            newIndex = 0;
        } else if (index < streams.size()) {
            // Regular assignment for index in bounds
            newIndex = index;
        } else if (streams.isEmpty()) {
            // Out of bounds from here on
            // Need to check if stream is empty to prevent arithmetic error and negative index
            newIndex = 0;
        } else if (isComplete()) {
            // Circular indexing
            newIndex = index % streams.size();
        } else {
            // Index of last element
            newIndex = streams.size() - 1;
        }

        queueIndex.set(newIndex);

        if (oldIndex != newIndex) {
            history.add(streams.get(newIndex));
        }

        /*
        TODO: Documentation states that a SelectEvent will only be emitted if the new index is...
        different from the old one but this is emitted regardless? Not sure what this what it does
        exactly so I won't touch it
         */
        broadcast(new SelectEvent(oldIndex, newIndex));
    }

    /**
     * @return the current item that should be played, or null if the queue is empty
     */
    @Nullable
    public PlayerMediaItem getItem() {
        return getItem(getIndex());
    }

    /**
     * @param index the index of the item to return
     * @return the item at the given index, or null if the index is out of bounds
     */
    @Nullable
    public PlayerMediaItem getItem(final int index) {
        if (index < 0 || index >= streams.size()) {
            return null;
        }
        return streams.get(index);
    }

    /**
     * Returns the index of the given item using its stable {@link PlayerMediaItem#getUuid() uuid}.
     * This keeps working after serialization instead of relying on referential equality.
     *
     * @param item the item to find the index of
     * @return the index of the given item, or -1 if it is not in the queue
     */
    public int indexOf(@NonNull final PlayerMediaItem item) {
        for (int i = 0; i < streams.size(); i++) {
            if (streams.get(i).getUuid().equals(item.getUuid())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return the current size of play queue.
     */
    public int size() {
        return streams.size();
    }

    /**
     * Checks if the play queue is empty.
     *
     * @return whether the play queue is empty
     */
    public boolean isEmpty() {
        return streams.isEmpty();
    }

    /**
     * Determines if the current play queue is shuffled.
     *
     * @return whether the play queue is shuffled
     */
    public boolean isShuffled() {
        return backup != null;
    }

    /**
     * @return an immutable view of the play queue
     */
    @NonNull
    public List<PlayerMediaItem> getStreams() {
        return Collections.unmodifiableList(streams);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Write ops
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Returns the play queue's update broadcast.
     * May be null if the play queue message bus is not initialized.
     *
     * @return the play queue's update broadcast
     */
    @Nullable
    public Flowable<PlayQueueEvent> getBroadcastReceiver() {
        return broadcastReceiver;
    }

    /**
     * Changes the current playing index by an offset amount.
     * <p>
     * Will emit a {@link SelectEvent} if offset is non-zero.
     * </p>
     *
     * @param offset the offset relative to the current index
     */
    public synchronized void offsetIndex(final int offset) {
        setIndex(getIndex() + offset);
    }

    /**
     * Appends the given {@link PlayerMediaItem}s to the current play queue.
     *
     * @see #append(List items)
     * @param items {@link PlayerMediaItem}s to append
     */
    public synchronized void append(@NonNull final PlayerMediaItem... items) {
        append(Arrays.asList(items));
    }

    /**
     * Appends the given {@link PlayerMediaItem}s to the current play queue.
     * <p>
     * If the play queue is shuffled, then append the items to the backup queue as is and
     * append the shuffle items to the play queue.
     * </p>
     * <p>
     * Will emit a {@link AppendEvent} on any given context.
     * </p>
     *
     * @param items {@link PlayerMediaItem}s to append
     */
    public synchronized void append(@NonNull final List<PlayerMediaItem> items) {
        appendInternal(items, false);
    }

    /**
     * Appends entries the player chose automatically (related streams, next partition, ...).
     * The tail is remembered so that a later user action can drop it again.
     *
     * @param items {@link PlayerMediaItem}s to append
     */
    public synchronized void appendAutoQueued(@NonNull final List<PlayerMediaItem> items) {
        appendInternal(items, true);
    }

    private synchronized void appendInternal(@NonNull final List<PlayerMediaItem> items,
                                             final boolean autoQueued) {
        final List<PlayerMediaItem> itemList = new ArrayList<>(items);

        if (isShuffled()) {
            backup.addAll(itemList);
            Collections.shuffle(itemList);
        }
        if (!itemList.isEmpty() && !streams.isEmpty() && autoQueuedUuid != null
                && autoQueuedUuid.equals(streams.get(streams.size() - 1).getUuid())
                && !autoQueued) {
            // A user enqueue overrides the speculative auto-enqueued tail.
            streams.remove(streams.size() - 1);
            autoQueuedUuid = null;
        }
        streams.addAll(itemList);
        if (autoQueued && !itemList.isEmpty()) {
            autoQueuedUuid = itemList.get(itemList.size() - 1).getUuid();
        }

        broadcast(new AppendEvent(itemList.size()));
    }

    /**
     * Removes the item at the given index from the play queue.
     * <p>
     * The current playing index will decrement if it is greater than the index being removed.
     * On cases where the current playing index exceeds the playlist range, it is set to 0.
     * </p>
     * <p>
     * Will emit a {@link RemoveEvent} if the index is within the play queue index range.
     * </p>
     *
     * @param index the index of the item to remove
     */
    public synchronized void remove(final int index) {
        if (index >= streams.size() || index < 0) {
            return;
        }
        removeInternal(index);
        broadcast(new RemoveEvent(index, getIndex()));
    }

    /**
     * Report an exception for the item at the current index in order and skip to the next one
     * <p>
     * This is done as a separate event as the underlying manager may have
     * different implementation regarding exceptions.
     * </p>
     */
    public synchronized void error() {
        final int oldIndex = getIndex();
        remove(oldIndex);
        broadcast(new ErrorEvent(oldIndex, getIndex()));
    }

    private synchronized void removeInternal(final int removeIndex) {
        final int currentIndex = queueIndex.get();
        final int size = size();

        if (currentIndex > removeIndex) {
            queueIndex.decrementAndGet();

        } else if (currentIndex >= size) {
            queueIndex.set(currentIndex % (size - 1));

        } else if (currentIndex == removeIndex && currentIndex == size - 1) {
            queueIndex.set(0);
        }

        final PlayerMediaItem removedItem = streams.get(removeIndex);
        if (backup != null) {
            backup.remove(removedItem);
        }

        recoveryPositions.remove(removedItem.getUuid());
        if (removedItem.getUuid().equals(autoQueuedUuid)) {
            autoQueuedUuid = null;
        }

        history.remove(streams.remove(removeIndex));
        if (streams.size() > queueIndex.get()) {
            history.add(streams.get(queueIndex.get()));
        }
    }

    /**
     * Moves a queue item at the source index to the target index.
     * <p>
     * If the item being moved is the currently playing, then the current playing index is set
     * to that of the target.
     * If the moved item is not the currently playing and moves to an index <b>AFTER</b> the
     * current playing index, then the current playing index is decremented.
     * Vice versa if the an item after the currently playing is moved <b>BEFORE</b>.
     * </p>
     *
     * @param source the original index of the item
     * @param target the new index of the item
     */
    public synchronized void move(final int source, final int target) {
        if (source < 0 || target < 0) {
            return;
        }
        if (source >= streams.size() || target >= streams.size()) {
            return;
        }

        final int current = getIndex();
        if (source == current) {
            queueIndex.set(target);
        } else if (source < current && target >= current) {
            queueIndex.decrementAndGet();
        } else if (source > current && target <= current) {
            queueIndex.incrementAndGet();
        }

        final PlayerMediaItem playQueueItem = streams.remove(source);
        // Moving an entry by hand makes it a deliberate user choice, no longer an auto-enqueue.
        if (playQueueItem.getUuid().equals(autoQueuedUuid)) {
            autoQueuedUuid = null;
        }
        streams.add(target, playQueueItem);
        broadcast(new MoveEvent(source, target));
    }

    /**
     * Sets the recovery record of the item at the index.
     * <p>
     * Broadcasts a recovery event.
     * </p>
     *
     * @param index    index of the item
     * @param position the recovery position
     */
    public synchronized void setRecovery(final int index, final long position) {
        if (index < 0 || index >= streams.size()) {
            return;
        }

        recoveryPositions.put(streams.get(index).getUuid(), position);
        broadcast(new RecoveryEvent(index, position));
    }

    /**
     * @param item the entry to look up
     * @return the saved recovery position of the given entry, or {@link #RECOVERY_UNSET}
     */
    public synchronized long getRecoveryPosition(@NonNull final PlayerMediaItem item) {
        return recoveryPositions.getOrDefault(item.getUuid(), RECOVERY_UNSET);
    }

    /**
     * @param index the index of the entry to look up
     * @return the saved recovery position of the entry at the index, or {@link #RECOVERY_UNSET}
     */
    public synchronized long getRecoveryPosition(final int index) {
        if (index < 0 || index >= streams.size()) {
            return RECOVERY_UNSET;
        }
        return getRecoveryPosition(streams.get(index));
    }

    /**
     * Revoke the recovery record of the item at the index.
     * <p>
     * Broadcasts a recovery event.
     * </p>
     *
     * @param index index of the item
     */
    public synchronized void unsetRecovery(final int index) {
        setRecovery(index, RECOVERY_UNSET);
    }

    /**
     * Shuffles the current play queue
     * <p>
     * This method first backs up the existing play queue and item being played. Then a newly
     * shuffled play queue will be generated along with currently playing item placed at the
     * beginning of the queue. This item will also be added to the history.
     * </p>
     * <p>
     * Will emit a {@link ReorderEvent} if shuffled.
     * </p>
     *
     * @implNote Does nothing if the queue has a size <= 2 (the currently playing video must stay on
     * top, so shuffling a size-2 list does nothing)
     */
    public synchronized void shuffle() {
        // Create a backup if it doesn't already exist
        // Note: The backup-list has to be created at all cost (even when size <= 2).
        // Otherwise it's not possible to enter shuffle-mode!
        if (backup == null) {
            backup = new ArrayList<>(streams);
        }
        // Can't shuffle a list that's empty or only has one element
        if (size() <= 2) {
            return;
        }

        final int originalIndex = getIndex();
        final PlayerMediaItem currentItem = getItem();

        Collections.shuffle(streams);

        // Move currentItem to the head of the queue
        streams.remove(currentItem);
        streams.add(0, currentItem);
        queueIndex.set(0);

        history.add(currentItem);

        broadcast(new ReorderEvent(originalIndex, 0));
    }

    /**
     * Unshuffles the current play queue if a backup play queue exists.
     * <p>
     * This method undoes shuffling and index will be set to the previously playing item if found,
     * otherwise, the index will reset to 0.
     * </p>
     * <p>
     * Will emit a {@link ReorderEvent} if a backup exists.
     * </p>
     */
    public synchronized void unshuffle() {
        if (backup == null) {
            return;
        }
        final int originIndex = getIndex();
        final PlayerMediaItem current = getItem();

        streams = backup;
        backup = null;

        final int newIndex = streams.indexOf(current);
        if (newIndex != -1) {
            queueIndex.set(newIndex);
        } else {
            queueIndex.set(0);
        }
        if (streams.size() > queueIndex.get()) {
            history.add(streams.get(queueIndex.get()));
        }

        broadcast(new ReorderEvent(originIndex, queueIndex.get()));
    }

    /**
     * Selects previous played item.
     *
     * This method removes currently playing item from history and
     * starts playing the last item from history if it exists
     *
     * @return true if history is not empty and the item can be played
     * */
    public synchronized boolean previous() {
        if (history.size() <= 1) {
            return false;
        }

        history.remove(history.size() - 1);

        final PlayerMediaItem last = history.remove(history.size() - 1);
        setIndex(indexOf(last));

        return true;
    }

    /*
     * Compares two PlayQueues. Useful when a user switches players but queue is the same so
     * we don't have to do anything with new queue.
     * This method also gives a chance to track history of items in a queue in
     * VideoDetailFragment without duplicating items from two identical queues
     */
    @Override
    public boolean equals(@Nullable final Object obj) {
        if (!(obj instanceof PlayQueue)) {
            return false;
        }
        final PlayQueue other = (PlayQueue) obj;
        if (size() != other.size() || getIndex() != other.getIndex() ) {
            return false;
        }
        for (int i = 0; i < size(); i++) {
            final PlayerMediaItem stream = streams.get(i);
            final PlayerMediaItem otherStream = other.streams.get(i);
            // Check is based on serviceId and URL
            if (stream.getServiceId() != otherStream.getServiceId()
                    || !stream.getUrl().equals(otherStream.getUrl())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        // Must agree with equals(), which compares the index and the content of every entry.
        int result = getIndex();
        for (final PlayerMediaItem item : streams) {
            result = 31 * result + item.getServiceId();
            result = 31 * result + item.getUrl().hashCode();
        }
        return result;
    }

    public boolean isDisposed() {
        return disposed;
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Rx Broadcast
    //////////////////////////////////////////////////////////////////////////*/

    private void broadcast(@NonNull final PlayQueueEvent event) {
        if (eventBroadcast != null) {
            eventBroadcast.onNext(event);
        }
    }
}

