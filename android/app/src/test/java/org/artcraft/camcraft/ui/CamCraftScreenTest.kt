package org.artcraft.camcraft.ui

import org.artcraft.camcraft.network.CamCraftStreamService
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.update.ReleaseUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamCraftScreenTest {

    @Test
    fun formatConnectionPresentationHandlesUsbDirectUsbAdbAndWifi() {
        val usbDirect = formatConnectionPresentation(
            status = StreamConnectionStatus.CONNECTED,
            clientAddress = CamCraftStreamService.USB_CLIENT_LABEL,
            listenPort = 9876,
            errorMessage = null,
        )
        assertEquals("LIVE", usbDirect.statusLabel)
        assertTrue(usbDirect.isUsb)
        assertEquals("USB Direct", usbDirect.subtitle)

        val usbAdb = formatConnectionPresentation(
            status = StreamConnectionStatus.CONNECTED,
            clientAddress = "127.0.0.1",
            listenPort = 9876,
            errorMessage = null,
        )
        assertEquals("LIVE", usbAdb.statusLabel)
        assertTrue(usbAdb.isUsb)
        assertEquals("USB ADB • 127.0.0.1", usbAdb.subtitle)

        val wifi = formatConnectionPresentation(
            status = StreamConnectionStatus.CONNECTED,
            clientAddress = "192.168.1.55",
            listenPort = 9876,
            errorMessage = null,
        )
        assertEquals("LIVE", wifi.statusLabel)
        assertFalse(wifi.isUsb)
        assertEquals("Wi-Fi • 192.168.1.55", wifi.subtitle)

        val listening = formatConnectionPresentation(
            status = StreamConnectionStatus.LISTENING,
            clientAddress = null,
            listenPort = 9876,
            errorMessage = null,
        )
        assertEquals("READY", listening.statusLabel)
        assertFalse(listening.isUsb)
        assertEquals("Port 9876 • USB / Wi-Fi", listening.subtitle)

        val errored = formatConnectionPresentation(
            status = StreamConnectionStatus.ERROR,
            clientAddress = null,
            listenPort = 9876,
            errorMessage = "Address already in use",
        )
        assertEquals("ERROR", errored.statusLabel)
        assertEquals("Address already in use", errored.subtitle)
    }

    @Test
    fun formatReadyToInstallDetailReflectsChecksumsPresence() {
        val verifiedRelease = ReleaseUpdateInfo(
            tagName = "v0.2.0",
            version = "0.2.0",
            releaseNotes = "",
            htmlUrl = "https://example.invalid/release",
            assetName = "camcraft.apk",
            downloadUrl = "https://example.invalid/camcraft.apk",
            assetSize = 1024L,
            checksumsUrl = "https://example.invalid/SHA256SUMS.txt",
        )
        assertEquals(
            "SHA-256 verified. Tap Install to finish.",
            formatReadyToInstallDetail(verifiedRelease),
        )

        val unverifiedRelease = verifiedRelease.copy(checksumsUrl = null)
        assertEquals(
            "APK downloaded. Tap Install to finish.",
            formatReadyToInstallDetail(unverifiedRelease),
        )
    }
}
