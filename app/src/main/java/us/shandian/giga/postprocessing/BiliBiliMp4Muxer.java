package us.shandian.giga.postprocessing;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;

public class BiliBiliMp4Muxer {
    private String folderName;
    private String fileName;
    private final Context context;

    public BiliBiliMp4Muxer(String folderName, String fileName, Context context) {
        this.folderName = folderName;
        this.fileName = fileName;
        this.context = context;
    }

    public void mux() {

        String temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName.replace(".mp4", ".tmp.mp4")), "rw");
        String video  = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName), "rw");
        String audio = FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(fileName.replace(".mp4", ".tmp")));
        FFmpegKit.execute(String.format("-i %s -i %s -strict -2 -c copy -y %s", video, audio, temp));
        temp = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName.replace(".mp4", ".tmp.mp4")), "rw");
        video  = FFmpegKitConfig.getSafParameter(context, Uri.parse(fileName), "w");
        FFmpegKit.execute(String.format("-i %s -strict -2 -c copy -y %s", temp, video));
    }
}
