package us.shandian.giga.postprocessing;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import org.schabi.newpipe.streams.io.SharpStream;
import org.schabi.newpipe.streams.io.StoredFileHelper;
import us.shandian.giga.io.CircularFileWriter;
import us.shandian.giga.io.FileStream;

import java.io.File;
import java.io.IOException;

import static us.shandian.giga.util.Utility.removeTempFileOfDownloadedVideo;

public class BiliBiliMp4Muxer extends Postprocessing{


    public BiliBiliMp4Muxer() {
        super(true, true, BILIBILI_MUXER);
    }

    @Override
    int process(String source, Context context, SharpStream out, SharpStream... sources) throws IOException {
        return OK_RESULT;
    }
    public int mux(StoredFileHelper storage, Context context, SharpStream out, SharpStream... sources) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        int read;
        final String source = storage.source;
        // stale temp files of older versions, left in the download folder
        removeTempFileOfDownloadedVideo(storage);

        // The intermediate files are plain files inside the app's external cache.
        // ffmpeg runs within the app's uid so it can access them directly, and the
        // storage provider quirks of creating documents in the destination folder
        // (display name alteration, " (1)" deduplication, document size commit
        // timing, uri resolution) are avoided entirely.
        final File cacheDir = context.getExternalCacheDir() != null
                ? context.getExternalCacheDir() : context.getCacheDir();
        final String base = "bilitmp_" + Math.abs(source.hashCode());
        final File audioTmp = new File(cacheDir, base + ".m4a");
        final File muxTmp = new File(cacheDir, base + ".mp4");
        try {
            // write audio out of the mission file before it gets rewritten
            try (SharpStream audioOut = new FileStream(audioTmp)) {
                while ((read = sources[1].read(buffer)) > 0) {
                    audioOut.write(buffer, 0, read);
                }
            }
            if (audioTmp.length() < 1) {
                throw new IOException("The extracted audio stream is empty");
            }

            // rewrite the mission file in place, keeping only the video stream
            buffer = new byte[8 * 1024];
            while ((read = sources[0].read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            ((CircularFileWriter) out).finalizeFile();
            if (storage.length() < 1) {
                throw new IOException("The extracted video stream is empty");
            }

            // NOTE: the video document must be opened in "rw" mode. MediaProvider
            // exposes a stale (pre-write) size for documents that were last written
            // through a released "rw" descriptor, so a pure "r" open would hand
            // ffmpeg a truncated view -> "moov atom not found".
            final String video = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "rw");
            final String audio = '\'' + audioTmp.getAbsolutePath() + '\'';
            final String temp = '\'' + muxTmp.getAbsolutePath() + '\'';
            FFmpegSession merged = FFmpegKit.execute(
                    String.format("-i %s -i %s -strict -2 -c copy -y %s", video, audio, temp));
            if (!ReturnCode.isSuccess(merged.getReturnCode())) {
                throw new IOException("Failed to mux Bilibili audio and video: " + merged.getOutput());
            }
            if (muxTmp.length() < 1) {
                throw new IOException("The muxed Bilibili stream is empty");
            }

            // write the muxed stream back into the mission document
            final String videoWrite = FFmpegKitConfig.getSafParameter(context, Uri.parse(source), "w");
            FFmpegSession finalizing = FFmpegKit.execute(
                    String.format("-i %s -strict -2 -c copy -y %s", temp, videoWrite));
            if (!ReturnCode.isSuccess(finalizing.getReturnCode())) {
                throw new IOException("Failed to finalize muxed Bilibili video: " + finalizing.getOutput());
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            audioTmp.delete();
            //noinspection ResultOfMethodCallIgnored
            muxTmp.delete();
        }

        return OK_RESULT;
    }

//    public void mux() {
//
//        String temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName.replace(".mp4", ".tmp.mp4")), "rw");
//        String video  = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName), "rw");
//        String audio = FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(fileName.replace(".mp4", ".tmp")));
//        FFmpegKit.execute(String.format("-i %s -i %s -strict -2 -c copy -y %s", video, audio, temp));
//        temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName.replace(".mp4", ".tmp.mp4")), "rw");
//        video  = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName), "w");
//        FFmpegKit.execute(String.format("-i %s -strict -2 -c copy -y %s", temp, video));
//    }
}
