package org.artcraft.camcraft.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFpsCapabilitiesTest {

    private val garnetCam0AeRanges = listOf(
        FpsRange(1, 5),
        FpsRange(5, 5),
        FpsRange(10, 10),
        FpsRange(14, 14),
        FpsRange(15, 15),
        FpsRange(12, 24),
        FpsRange(24, 24),
        FpsRange(7, 30),
        FpsRange(14, 30),
        FpsRange(30, 30),
    )

    private val garnetCam1AeRanges = listOf(
        FpsRange(10, 10),
        FpsRange(15, 15),
        FpsRange(12, 24),
        FpsRange(24, 24),
        FpsRange(10, 30),
        FpsRange(30, 30),
    )

    private val garnetCam0StreamBasedRaw = intArrayOf(
        4080, 1832, 30, 60,
        4080, 1832, 60, 60,
        2304, 1296, 30, 60,
        2304, 1296, 60, 60,
        1920, 1080, 30, 60,
        1920, 1080, 60, 60,
        1280, 720, 30, 60,
        1280, 720, 60, 60,
    )

    private val garnetCam0CustomHfrRaw = intArrayOf(
        4080, 1832, 60,
        2304, 1296, 60,
        1920, 1080, 60,
        1280, 720, 60,
    )

    private val garnetCam1StreamBasedRaw = intArrayOf(
        2304, 1296, 30, 60,
        2304, 1296, 60, 60,
        1920, 1080, 30, 60,
        1920, 1080, 60, 60,
        1280, 720, 30, 60,
        1280, 720, 60, 60,
    )

    @Test
    fun garnetCamera0And1Unlock60FpsAt1080pAnd720pWhile4kStays30Fps() {
        val cam0Stream = CameraFpsCapabilities.parseStreamBasedFpsTable(garnetCam0StreamBasedRaw)
        val cam0Hfr = CameraFpsCapabilities.parseCustomHfrFpsTable(garnetCam0CustomHfrRaw)
        val cam1Stream = CameraFpsCapabilities.parseStreamBasedFpsTable(garnetCam1StreamBasedRaw)

        // 4K reports 30fps and falls back safely to fixed [30, 30]
        assertEquals(
            30,
            CameraFpsCapabilities.computeMaxFpsForSize(
                width = 3840,
                height = 2160,
                minFrameDurationNs = 33_333_333L,
                standardAeRanges = garnetCam0AeRanges,
                streamBasedEntries = cam0Stream,
                customHfrEntries = cam0Hfr,
            ),
        )
        val plan4k = CameraFpsCapabilities.resolveTargetFpsPlan(
            width = 3840,
            height = 2160,
            requestedFps = 60,
            minFrameDurationNs = 33_333_333L,
            standardAeRanges = garnetCam0AeRanges,
            streamBasedEntries = cam0Stream,
            customHfrEntries = cam0Hfr,
        )
        assertNotNull(plan4k)
        assertEquals(FpsRange(30, 30), plan4k?.range)
        assertEquals(FpsSupportSource.FALLBACK_STANDARD, plan4k?.source)
        assertFalse(plan4k!!.useVideoSessionMode)

        // 1080p & 720p on both Camera 0 and Camera 1 unlock [60, 60] via StreamBasedFPSTable
        for ((w, h) in listOf(1920 to 1080, 1280 to 720)) {
            assertEquals(
                60,
                CameraFpsCapabilities.computeMaxFpsForSize(
                    width = w,
                    height = h,
                    minFrameDurationNs = 33_333_333L,
                    standardAeRanges = garnetCam0AeRanges,
                    streamBasedEntries = cam0Stream,
                    customHfrEntries = cam0Hfr,
                ),
            )
            val cam0Plan = CameraFpsCapabilities.resolveTargetFpsPlan(
                width = w,
                height = h,
                requestedFps = 60,
                minFrameDurationNs = 33_333_333L,
                standardAeRanges = garnetCam0AeRanges,
                streamBasedEntries = cam0Stream,
                customHfrEntries = cam0Hfr,
            )
            assertNotNull(cam0Plan)
            assertEquals(FpsRange(60, 60), cam0Plan?.range)
            assertFalse(cam0Plan!!.isStandardAeRange)
            assertTrue(cam0Plan.useVideoSessionMode)
            assertEquals(FpsSupportSource.VENDOR_STREAM_BASED, cam0Plan.source)

            assertEquals(
                60,
                CameraFpsCapabilities.computeMaxFpsForSize(
                    width = w,
                    height = h,
                    minFrameDurationNs = 33_333_333L,
                    standardAeRanges = garnetCam1AeRanges,
                    streamBasedEntries = cam1Stream,
                    customHfrEntries = emptyList(),
                ),
            )
            val cam1Plan = CameraFpsCapabilities.resolveTargetFpsPlan(
                width = w,
                height = h,
                requestedFps = 60,
                minFrameDurationNs = 33_333_333L,
                standardAeRanges = garnetCam1AeRanges,
                streamBasedEntries = cam1Stream,
                customHfrEntries = emptyList(),
            )
            assertNotNull(cam1Plan)
            assertEquals(FpsRange(60, 60), cam1Plan?.range)
            assertFalse(cam1Plan!!.isStandardAeRange)
            assertTrue(cam1Plan.useVideoSessionMode)
            assertEquals(FpsSupportSource.VENDOR_STREAM_BASED, cam1Plan.source)
        }
    }

    @Test
    fun customHfrOnlyDeviceUnlocks60FpsWhenStreamBasedTableAbsent() {
        val hfrEntries = CameraFpsCapabilities.parseCustomHfrFpsTable(garnetCam0CustomHfrRaw)

        assertEquals(
            60,
            CameraFpsCapabilities.computeMaxFpsForSize(
                width = 1920,
                height = 1080,
                minFrameDurationNs = 33_333_333L,
                standardAeRanges = garnetCam0AeRanges,
                streamBasedEntries = emptyList(),
                customHfrEntries = hfrEntries,
            ),
        )

        val plan = CameraFpsCapabilities.resolveTargetFpsPlan(
            width = 1920,
            height = 1080,
            requestedFps = 60,
            minFrameDurationNs = 33_333_333L,
            standardAeRanges = garnetCam0AeRanges,
            streamBasedEntries = emptyList(),
            customHfrEntries = hfrEntries,
        )
        assertNotNull(plan)
        assertEquals(FpsRange(60, 60), plan?.range)
        assertFalse(plan!!.isStandardAeRange)
        assertTrue(plan.useVideoSessionMode)
        assertEquals(FpsSupportSource.VENDOR_CUSTOM_HFR, plan.source)
    }

    @Test
    fun standardFixed60FpsDeviceResolvesStandardAePlan() {
        val ranges = listOf(
            FpsRange(15, 30),
            FpsRange(30, 30),
            FpsRange(30, 60),
            FpsRange(60, 60),
        )

        val plan = CameraFpsCapabilities.resolveTargetFpsPlan(
            width = 1920,
            height = 1080,
            requestedFps = 60,
            minFrameDurationNs = 16_666_666L,
            standardAeRanges = ranges,
            streamBasedEntries = emptyList(),
            customHfrEntries = emptyList(),
        )
        assertNotNull(plan)
        assertEquals(FpsRange(60, 60), plan?.range)
        assertTrue(plan!!.isStandardAeRange)
        assertTrue(plan.useVideoSessionMode)
        assertEquals(FpsSupportSource.STANDARD_AE, plan.source)
    }

    @Test
    fun variableOnly60FpsDevicePicksHighestMinFpsVariableRange() {
        val ranges = listOf(
            FpsRange(12, 30),
            FpsRange(30, 30),
            FpsRange(15, 60),
            FpsRange(24, 60),
        )

        val plan = CameraFpsCapabilities.resolveTargetFpsPlan(
            width = 1920,
            height = 1080,
            requestedFps = 60,
            minFrameDurationNs = 16_666_666L,
            standardAeRanges = ranges,
            streamBasedEntries = emptyList(),
            customHfrEntries = emptyList(),
        )
        assertNotNull(plan)
        assertEquals(FpsRange(24, 60), plan?.range)
        assertTrue(plan!!.isStandardAeRange)
        assertTrue(plan.useVideoSessionMode)
        assertEquals(FpsSupportSource.STANDARD_AE, plan.source)
    }

    @Test
    fun ultraWide30FpsOnlyCameraReportsMax30AndFallsBackSafely() {
        val ultraWideAe = listOf(
            FpsRange(10, 30),
            FpsRange(15, 30),
            FpsRange(30, 30),
        )

        assertEquals(
            30,
            CameraFpsCapabilities.computeMaxFpsForSize(
                width = 1920,
                height = 1080,
                minFrameDurationNs = 33_333_333L,
                standardAeRanges = ultraWideAe,
                streamBasedEntries = emptyList(),
                customHfrEntries = emptyList(),
            ),
        )

        val plan = CameraFpsCapabilities.resolveTargetFpsPlan(
            width = 1920,
            height = 1080,
            requestedFps = 60,
            minFrameDurationNs = 33_333_333L,
            standardAeRanges = ultraWideAe,
            streamBasedEntries = emptyList(),
            customHfrEntries = emptyList(),
        )
        assertNotNull(plan)
        assertEquals(FpsRange(30, 30), plan?.range)
        assertTrue(plan!!.isStandardAeRange)
        assertFalse(plan.useVideoSessionMode)
        assertEquals(FpsSupportSource.FALLBACK_STANDARD, plan.source)
    }

    @Test
    fun malformedOrTruncatedVendorArraysAreSafelyIgnored() {
        assertTrue(CameraFpsCapabilities.parseStreamBasedFpsTable(null).isEmpty())
        assertTrue(CameraFpsCapabilities.parseStreamBasedFpsTable(intArrayOf(1920, 1080, 60)).isEmpty())
        assertTrue(CameraFpsCapabilities.parseStreamBasedFpsTable(intArrayOf(1920, 1080, 60, 60, 1)).isEmpty())
        assertTrue(CameraFpsCapabilities.parseStreamBasedFpsTable(intArrayOf(0, 1080, 60, 60, 1920, 1080, 90, 60)).isEmpty())

        assertTrue(CameraFpsCapabilities.parseCustomHfrFpsTable(null).isEmpty())
        assertTrue(CameraFpsCapabilities.parseCustomHfrFpsTable(intArrayOf(1920, 1080)).isEmpty())
        assertTrue(CameraFpsCapabilities.parseCustomHfrFpsTable(intArrayOf(1920, 1080, 60, 1)).isEmpty())
        assertTrue(CameraFpsCapabilities.parseCustomHfrFpsTable(intArrayOf(-1, 1080, 60)).isEmpty())

        // Stride-5 variant should still parse valid tuples cleanly
        val stride5 = CameraFpsCapabilities.parseCustomHfrFpsTable(intArrayOf(1920, 1080, 60, 0, 1))
        assertEquals(listOf(StreamFpsEntry(1920, 1080, 60, 60)), stride5)

        // Zero/negative requestedFps safely returns null
        assertNull(
            CameraFpsCapabilities.resolveTargetFpsPlan(
                width = 1920,
                height = 1080,
                requestedFps = 0,
                minFrameDurationNs = 16_666_666L,
                standardAeRanges = garnetCam0AeRanges,
                streamBasedEntries = emptyList(),
                customHfrEntries = emptyList(),
            ),
        )
    }
}
