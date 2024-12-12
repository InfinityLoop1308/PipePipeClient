package org.schabi.newpipe.util;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.NotLoginException;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.schabi.newpipe.extractor.localization.Localization.getEnglishName;

public class YtdlpHelper {
    private static final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    public static String cookieFile;
    public static StreamInfo getFallbackStreams(final String url) throws IOException, ExtractionException {
        ReentrantLock lock = locks.computeIfAbsent(url, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            System.out.println("Operation skipped for " + url + " because another thread is processing it.");
            return new StreamInfo(); // Or handle this situation as needed
        }
        try {
            System.out.println("Getting fallback streams for " + url);
            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("--no-check-certificate");
            request.addOption("--extractor-args", "youtube:player_client=default,-ios");
            request.addOption("--cookies", cookieFile);
            VideoInfo originStreamInfo = YoutubeDL.getInstance().getInfo(request);
            StreamInfo streamInfo = new StreamInfo();
            ArrayList<AudioStream> audioStreams = new ArrayList<>();
            ArrayList<VideoStream> videoStreams = new ArrayList<>();
            ArrayList<VideoStream> videoOnlyStreams = new ArrayList<>();

            String contentLanguage = getEnglishName(ServiceList.YouTube.getAudioLanguage());
            boolean hasContentLanguage = false;
            boolean hasOriginal = false;
            for(VideoFormat videoFormat : originStreamInfo.getFormats()) {
                if (Objects.equals(videoFormat.getVcodec(), "none") && !Objects.equals(videoFormat.getAcodec(), "none")) {
                    if (videoFormat.getFormatNote().contains(contentLanguage)) {
                        hasContentLanguage = true;
                    }
                    if (videoFormat.getFormatNote().contains("original")) {
                        hasOriginal = true;
                    }
                }
            }

            for(VideoFormat videoFormat : originStreamInfo.getFormats()) {
                if (Objects.equals(videoFormat.getVcodec(), "none") && Objects.equals(videoFormat.getAcodec(), "none")) {
                    continue;
                }
                if (Objects.equals(videoFormat.getVcodec(), "none")) {
                    if (hasContentLanguage) {
                        if (!videoFormat.getFormatNote().contains(contentLanguage)) {
                            continue;
                        }
                    } else {
                        if (hasOriginal) {
                            if (!videoFormat.getFormatNote().contains("original")) {
                                continue;
                            }
                        }

                    }
                    ItagItem itag = new ItagItem(Integer.parseInt(videoFormat.getFormatId().split("-")[0]), ItagItem.ItagType.AUDIO,
                            MediaFormat.getFromSuffix(videoFormat.getExt()), videoFormat.getTbr());
                    itag.setCodec(videoFormat.getAcodec());
                    itag.setBitrate(videoFormat.getTbr());
                    itag.setSampleRate(videoFormat.getAsr());
                    itag.setInitStart(-1);
                    itag.setInitEnd(-1);
                    itag.setIndexStart(-1);
                    itag.setIndexEnd(-1);

                    audioStreams.add(new AudioStream.Builder().setId(originStreamInfo.getId())
                            .setContent(videoFormat.getUrl(), true)
                            .setItagItem(itag)
                            .setMediaFormat(MediaFormat.getFromSuffix(videoFormat.getExt())).setAverageBitrate(videoFormat.getTbr()).build());
                } else if (Objects.equals(videoFormat.getAcodec(), "none")) {
                    ItagItem itag = new ItagItem(Integer.parseInt(videoFormat.getFormatId().split("-")[0]), ItagItem.ItagType.VIDEO_ONLY, MediaFormat.getFromSuffix(videoFormat.getExt()), videoFormat.getFormatNote(), videoFormat.getFps());
                    itag.setCodec(videoFormat.getVcodec());
                    itag.setBitrate(videoFormat.getTbr());
                    itag.setWidth(videoFormat.getWidth());
                    itag.setHeight(videoFormat.getHeight());
                    itag.setInitStart(-1);
                    itag.setInitEnd(-1);
                    itag.setIndexStart(-1);
                    itag.setIndexEnd(-1);
                    String resolution = videoFormat.getFormatNote();
                    if (resolution == null) {
                        resolution = videoFormat.getHeight() + "p";
                    }
                    videoOnlyStreams.add(new VideoStream.Builder().setContent(videoFormat.getUrl(), true)
                            .setMediaFormat(MediaFormat.getFromSuffix(videoFormat.getExt())).setId(originStreamInfo.getId())
                            .setItagItem(itag)
                            .setIsVideoOnly(true).setResolution(resolution).build());
                } else {
                    ItagItem itag = new ItagItem(Integer.parseInt(videoFormat.getFormatId().split("-")[0]), ItagItem.ItagType.VIDEO_ONLY, MediaFormat.getFromSuffix(videoFormat.getExt()), videoFormat.getFormatNote(), videoFormat.getFps());
                    itag.setCodec(videoFormat.getVcodec());
                    itag.setBitrate(videoFormat.getTbr());
                    itag.setWidth(videoFormat.getWidth());
                    itag.setHeight(videoFormat.getHeight());
                    itag.setInitStart(-1);
                    itag.setInitEnd(-1);
                    itag.setIndexStart(-1);
                    itag.setIndexEnd(-1);
                    String resolution = videoFormat.getFormatNote();
                    if (resolution == null) {
                        resolution = videoFormat.getHeight() + "p";
                    }
                    videoStreams.add(new VideoStream.Builder().setContent(videoFormat.getUrl(), true)
                            .setMediaFormat(MediaFormat.getFromSuffix(videoFormat.getExt())).setId(originStreamInfo.getId())
                            .setItagItem(itag)
                            .setIsVideoOnly(true).setResolution(resolution).build());
                }
            }

            streamInfo.setAudioStreams(audioStreams);
            streamInfo.setVideoStreams(videoStreams);
            streamInfo.setVideoOnlyStreams(videoOnlyStreams);
            System.out.println("Successfully got fallback streams for " + url);
            return streamInfo;
        } catch (InterruptedException | YoutubeDL.CanceledException e) {
            throw new IOException(e);
        }  catch (Exception e) {
            if (e.getMessage() == null) {
                throw new ExtractionException(e);
            }
            if (e.getMessage().contains("Sign in")) {
                throw new NotLoginException(e);
            } else if (e.getMessage().contains("in your country")) {
                throw new GeographicRestrictionException(e.getMessage());
            }
            throw new ExtractionException(e);
        } finally {
            lock.unlock(); // Release the lock
            // Clean up the lock if it's no longer needed
            locks.remove(url, lock);
        }
    }
}
