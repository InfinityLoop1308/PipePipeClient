package org.schabi.newpipe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class YoutubeSessionPoTokenSettingTest {
    @Test
    public void androidVrAlwaysGetsSessionPoTokens() {
        assertTrue(App.shouldProvideYoutubeSessionPoToken("ANDROID_VR", false));
        assertTrue(App.shouldProvideYoutubeSessionPoToken("ANDROID_VR", true));
    }

    @Test
    public void otherClientsFollowVisitorDataSetting() {
        assertFalse(App.shouldProvideYoutubeSessionPoToken("WEB", false));
        assertFalse(App.shouldProvideYoutubeSessionPoToken("MWEB", false));
        assertTrue(App.shouldProvideYoutubeSessionPoToken("WEB", true));
        assertTrue(App.shouldProvideYoutubeSessionPoToken("MWEB", true));
    }
}
