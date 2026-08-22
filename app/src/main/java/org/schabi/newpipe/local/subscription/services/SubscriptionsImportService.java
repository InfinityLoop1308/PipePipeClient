/*
 * Copyright 2018 Mauricio Colli <mauriciocolli@outlook.com>
 * SubscriptionsImportService.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.subscription.services;

import static org.schabi.newpipe.MainActivity.DEBUG;
import static org.schabi.newpipe.streams.io.StoredFileHelper.DEFAULT_MIME;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.App;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.subscription.SubscriptionItem;
import org.schabi.newpipe.streams.io.SharpInputStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import org.schabi.newpipe.util.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class SubscriptionsImportService extends BaseImportExportService {
    public static final int CHANNEL_URL_MODE = 0;
    public static final int INPUT_STREAM_MODE = 1;
    public static final int PREVIOUS_EXPORT_MODE = 2;
    public static final String KEY_MODE = "key_mode";
    public static final String KEY_VALUE = "key_value";
    public static final String KEY_INSERTED_SUBSCRIPTION_IDS = "inserted_subscription_ids";

    /**
     * A {@link LocalBroadcastManager local broadcast} will be made with this action
     * when the import is successfully completed.
     */
    public static final String IMPORT_COMPLETE_ACTION = App.PACKAGE_NAME + ".local.subscription"
            + ".services.SubscriptionsImportService.IMPORT_COMPLETE";

    private Subscription subscription;
    private long[] insertedSubscriptionIds = new long[0];
    private int currentMode;
    private int currentServiceId;
    @Nullable
    private String channelUrl;
    @Nullable
    private InputStream inputStream;
    @Nullable
    private String inputStreamType;

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null || subscription != null) {
            return START_NOT_STICKY;
        }

        currentMode = intent.getIntExtra(KEY_MODE, -1);
        currentServiceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, Constants.NO_SERVICE_ID);

        if (currentMode == CHANNEL_URL_MODE) {
            channelUrl = intent.getStringExtra(KEY_VALUE);
        } else {
            final Uri uri = intent.getParcelableExtra(KEY_VALUE);
            if (uri == null) {
                stopAndReportError(new IllegalStateException(
                        "Importing from input stream, but file path is null"),
                        "Importing subscriptions");
                return START_NOT_STICKY;
            }

            try {
                final StoredFileHelper fileHelper = new StoredFileHelper(this, uri, DEFAULT_MIME);
                inputStream = new SharpInputStream(fileHelper.getStream());
                inputStreamType = fileHelper.getType();

                if (inputStreamType == null || inputStreamType.equals(DEFAULT_MIME)) {
                    // mime type could not be determined, just take file extension
                    final String name = fileHelper.getName();
                    final int pointIndex = name.lastIndexOf('.');
                    if (pointIndex == -1 || pointIndex >= name.length() - 1) {
                        inputStreamType = DEFAULT_MIME; // no extension, will fail in the extractor
                    } else {
                        inputStreamType = name.substring(pointIndex + 1);
                    }
                }
            } catch (final IOException e) {
                handleError(e);
                return START_NOT_STICKY;
            }
        }

        if (currentMode == -1 || currentMode == CHANNEL_URL_MODE && channelUrl == null) {
            final String errorDescription = "Some important field is null or in illegal state: "
                    + "currentMode=[" + currentMode + "], "
                    + "channelUrl=[" + channelUrl + "], "
                    + "inputStream=[" + inputStream + "]";
            stopAndReportError(new IllegalStateException(errorDescription),
                    "Importing subscriptions");
            return START_NOT_STICKY;
        }

        startImport();
        return START_NOT_STICKY;
    }

    @Override
    protected int getNotificationId() {
        return 4568;
    }

    @Override
    public int getTitle() {
        return R.string.import_ongoing;
    }

    @Override
    protected void disposeAll() {
        super.disposeAll();
        if (subscription != null) {
            subscription.cancel();
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Imports
    //////////////////////////////////////////////////////////////////////////*/

    private void startImport() {
        showToast(R.string.import_ongoing);

        Flowable<List<SubscriptionItem>> flowable = null;
        switch (currentMode) {
            case CHANNEL_URL_MODE:
                flowable = importFromChannelUrl();
                break;
            case INPUT_STREAM_MODE:
                flowable = importFromInputStream();
                break;
            case PREVIOUS_EXPORT_MODE:
                flowable = importFromPreviousExport();
                break;
        }

        if (flowable == null) {
            final String message = "Flowable given by \"importFrom\" is null "
                    + "(current mode: " + currentMode + ")";
            stopAndReportError(new IllegalStateException(message), "Importing subscriptions");
            return;
        }

        flowable.doOnNext(subscriptionItems ->
                eventListener.onSizeReceived(subscriptionItems.size()))
                .observeOn(Schedulers.io())
                .map(subscriptionItems -> {
                    final List<SubscriptionEntity> inserted =
                            subscriptionManager.insertAll(subscriptionItems);
                    for (final SubscriptionItem item : subscriptionItems) {
                        eventListener.onItemCompleted(item.getName());
                    }
                    return inserted;
                })

                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(getSubscriber());
    }

    private Subscriber<List<SubscriptionEntity>> getSubscriber() {
        return new Subscriber<List<SubscriptionEntity>>() {
            @Override
            public void onSubscribe(final Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final List<SubscriptionEntity> successfulInserted) {
                insertedSubscriptionIds = new long[successfulInserted.size()];
                for (int i = 0; i < successfulInserted.size(); i++) {
                    insertedSubscriptionIds[i] = successfulInserted.get(i).getUid();
                }
                if (DEBUG) {
                    Log.d(TAG, "startImport() " + successfulInserted.size()
                            + " items successfully inserted into the database");
                }
            }

            @Override
            public void onError(final Throwable error) {
                Log.e(TAG, "Got an error!", error);
                handleError(error);
            }

            @Override
            public void onComplete() {
                LocalBroadcastManager.getInstance(SubscriptionsImportService.this)
                        .sendBroadcast(new Intent(IMPORT_COMPLETE_ACTION)
                                .putExtra(KEY_INSERTED_SUBSCRIPTION_IDS,
                                        insertedSubscriptionIds));
                showToast(R.string.import_complete_toast);
                stopService();
            }
        };
    }

    private Flowable<List<SubscriptionItem>> importFromChannelUrl() {
        return Flowable.fromCallable(() -> NewPipe.getService(currentServiceId)
                .getSubscriptionExtractor()
                .fromChannelUrl(channelUrl));
    }

    private Flowable<List<SubscriptionItem>> importFromInputStream() {
        Objects.requireNonNull(inputStream);
        Objects.requireNonNull(inputStreamType);

        return Flowable.fromCallable(() -> NewPipe.getService(currentServiceId)
                .getSubscriptionExtractor()
                .fromInputStream(inputStream, inputStreamType));
    }

    private Flowable<List<SubscriptionItem>> importFromPreviousExport() {
        return Flowable.fromCallable(() -> ImportExportJsonHelper.readFrom(inputStream, null));
    }

    protected void handleError(@NonNull final Throwable error) {
        super.handleError(R.string.subscriptions_import_unsuccessful, error);
    }
}
