package us.shandian.giga.get;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.streams.io.SharpStream;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.nio.channels.ClosedByInterruptException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import us.shandian.giga.util.Utility;

import static org.schabi.newpipe.BuildConfig.DEBUG;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_AUTH;
import static us.shandian.giga.get.DownloadMission.ERROR_HTTP_FORBIDDEN;
import org.schabi.newpipe.extractor.utils.SubtitleDeduplicator;

public class DownloadInitializer extends Thread {
    private final static String TAG = "DownloadInitializer";
    final static int mId = 0;
    private final static int RESERVE_SPACE_DEFAULT = 5 * 1024 * 1024;// 5 MiB
    private final static int RESERVE_SPACE_MAXIMUM = 150 * 1024 * 1024;// 150 MiB

    private final DownloadMission mMission;
    private HttpURLConnection mConn;

    DownloadInitializer(@NonNull DownloadMission mission) {
        mMission = mission;
        mConn = null;
    }

    private void dispose() {
        try {
            mConn.getInputStream().close();
        } catch (Exception e) {
            // nothing to do
        }
    }

    @Override
    public void run() {
        if (mMission.current > 0) mMission.resetState(false, true, DownloadMission.ERROR_NOTHING);

        int retryCount = 0;
        int httpCode = 204;

        //process local, for example: file://
        for (int i = 0; i < mMission.urls.length && mMission.running; i++) {
            String currentUrl = mMission.urls[i];

            if (false == isLocalSubtitleUrl(currentUrl)) {
                // do nothing
            } else {
                int result = handleLocalSubtitle(currentUrl);
                if (0 == result) {
                    printLocalSubtitleStoredOk();
                } else {
                    Log.e(TAG, "Fail to handle localSubtitle Url. error=" + result);
                }

                // There is only urls[0] for subtitle,
                // so return directly after processing the urls[0].
                return;
            }
        }

        // process remote, for example: http:// or https://
        while (true) {
            try {
                if (mMission.blocks == null && mMission.current == 0) {
                    // calculate the whole size of the mission
                    long finalLength = 0;
                    long lowestSize = Long.MAX_VALUE;

                    for (int i = 0; i < mMission.urls.length && mMission.running; i++) {

                        mConn = mMission.openConnection(mMission.urls[i], true, 0, 0);
                        mMission.establishConnection(mId, mConn);
                        dispose();

                        if (Thread.interrupted()) return;
                        long length = Utility.getTotalContentLength(mConn);

                        if (i == 0) {
                            httpCode = mConn.getResponseCode();
                            mMission.length = length;
                        }

                        if (length > 0) finalLength += length;
                        if (length < lowestSize) lowestSize = length;
                    }

                    mMission.nearLength = finalLength;

                    // reserve space at the start of the file
                    if (mMission.psAlgorithm != null && mMission.psAlgorithm.reserveSpace) {
                        if (lowestSize < 1) {
                            // the length is unknown use the default size
                            mMission.offsets[0] = RESERVE_SPACE_DEFAULT;
                        } else {
                            // use the smallest resource size to download, otherwise, use the maximum
                            mMission.offsets[0] = lowestSize < RESERVE_SPACE_MAXIMUM ? lowestSize : RESERVE_SPACE_MAXIMUM;
                        }
                    }
                } else {
                    // ask for the current resource length
                    mConn = mMission.openConnection(true, 0, 0);
                    mMission.establishConnection(mId, mConn);
                    dispose();

                    if (!mMission.running || Thread.interrupted()) return;

                    httpCode = mConn.getResponseCode();
                    mMission.length = Utility.getTotalContentLength(mConn);
                }

                if (mMission.length == 0 || httpCode == 204) {
                    mMission.notifyError(DownloadMission.ERROR_HTTP_NO_CONTENT, null);
                    return;
                }

                // check for dynamic generated content
                if (mMission.length == -1 && mConn.getResponseCode() == 200) {
                    mMission.blocks = new int[0];
                    mMission.length = 0;
                    mMission.unknownLength = true;

                    if (DEBUG) {
                        Log.d(TAG, "falling back (unknown length)");
                    }
                } else {
                    // Open again
                    mConn = mMission.openConnection(true, mMission.length - 10, mMission.length);
                    mMission.establishConnection(mId, mConn);
                    dispose();

                    if (!mMission.running || Thread.interrupted()) return;

                    synchronized (mMission.LOCK) {
                        if (mConn.getResponseCode() == 206) {

                            if (mMission.threadCount > 1) {
                                int count = (int) (mMission.length / DownloadMission.BLOCK_SIZE);
                                if ((count * DownloadMission.BLOCK_SIZE) < mMission.length) count++;

                                mMission.blocks = new int[count];
                            } else {
                                // if one thread is required don't calculate blocks, is useless
                                mMission.blocks = new int[0];
                                mMission.unknownLength = false;
                            }

                            if (DEBUG) {
                                Log.d(TAG, "http response code = " + mConn.getResponseCode());
                            }
                        } else {
                            // Fallback to single thread
                            mMission.blocks = new int[0];
                            mMission.unknownLength = false;

                            if (DEBUG) {
                                Log.d(TAG, "falling back due http response code = " + mConn.getResponseCode());
                            }
                        }
                    }

                    if (!mMission.running || Thread.interrupted()) return;
                }

                try (SharpStream fs = mMission.storage.getStream()) {
                    fs.setLength(mMission.offsets[mMission.current] + mMission.length);
                    fs.seek(mMission.offsets[mMission.current]);
                }

                if (!mMission.running || Thread.interrupted()) return;

                if (!mMission.unknownLength && mMission.recoveryInfo != null) {
                    String entityTag = mConn.getHeaderField("ETAG");
                    String lastModified = mConn.getHeaderField("Last-Modified");
                    MissionRecoveryInfo recovery = mMission.recoveryInfo[mMission.current];

                    if (!TextUtils.isEmpty(entityTag)) {
                        recovery.setValidateCondition(entityTag);
                    } else if (!TextUtils.isEmpty(lastModified)) {
                        recovery.setValidateCondition(lastModified);// Note: this is less precise
                    } else {
                        recovery.setValidateCondition(null);
                    }
                }

                mMission.running = false;
                break;
            } catch (InterruptedIOException | ClosedByInterruptException e) {
                return;
            } catch (Exception e) {
                if (!mMission.running || super.isInterrupted()) return;

                if (e instanceof DownloadMission.HttpError && ((((DownloadMission.HttpError) e).statusCode == ERROR_HTTP_FORBIDDEN)
                        || ((DownloadMission.HttpError) e).statusCode == ERROR_HTTP_AUTH)) {
                    // for youtube streams. The url has expired
                    interrupt();
                    mMission.doRecover(ERROR_HTTP_FORBIDDEN);
                    return;
                }

                if (e instanceof IOException && e.getMessage().contains("Permission denied")) {
                    mMission.notifyError(DownloadMission.ERROR_PERMISSION_DENIED, e);
                    return;
                }

                if (retryCount++ > mMission.maxRetry) {
                    Log.e(TAG, "initializer failed", e);
                    mMission.notifyError(e);
                    return;
                }

                Log.e(TAG, "initializer failed, retrying", e);
            }
        }

        mMission.start();
    }

