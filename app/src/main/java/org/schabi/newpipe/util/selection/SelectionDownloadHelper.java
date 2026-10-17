package org.schabi.newpipe.util.selection;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.StreamProcessor;

import java.util.List;

import us.shandian.giga.get.DirectDownloader;

/** Shared video/audio picker used by every list's selection toolbar. */
public final class SelectionDownloadHelper {

    private SelectionDownloadHelper() {
    }

    public static void showDownloadDialog(@NonNull final Context context,
                                          @NonNull final List<StreamInfoItem> streams) {
        if (streams.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.download)
                .setMessage(R.string.download_all_message)
                .setPositiveButton(R.string.video, (d, w) ->
                        new StreamProcessor().processStreamsSequentiallyWithProgress(
                                context, streams, DirectDownloader.DownloadType.VIDEO))
                .setNegativeButton(R.string.audio, (d, w) ->
                        new StreamProcessor().processStreamsSequentiallyWithProgress(
                                context, streams, DirectDownloader.DownloadType.AUDIO))
                .setNeutralButton(R.string.cancel, null)
                .show();
    }
}
