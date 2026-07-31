package org.schabi.newpipe;

import android.app.Activity;
import android.app.Application;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.connection.CarConnection;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.multidex.MultiDexApplication;
import androidx.preference.PreferenceManager;

import com.jakewharton.processphoenix.ProcessPhoenix;


import org.acra.ACRA;
import org.acra.config.CoreConfigurationBuilder;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.ktx.ExceptionUtils;
import org.schabi.newpipe.player.datasource.LocalDomPoTokenProvider;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import io.reactivex.rxjava3.exceptions.CompositeException;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.schabi.newpipe.MainActivity.DEBUG;

/*
 * Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
 * App.java is part of NewPipe.
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

public class App extends MultiDexApplication {
    public static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    private static final String TAG = App.class.toString();
    private static final String YOUTUBE_WEB_CLIENT_NAME = "WEB";
    private static final String YOUTUBE_MWEB_CLIENT_NAME = "MWEB";
    private static final String YOUTUBE_ANDROID_VR_CLIENT_NAME = "ANDROID_VR";
    private static App app;

    private CarConnectionStateReceiver carConnectionReceiver;

    @NonNull
    public static App getApp() {
        return app;
    }

    @Override
    protected void attachBaseContext(final Context base) {
        super.attachBaseContext(base);
        initACRA();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;

        if (ACRA.isACRASenderServiceProcess()) {
            Log.i(TAG, "This is the ACRA sender process! "
                    + "Aborting initialization of App[onCreate]");
            return;
        }

        EdgeToEdgeWorkaround.apply();

        if (ProcessPhoenix.isPhoenixProcess(this)) {
            Log.i(TAG, "This is a phoenix process! "
                    + "Aborting initialization of App[onCreate]");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerCarConnectionReceiver();
            CarConnection carConnection = new CarConnection(this);
            carConnection.getType().observeForever(new androidx.lifecycle.Observer<Integer>() {
                @Override
                public void onChanged(Integer connectionState) {
                    boolean isConnected = (connectionState != null && connectionState != CarConnection.CONNECTION_TYPE_NOT_CONNECTED);
                    Log.d(TAG, "Initial check: Is car connected? " + isConnected);
                    CarConnectionStateReceiver.setCarConnectionState(isConnected);
                    carConnection.getType().removeObserver(this);
                }
            });
        }

        // Initialize settings first because others inits can use its values
        NewPipeSettings.initSettings(this);

        // Initialize Android Auto component state based on preference
        DeviceUtils.updateAndroidAutoComponentState(this);

        NewPipe.init(getDownloader(),
            Localization.getPreferredLocalization(this),
            Localization.getPreferredContentCountry(this));
        final LocalDomPoTokenProvider sessionPoTokenProvider =
                LocalDomPoTokenProvider.shared(this);
        NewPipe.setYoutubeSessionPoTokenProvider(sessionPoTokenProvider);
        final AndroidWebViewAvailabilityChecker webViewAvailabilityChecker =
                new AndroidWebViewAvailabilityChecker(this);
        NewPipe.setWebViewAvailabilityChecker(webViewAvailabilityChecker);
        webViewAvailabilityChecker.warmUp();
        final WebViewJavaScriptDecoder decoder = new WebViewJavaScriptDecoder(this);
        YoutubeApiDecoder.setLocalDecoder(decoder);
        scheduleYoutubeDecoderPrewarm(decoder);

        Localization.initPrettyTime(Localization.resolvePrettyTime(getApplicationContext()));

        StateSaver.init(this);
        initNotificationChannels();

        ServiceHelper.initServices(this);

        // Initialize image loader
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final String youtubePlayerClientKey = getString(R.string.youtube_player_client_key);
        final String[] youtubePlayerClients = getResources()
                .getStringArray(R.array.youtube_player_client_values);
        final boolean hasYouTubeLogin = !TextUtils.isEmpty(prefs.getString(
                getString(R.string.youtube_cookies_key), null));
        final String defaultYoutubePlayerClient = hasYouTubeLogin
                ? "tv_downgraded" : "mweb";
        String youtubePlayerClient = prefs.getString(youtubePlayerClientKey,
                defaultYoutubePlayerClient);
        boolean isYoutubePlayerClientValid = false;
        for (final String client : youtubePlayerClients) {
            if (client.equals(youtubePlayerClient)) {
                isYoutubePlayerClientValid = true;
                break;
            }
        }
        if (!isYoutubePlayerClientValid) {
            youtubePlayerClient = defaultYoutubePlayerClient;
            prefs.edit().putString(youtubePlayerClientKey, youtubePlayerClient).apply();
        }
        if ((hasYouTubeLogin && !"tv_downgraded".equals(youtubePlayerClient)
                && !"mweb".equals(youtubePlayerClient))
                || (!hasYouTubeLogin && "tv_downgraded".equals(youtubePlayerClient))) {
            youtubePlayerClient = defaultYoutubePlayerClient;
            prefs.edit().putString(youtubePlayerClientKey, youtubePlayerClient).apply();
        }
        NewPipe.setYoutubePlayerClient(youtubePlayerClient);
        prewarmYoutubeSessionPoToken(this);
        PicassoHelper.init(this);
        PicassoHelper.setShouldLoadImages(
                prefs.getBoolean(getString(R.string.download_thumbnail_key), true));
        PicassoHelper.setIndicatorsEnabled(DEBUG
                && prefs.getBoolean(getString(R.string.show_image_indicators_key), false));

        configureRxJavaErrorHandler();
    }

    private void scheduleYoutubeDecoderPrewarm(final WebViewJavaScriptDecoder decoder) {
        registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private boolean scheduled;

            @Override
            public void onActivityResumed(@NonNull final Activity activity) {
                if (scheduled) {
                    return;
                }
                scheduled = true;
                unregisterActivityLifecycleCallbacks(this);
                activity.getWindow().getDecorView().postDelayed(
                        () -> Schedulers.io().scheduleDirect(decoder::prewarm), 1_000);
            }

            @Override
            public void onActivityCreated(@NonNull final Activity activity,
                                          final Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull final Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull final Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity activity,
                                                    @NonNull final Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity activity) {
            }
        });
    }

    public static void prewarmYoutubeSessionPoToken(@NonNull final Context context) {
        final LocalDomPoTokenProvider provider = LocalDomPoTokenProvider.shared(context);
        provider.cancelSessionPoTokenPrewarm();
        try {
            final YoutubePoTokenClientContext client = resolveYoutubePoTokenClientContext(
                    NewPipe.getYoutubePlayerClient());
            if (client == null) {
                return;
            }
            provider.prewarmSessionPoToken(client.clientName, client.userAgent,
                    YoutubeParsingHelper.getPlayerRequestLocalization(),
                    ServiceList.YouTube.getContentCountry(), ServiceList.YouTube.hasTokens(),
                    client.clientVersionResolver);
        } catch (final RuntimeException e) {
            Log.w(TAG, "Could not schedule YouTube session PO token prewarm", e);
        }
    }

    private static YoutubePoTokenClientContext resolveYoutubePoTokenClientContext(
            @NonNull final String selectedClient) {
        switch (selectedClient) {
            case "mweb":
                return new YoutubePoTokenClientContext(YOUTUBE_MWEB_CLIENT_NAME,
                        YoutubeParsingHelper::getClientVersion,
                        YoutubeParsingHelper.MWEB_USER_AGENT);
            case "web":
                return new YoutubePoTokenClientContext(YOUTUBE_WEB_CLIENT_NAME,
                        YoutubeParsingHelper::getClientVersion,
                        YoutubeParsingHelper.WEB_USER_AGENT);
            case "web_safari":
                return new YoutubePoTokenClientContext(YOUTUBE_WEB_CLIENT_NAME,
                        () -> "2.20260114.08.00",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                                + "Version/15.5 Safari/605.1.15,gzip(gfe)");
            case "android_vr":
                return new YoutubePoTokenClientContext(YOUTUBE_ANDROID_VR_CLIENT_NAME,
                        () -> "1.65.10",
                        "com.google.android.apps.youtube.vr.oculus/1.65.10 "
                                + "(Linux; U; Android 12L; eureka-user "
                                + "Build/SQ3A.220605.009.A1) gzip");
            case "tv_simply":
                return new YoutubePoTokenClientContext("TVHTML5_SIMPLY", () -> "1.0",
                        YoutubeParsingHelper.WEB_USER_AGENT);
            case "tv_downgraded":
                return new YoutubePoTokenClientContext("TVHTML5", () -> "5.20260114",
                        "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version");
            default:
                return null;
        }
    }

    private static final class YoutubePoTokenClientContext {
        @NonNull private final String clientName;
        @NonNull private final Callable<String> clientVersionResolver;
        @NonNull private final String userAgent;

        private YoutubePoTokenClientContext(@NonNull final String clientName,
                                            @NonNull final Callable<String> clientVersionResolver,
                                            @NonNull final String userAgent) {
            this.clientName = clientName;
            this.clientVersionResolver = clientVersionResolver;
            this.userAgent = userAgent;
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        PicassoHelper.terminate();
        if (carConnectionReceiver != null) {
            unregisterReceiver(carConnectionReceiver);
        }
    }

    private void registerCarConnectionReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            carConnectionReceiver = new CarConnectionStateReceiver();
            IntentFilter filter = new IntentFilter(
                    "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ requires explicit export flag
                registerReceiver(carConnectionReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(carConnectionReceiver, filter);
            }
            Log.d("CarConnectionReceiver", "Receiver registered dynamically");
        }
    }


    protected Downloader getDownloader() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final DownloaderImpl downloader = DownloaderImpl.init(null, prefs.getBoolean(
                getString(R.string.use_dns_over_https_fallback_key), false));
        setCookiesToDownloader(downloader);
        return downloader;
    }

    protected void setCookiesToDownloader(final DownloaderImpl downloader) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());
        final String key = getApplicationContext().getString(R.string.recaptcha_cookies_key);
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, prefs.getString(key, null));
        downloader.updateYoutubeRestrictedModeCookies(getApplicationContext());
    }

    private void configureRxJavaErrorHandler() {
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(new Consumer<Throwable>() {
            @Override
            public void accept(@NonNull final Throwable throwable) {
                Log.e(TAG, "RxJavaPlugins.ErrorHandler called with -> : "
                        + "throwable = [" + throwable.getClass().getName() + "]");

                final Throwable actualThrowable;
                if (throwable instanceof UndeliverableException) {
                    // As UndeliverableException is a wrapper,
                    // get the cause of it to get the "real" exception
                    actualThrowable = throwable.getCause();
                } else {
                    actualThrowable = throwable;
                }

                final List<Throwable> errors;
                if (actualThrowable instanceof CompositeException) {
                    errors = ((CompositeException) actualThrowable).getExceptions();
                } else {
                    errors = Collections.singletonList(actualThrowable);
                }

                for (final Throwable error : errors) {
                    if (isThrowableIgnored(error)) {
                        return;
                    }
                    if (isThrowableCritical(error)) {
                        reportException(error);
                        return;
                    }
                }

                // Out-of-lifecycle exceptions should only be reported if a debug user wishes so,
                // When exception is not reported, log it
                if (isDisposedRxExceptionsReported()) {
                    reportException(actualThrowable);
                } else {
//                    Log.e(TAG, "RxJavaPlugin: Undeliverable Exception received: ", actualThrowable);
                }
            }

            private boolean isThrowableIgnored(@NonNull final Throwable throwable) {
                // Don't crash the application over a simple network problem
                return ExceptionUtils.hasAssignableCause(throwable,
                        // network api cancellation
                        IOException.class, SocketException.class,
                        // blocking code disposed
                        InterruptedException.class, InterruptedIOException.class);
            }

            private boolean isThrowableCritical(@NonNull final Throwable throwable) {
                // Though these exceptions cannot be ignored
                return ExceptionUtils.hasAssignableCause(throwable,
                        NullPointerException.class, IllegalArgumentException.class, // bug in app
                        OnErrorNotImplementedException.class, MissingBackpressureException.class,
                        IllegalStateException.class); // bug in operator
            }

            private void reportException(@NonNull final Throwable throwable) {
                // Throw uncaught exception that will trigger the report system
                Thread.currentThread().getUncaughtExceptionHandler()
                        .uncaughtException(Thread.currentThread(), throwable);
            }
        });
    }

    /**
     * Called in {@link #attachBaseContext(Context)} after calling the {@code super} method.
     * Should be overridden if MultiDex is enabled, since it has to be initialized before ACRA.
     */
    protected void initACRA() {
        if (ACRA.isACRASenderServiceProcess()) {
            return;
        }

        final CoreConfigurationBuilder acraConfig = new CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig.class);

        if (isJobSenderServiceAvailable(this)) {
            ACRA.init(this, acraConfig);
        }
    }

    public static boolean isJobSenderServiceAvailable(Context context) {
        Intent intent = new Intent(context, org.acra.sender.JobSenderService.class);
        ResolveInfo resolveInfo = context.getPackageManager().resolveService(intent, 0);
        return resolveInfo != null;
    }


    private void initNotificationChannels() {
        // Keep the importance below DEFAULT to avoid making noise on every notification update for
        // the main and update channels
        final List<NotificationChannelCompat> notificationChannelCompats = new ArrayList<>();
        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.notification_channel_id),
                        NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_name))
                .setDescription(getString(R.string.notification_channel_description))
                .build());

        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.app_update_notification_channel_id),
                        NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.app_update_notification_channel_name))
                .setDescription(getString(R.string.app_update_notification_channel_description_new))
                .build());

        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.hash_channel_id),
                        NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.hash_channel_name))
                .setDescription(getString(R.string.hash_channel_description))
                .build());

        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.error_report_channel_id),
                        NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.error_report_channel_name))
                .setDescription(getString(R.string.error_report_channel_description))
                .build());

        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.streams_notification_channel_id),
                    NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(R.string.streams_notification_channel_name))
                .setDescription(getString(R.string.streams_notification_channel_description))
                .build());

        notificationChannelCompats.add(new NotificationChannelCompat
                .Builder(getString(R.string.sabr_backoff_notification_channel_id),
                        NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(getString(R.string.sabr_backoff_notification_channel_name))
                .setDescription(getString(
                        R.string.sabr_backoff_notification_channel_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build());

        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannelsCompat(notificationChannelCompats);
    }

    protected boolean isDisposedRxExceptionsReported() {
        return false;
    }

}
