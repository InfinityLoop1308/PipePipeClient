/*
 * Copyright 2026 PipePipe contributors
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.subscription.services;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Notification;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class SubscriptionsFetchService extends BaseImportExportService {
    public static final String KEY_SUBSCRIPTION_IDS = "subscription_ids";

    private static final int PARALLEL_EXTRACTIONS = 8;

    private Subscription subscription;

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || subscription != null) {
            return START_NOT_STICKY;
        }

        final long[] subscriptionIds = intent.getLongArrayExtra(KEY_SUBSCRIPTION_IDS);
        if (subscriptionIds == null || subscriptionIds.length == 0) {
            stopService();
            return START_NOT_STICKY;
        }

        startFetching(subscriptionIds);
        return START_NOT_STICKY;
    }

    @Override
    protected int getNotificationId() {
        return 4569;
    }

    @Override
    public int getTitle() {
        return R.string.subscription_details_update_ongoing;
    }

    @Override
    protected void disposeAll() {
        super.disposeAll();
        if (subscription != null) {
            subscription.cancel();
        }
    }

    private void startFetching(final long[] subscriptionIds) {
        final List<Long> ids = new ArrayList<>(subscriptionIds.length);
        for (final long subscriptionId : subscriptionIds) {
            ids.add(subscriptionId);
        }

        eventListener.onSizeReceived(ids.size());
        Flowable.fromIterable(ids)
                .parallel(PARALLEL_EXTRACTIONS)
                .runOn(Schedulers.io())
                .map(this::fetchChannelInfo)
                .sequential()
                .observeOn(Schedulers.io())
                .doOnNext(this::storeChannelInfo)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getSubscriber());
    }

    private FetchResult fetchChannelInfo(final long subscriptionId) {
        final SubscriptionEntity entity =
                subscriptionManager.subscriptionTable().getSubscription(subscriptionId);
        if (entity == null) {
            return new FetchResult(subscriptionId, "", Notification.createOnError(
                    new IllegalStateException("Subscription not found: " + subscriptionId)));
        }

        try {
            final ChannelInfo info = ExtractorHelper.getChannelInfo(
                    entity.getServiceId(), entity.getUrl(), true).blockingGet();
            return new FetchResult(subscriptionId, entity.getName(),
                    Notification.createOnNext(info));
        } catch (final Throwable error) {
            return new FetchResult(subscriptionId, entity.getName(),
                    Notification.createOnError(error));
        }
    }

    private void storeChannelInfo(final FetchResult result) {
        eventListener.onItemCompleted(result.name);
        if (result.notification.isOnNext()) {
            subscriptionManager.updateChannelInfo(result.subscriptionId,
                    result.notification.getValue());
        } else {
            Log.w(TAG, "Failed to fetch subscription details for "
                    + result.subscriptionId, result.notification.getError());
        }
    }

    private Subscriber<FetchResult> getSubscriber() {
        return new Subscriber<FetchResult>() {
            @Override
            public void onSubscribe(final Subscription newSubscription) {
                subscription = newSubscription;
                newSubscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final FetchResult result) {
                // Progress and database updates are handled before switching to the main thread.
            }

            @Override
            public void onError(final Throwable error) {
                Log.e(TAG, "Failed to update subscription details", error);
                handleError(error);
            }

            @Override
            public void onComplete() {
                showToast(R.string.subscription_details_update_complete);
                stopService();
            }
        };
    }

    private void handleError(@NonNull final Throwable error) {
        super.handleError(R.string.subscription_details_update_failed, error);
    }

    private static final class FetchResult {
        private final long subscriptionId;
        private final String name;
        private final Notification<ChannelInfo> notification;

        private FetchResult(final long subscriptionId, final String name,
                            final Notification<ChannelInfo> notification) {
            this.subscriptionId = subscriptionId;
            this.name = name;
            this.notification = notification;
        }
    }
}
