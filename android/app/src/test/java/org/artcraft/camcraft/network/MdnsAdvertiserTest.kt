package org.artcraft.camcraft.network

import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsAdvertiserTest {

    @Test
    fun sanitizeServiceNamePreservesNormalDeviceNames() {
        assertEquals("Pixel 8 Pro", sanitizeServiceName("Pixel 8 Pro"))
        assertEquals("Samsung Galaxy S24 Ultra", sanitizeServiceName("Samsung Galaxy S24 Ultra"))
    }

    @Test
    fun sanitizeServiceNameReplacesDotsAndControlCharsAndCollapsesWhitespace() {
        assertEquals(
            "Pixel 8 Pro Ultra",
            sanitizeServiceName("  Pixel.8..Pro\n\t\r\u0000\u007F  Ultra  "),
        )
    }

    @Test
    fun sanitizeServiceNameFallsBackToDefaultWhenBlank() {
        assertEquals("CamCraft Android", sanitizeServiceName(""))
        assertEquals("CamCraft Android", sanitizeServiceName("   \t\n  "))
        assertEquals("CamCraft Android", sanitizeServiceName("...\u0000..."))
    }

    @Test
    fun sanitizeServiceNameTruncatesAsciiAndMultibyteOnUtf8Boundary() {
        val longAscii = "a".repeat(80)
        val asciiResult = sanitizeServiceName(longAscii)
        assertEquals(63, asciiResult.length)
        assertEquals(63, asciiResult.toByteArray(Charsets.UTF_8).size)

        // 2-byte UTF-8 characters ('é' = 2 bytes) -> max 31 chars = 62 bytes
        val twoByte = "é".repeat(40)
        val twoByteResult = sanitizeServiceName(twoByte)
        assertTrue(twoByteResult.toByteArray(Charsets.UTF_8).size <= MdnsAdvertiser.MAX_SERVICE_NAME_BYTES)
        assertEquals(62, twoByteResult.toByteArray(Charsets.UTF_8).size)
        assertEquals("é".repeat(31), twoByteResult)

        // 4-byte UTF-8 surrogate pair emojis ("📷" = 4 bytes) -> max 15 chars = 60 bytes
        val emojis = "📷".repeat(20)
        val emojiResult = sanitizeServiceName(emojis)
        assertTrue(emojiResult.toByteArray(Charsets.UTF_8).size <= MdnsAdvertiser.MAX_SERVICE_NAME_BYTES)
        assertEquals(60, emojiResult.toByteArray(Charsets.UTF_8).size)
        assertFalse(emojiResult.contains('\uFFFD'))
        assertEquals("📷".repeat(15), emojiResult)

        // Boundary crossover: 61 ASCII bytes + 4-byte emoji (65 > 63) must drop the emoji cleanly
        val boundary = "x".repeat(61) + "📷"
        val boundaryResult = sanitizeServiceName(boundary)
        assertEquals("x".repeat(61), boundaryResult)
        assertEquals(61, boundaryResult.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun selectBestLocalIpv4PrefersWlanRfc1918OverCellularVpnLoopbackAndLinkLocal() {
        val candidates = listOf(
            InterfaceAddressCandidate("lo", "127.0.0.1", isSiteLocal = false, isLinkLocal = false),
            InterfaceAddressCandidate("rmnet_data0", "10.120.5.4", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("tun0", "10.8.0.2", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("wlan0", "169.254.12.34", isSiteLocal = false, isLinkLocal = true),
            InterfaceAddressCandidate("eth0", "192.168.50.10", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("wlan0", "192.168.1.42", isSiteLocal = true, isLinkLocal = false),
        )

        assertEquals("192.168.1.42", selectBestLocalIpv4(candidates))
    }

    @Test
    fun selectBestLocalIpv4FallsBackToEthernetWhenNoWifiRfc1918Exists() {
        val candidates = listOf(
            InterfaceAddressCandidate("rmnet0", "10.0.0.2", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("wlan0", "169.254.1.20", isSiteLocal = false, isLinkLocal = true),
            InterfaceAddressCandidate("eth0", "192.168.2.99", isSiteLocal = true, isLinkLocal = false),
        )

        assertEquals("192.168.2.99", selectBestLocalIpv4(candidates))
    }

    @Test
    fun selectBestLocalIpv4ReturnsNullWhenOnlyLoopbackCellularVpnOrDummyExist() {
        val candidates = listOf(
            InterfaceAddressCandidate("lo", "127.0.0.1", isSiteLocal = false, isLinkLocal = false),
            InterfaceAddressCandidate("rmnet0", "10.10.10.2", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("ccmni0", "172.16.0.5", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("tun0", "10.9.0.1", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("ppp0", "10.64.64.64", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("dummy0", "192.168.99.1", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("wlan0", "127.0.0.2", isSiteLocal = false, isLinkLocal = false),
        )

        assertNull(selectBestLocalIpv4(candidates))
        assertNull(selectBestLocalIpv4(emptyList()))
    }

    @Test
    fun selectBestLocalIpv4SupportsOtherWifiAndNonSiteLocalFallbacks() {
        val apCandidates = listOf(
            InterfaceAddressCandidate("ap0", "192.168.43.1", isSiteLocal = true, isLinkLocal = false),
            InterfaceAddressCandidate("eth0", "192.168.1.50", isSiteLocal = true, isLinkLocal = false),
        )
        assertEquals("192.168.43.1", selectBestLocalIpv4(apCandidates))

        val nonSiteLocalWifi = listOf(
            InterfaceAddressCandidate("wlan0", "100.64.1.12", isSiteLocal = false, isLinkLocal = false),
        )
        assertEquals("100.64.1.12", selectBestLocalIpv4(nonSiteLocalWifi))
    }

    @Test
    fun formatWifiEndpointAndStreamServiceStateWifiEndpointWork() {
        assertEquals(
            "192.168.1.42:${CcftProtocolConstants.DEFAULT_PORT}",
            formatWifiEndpoint("192.168.1.42"),
        )
        assertEquals("192.168.1.42:9999", formatWifiEndpoint("192.168.1.42", 9999))
        assertNull(formatWifiEndpoint(null))
        assertNull(formatWifiEndpoint(""))
        assertNull(formatWifiEndpoint("   "))

        val connectedState = StreamServiceState(localIpAddress = "192.168.1.42")
        assertEquals("192.168.1.42:9876", connectedState.wifiEndpoint)

        val disconnectedState = StreamServiceState(localIpAddress = null)
        assertNull(disconnectedState.wifiEndpoint)
    }
}