    @Override
    public void interrupt() {
        super.interrupt();
        if (mConn != null) dispose();
    }

    private boolean isLocalUrl(String url) {
        String URL_PREFIX = SubtitleDeduplicator.LOCAL_SUBTITLE_URL_PREFIX;

        if (url.startsWith(URL_PREFIX)) {
            return true;
        } else {
            return false;
        }
    }

    private boolean isSubtitleUrl() {
        char downloadKind = mMission.kind;
        if ('s' != downloadKind) {
            return false;
        }

        return true;
    }

    private boolean isLocalSubtitleUrl(String url) {
        if (false == isLocalUrl(url)) {
            return false;
        }

        if (false == isSubtitleUrl()) {
            return false;
        }

        return true;
    }

    private String getAbsolutePathFromLocalUrl(String localSubtitleUrl) {
        String URL_PREFIX = SubtitleDeduplicator.LOCAL_SUBTITLE_URL_PREFIX;
        int prefixLength = URL_PREFIX.length();
        // Remove URL_PREFIX
        String absolutePath = localSubtitleUrl.substring(prefixLength);
        return absolutePath;
    }

    private int handleLocalSubtitle(String localSubtitleUrl) {
        if (false == isValidLocalUrlLength(localSubtitleUrl)) {
            return 3;
        }

        String localSubtitlePath = getAbsolutePathFromLocalUrl(localSubtitleUrl);
        File file = new File(localSubtitlePath);

        int permissionResult = checkLocalFilePermissions(file);
        if (permissionResult != 0) {
            return permissionResult;
        }

        extractSubtitleToStorage(file);

        return 0; // Successfully
    }

    private boolean isValidLocalUrlLength(String localUrl) {
        String URL_PREFIX = SubtitleDeduplicator.LOCAL_SUBTITLE_URL_PREFIX;

        if (localUrl.length() <= URL_PREFIX.length()) {
             return false;
        }

        return true;
    }

    private int checkLocalFilePermissions(File file) {
        if (!file.exists()) {
            mMission.notifyError(DownloadMission.ERROR_FILE_CREATION, null);
            return 1;
        }

        if (!mMission.storage.canWrite()) {
            mMission.notifyError(DownloadMission.ERROR_PERMISSION_DENIED, null);
            return 2;
        }

        return 0;
    }

    // Extracts subtitle paragraphs(content) from a given local file
    // and writes them to storage.
    private void extractSubtitleToStorage(File file) {
        try (FileInputStream inputStream = new FileInputStream(file);
             SharpStream outputStream = mMission.storage.getStream()) {

            byte[] buffer = new byte[DownloadMission.BUFFER_SIZE];
            int bytesRead;
            long totalBytes = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                mMission.notifyProgress(bytesRead);
            }

            mMission.length = totalBytes;
            mMission.unknownLength = false;
            mMission.notifyFinished();

        } catch (IOException e) {
            String logMessage = "Error extracting subtitle paragraphs from " +
                                    file.getAbsolutePath() + ", error:" +
                                    e.getMessage();
            Log.e(TAG, logMessage);
            mMission.notifyError(DownloadMission.ERROR_FILE_CREATION, e);
        }
    }

    private void printLocalSubtitleStoredOk() {
        try {
            String logMessage = "Local subtitle url is extracted to:" +
                                mMission.storage.getName();
            Log.i(TAG, logMessage);
        } catch (NullPointerException e) {
            Log.w(TAG, "Please check whether the subtitle file is downloaded.", e);
        }
    }
}
