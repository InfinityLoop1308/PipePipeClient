package org.schabi.newpipe.cast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers #3172: the advertised cast-receiver name should prefer the user-set device name
 * (Settings.Global.DEVICE_NAME) and only fall back to Build.MODEL / "Android" when unset.
 * The logic is tested via the pure helper [CastReceiver.resolveDeviceName] so no Android
 * framework / device is needed.
 */
class CastReceiverDeviceNameTest {

    @Test
    fun `user device name wins over model`() {
        assertEquals("Living Room", CastReceiver.resolveDeviceName("Living Room", "Chromecast HD"))
    }

    @Test
    fun `null user name falls back to model`() {
        assertEquals("Chromecast HD", CastReceiver.resolveDeviceName(null, "Chromecast HD"))
    }

    @Test
    fun `empty user name falls back to model`() {
        assertEquals("Pixel 8", CastReceiver.resolveDeviceName("", "Pixel 8"))
        assertEquals("Pixel 8", CastReceiver.resolveDeviceName("  ", "Pixel 8"))
    }

    @Test
    fun `no user name and no model falls back to Android`() {
        assertEquals("Android", CastReceiver.resolveDeviceName(null, null))
        assertEquals("Android", CastReceiver.resolveDeviceName("", null))
        assertEquals("Android", CastReceiver.resolveDeviceName("   ", null))
    }

    @Test
    fun `blank user name does not mask model fallback`() {
        // protects against advertising a blank NSD TXT record
        assertEquals("Pixel 8", CastReceiver.resolveDeviceName("   ", "Pixel 8"))
    }
}
