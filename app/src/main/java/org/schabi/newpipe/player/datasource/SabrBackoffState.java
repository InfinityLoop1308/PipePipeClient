package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/** Client-owned, observable backoff gate shared by all readers of one SABR session. */
public final class SabrBackoffState {
    public interface Listener { void onBackoffChanged(long remainingMs); }

    private final Object monitor = new Object();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile long deadlineNs;

    long remainingMs() {
        final long remaining = deadlineNs - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, remaining / 1_000_000L);
    }

    void update(final int backoffMs) {
        deadlineNs = backoffMs <= 0 ? 0
                : System.nanoTime() + backoffMs * 1_000_000L;
        final long remaining = remainingMs();
        synchronized (monitor) { monitor.notifyAll(); }
        for (final Listener listener : listeners) {
            listener.onBackoffChanged(remaining);
        }
    }

    void awaitReady() throws InterruptedException {
        while (true) {
            final long remaining = remainingMs();
            if (remaining == 0) return;
            synchronized (monitor) { monitor.wait(Math.min(remaining, 250)); }
        }
    }

    void addListener(@NonNull final Listener listener) { listeners.add(listener); }
    void removeListener(@NonNull final Listener listener) { listeners.remove(listener); }
}
