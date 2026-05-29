package us.shandian.giga.get;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;
import java.util.Locale;

public final class HlsDownloadStreamHelper {
    public static final String HLS_MANIFEST_RESOLUTION = "HLS";

    private static final String HLS_MANIFEST_STREAM_ID = "hls-manifest";
    private static final String HLS_MANIFEST_CODEC = "hls";

    private HlsDownloadStreamHelper() {
    }

    public static boolean addManifestFallbackIfNeeded(final List<VideoStream> streams,
                                                      final StreamInfo info) {
        if (streams == null || info == null || info.getStreamType() != StreamType.VIDEO_STREAM) {
            return false;
        }

        final String hlsUrl = info.getHlsUrl();
        if (hlsUrl == null || hlsUrl.isEmpty() || hasNonHlsStream(streams)
                || hasHlsStream(streams, hlsUrl)) {
            return false;
        }

        streams.add(createManifestFallback(hlsUrl));
        return true;
    }

    public static VideoStream createManifestFallback(final String hlsUrl) {
        return new VideoStream.Builder()
                .setId(HLS_MANIFEST_STREAM_ID)
                .setContent(hlsUrl, true)
                .setIsVideoOnly(false)
                .setResolution(HLS_MANIFEST_RESOLUTION)
                .setMediaFormat(MediaFormat.MPEG_4)
                .setDeliveryMethod(DeliveryMethod.HLS)
                .setManifestUrl(hlsUrl)
                .setCodec(HLS_MANIFEST_CODEC)
                .build();
    }

    public static boolean isManifestFallbackRecovery(final MissionRecoveryInfo recovery) {
        return recovery != null
                && recovery.getKind() == 'v'
                && !recovery.isDesired2()
                && recovery.getFormat() == MediaFormat.MPEG_4
                && HLS_MANIFEST_RESOLUTION.equals(recovery.getDesired());
    }

    public static String[] buildResourceDeliveryMethods(final Stream selectedStream,
                                                        final Stream secondaryStream) {
        if (secondaryStream == null) {
            return new String[]{selectedStream.getDeliveryMethod().name()};
        }
        return new String[]{
                selectedStream.getDeliveryMethod().name(),
                secondaryStream.getDeliveryMethod().name()
        };
    }

    public static String[] buildResourceManifestUrls(final Stream selectedStream,
                                                     final Stream secondaryStream) {
        if (secondaryStream == null) {
            return new String[]{selectedStream.getManifestUrl()};
        }
        return new String[]{selectedStream.getManifestUrl(), secondaryStream.getManifestUrl()};
    }

    public static boolean[] buildResourceIsUrls(final Stream selectedStream,
                                                final Stream secondaryStream) {
        if (secondaryStream == null) {
            return new boolean[]{selectedStream.isUrl()};
        }
        return new boolean[]{selectedStream.isUrl(), secondaryStream.isUrl()};
    }

    public static boolean containsHlsResource(final String[] deliveryMethods,
                                             final String[] manifestUrls,
                                             final String[] urls) {
        return containsHlsMethod(deliveryMethods)
                || containsHlsValue(manifestUrls)
                || containsHlsValue(urls);
    }

    public static boolean looksLikeHls(final String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static boolean hasNonHlsStream(final List<VideoStream> streams) {
        for (final VideoStream stream : streams) {
            if (stream.getDeliveryMethod() != DeliveryMethod.HLS) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHlsStream(final List<VideoStream> streams, final String hlsUrl) {
        for (final VideoStream stream : streams) {
            if (stream.getDeliveryMethod() == DeliveryMethod.HLS
                    || hlsUrl.equals(stream.getContent())
                    || hlsUrl.equals(stream.getManifestUrl())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHlsMethod(final String[] values) {
        if (values == null) {
            return false;
        }
        for (final String value : values) {
            if (DeliveryMethod.HLS.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHlsValue(final String[] values) {
        if (values == null) {
            return false;
        }
        for (final String value : values) {
            if (looksLikeHls(value)) {
                return true;
            }
        }
        return false;
    }
}
