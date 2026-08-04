package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class SabrPreferredAudioLanguageTest {

    @Test
    public void preferredLanguageSelectsHighestBitrateRegionalTrack() throws Exception {
        final YoutubeSabrInfo.Format original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrInfo.Format portugueseLow = audioFormat(
                139, "pt-BR.4", "Portuguese (Brazil)", 96_000);
        final YoutubeSabrInfo.Format portugueseHigh = audioFormat(
                251, "pt-BR.4", "Portuguese (Brazil)", 160_000);
        final YoutubeSabrInfo info = info(original, portugueseLow, portugueseHigh);

        assertSame(portugueseHigh, SabrSessionStore.pickAudioFormat(info, null, "pt"));
    }

    @Test
    public void explicitTrackOverridesPreferredLanguage() throws Exception {
        final YoutubeSabrInfo.Format original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrInfo.Format portuguese = audioFormat(
                251, "pt-BR.4", "Portuguese (Brazil)", 160_000);
        final YoutubeSabrInfo.Format spanish = audioFormat(
                250, "es-ES.4", "Spanish (Spain)", 96_000);
        final YoutubeSabrInfo info = info(original, portuguese, spanish);

        assertSame(spanish,
                SabrSessionStore.pickAudioFormat(info, "es-ES.4", "pt"));
    }

    @Test
    public void missingPreferredLanguageFallsBackToOriginal() throws Exception {
        final YoutubeSabrInfo.Format original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrInfo.Format spanish = audioFormat(
                251, "es-ES.4", "Spanish (Spain)", 160_000);
        final YoutubeSabrInfo info = info(original, spanish);

        assertSame(original, SabrSessionStore.pickAudioFormat(info, null, "pt"));
    }

    private static YoutubeSabrInfo.Format audioFormat(final int itag,
                                                  final String trackId,
                                                  final String displayName,
                                                  final int bitrate) throws Exception {
        final ItagItem parsedFormat = ItagItem.getItag(itag);
        parsedFormat.setBitrate(bitrate);
        parsedFormat.setContentLength(100_000L);
        parsedFormat.setApproxDurationMs(300_000L);
        return YoutubeSabrInfo.Format.fromParsedFormat(parsedFormat, 123456L, null, "audio/mp4",
                trackId, displayName, false, null, -1L, -1L);
    }

    private static YoutubeSabrInfo info(final YoutubeSabrInfo.Format... formats) throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance("video-id", "cpn",
                "2.test", "visitor", "https://sabr.test", null, Arrays.asList(formats));
    }
}
