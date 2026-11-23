package org.artcraft.camcraft.ui

import android.os.BatteryManager
import kotlin.math.abs
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.network.StreamServiceState
import org.artcraft.camcraft.network.defaultActiveCameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OledSaverOverlayTest {

    @Test
    fun constantsMatchBatterySaverAndBurnInSpec() {
        assertEquals(0.01f, OledSaverMotion.OLED_SAVER_BRIGHTNESS, 0.0001f)
        assertEquals(30_000L, OledSaverMotion.BURN_IN_SHIFT_INTERVAL_MS)
        assertTrue(OledSaverMotion.WAYPOINTS.size >= 4)
    }

    @Test
    fun biasForStepIsBoundedDistinctAndWrapsSafely() {
        val count = OledSaverMotion.WAYPOINTS.size
        val step0 = OledSaverMotion.biasForStep(0)
        val wrapped = OledSaverMotion.biasForStep(count)
        val negative = OledSaverMotion.biasForStep(-count)
        assertEquals(step0, wrapped)
        assertEquals(step0, negative)

        for (step in 0 until count) {
            val current = OledSaverMotion.biasForStep(step)
            val next = OledSaverMotion.biasForStep(step + 1)
            assertTrue("x bias out of bounds at step $step", abs(current.first) <= 0.6f)
            assertTrue("y bias out of bounds at step $step", abs(current.second) <= 0.7f)
            assertNotEquals("consecutive steps must shift to prevent burn-in", current, next)
        }
    }

    @Test
    fun statusAndDetailFormattingCoversAllConnectionStates() {
        val connected = StreamServiceState(
            status = StreamConnectionStatus.CONNECTED,
            currentFps = 59.8f,
            activeState = defaultActiveCameraState().copy(width = 1920, height = 1080, audioMuted = true),
        )
        assertEquals("LIVE • 1920x1080 • 59.8 FPS", OledSaverMotion.formatStatusLine(connected))
        assertEquals("Mic Muted • Double-tap anywhere to wake", OledSaverMotion.formatDetailLine(connected))

        val listening = StreamServiceState(
            status = StreamConnectionStatus.LISTENING,
            localIpAddress = "192.168.1.42",
            listenPort = 9876,
            activeState = defaultActiveCameraState().copy(audioEnabled = true, audioMuted = false),
        )
        assertEquals("WAITING FOR PC • 192.168.1.42:9876", OledSaverMotion.formatStatusLine(listening))
        assertEquals("Mic ON • Double-tap anywhere to wake", OledSaverMotion.formatDetailLine(listening))

        val stopped = StreamServiceState(status = StreamConnectionStatus.STOPPED)
        assertEquals("CAMCRAFT STANDBY", OledSaverMotion.formatStatusLine(stopped))
        assertEquals("Double-tap anywhere to wake", OledSaverMotion.formatDetailLine(stopped))

        val error = StreamServiceState(
            status = StreamConnectionStatus.ERROR,
            errorMessage = "Port in use",
        )
        assertEquals("ERROR • Port in use", OledSaverMotion.formatStatusLine(error))
        assertEquals("Double-tap anywhere to wake", OledSaverMotion.formatDetailLine(error))

        val activeVideoError = StreamServiceState(
            status = StreamConnectionStatus.ERROR,
            clientAddress = "USB Direct",
            hasActiveSession = true,
            isVideoPipelineHealthy = false,
            activeState = defaultActiveCameraState().copy(audioEnabled = true, audioMuted = false),
            errorMessage = "Bind failed",
        )
        assertEquals("ERROR • Bind failed", OledSaverMotion.formatStatusLine(activeVideoError))
        assertEquals("Mic ON • Double-tap anywhere to wake", OledSaverMotion.formatDetailLine(activeVideoError))
    }

    @Test
    fun detailLineWithBatterySummaryAppendsOnlyWhenNonBlank() {
        val connected = StreamServiceState(
            status = StreamConnectionStatus.CONNECTED,
            activeState = defaultActiveCameraState().copy(audioEnabled = true, audioMuted = false),
        )
        assertEquals(
            "Mic ON • Double-tap anywhere to wake • 84% • 34°C",
            OledSaverMotion.formatDetailLine(connected, "84% • 34°C"),
        )
        assertEquals(
            "Mic ON • Double-tap anywhere to wake",
            OledSaverMotion.formatDetailLine(connected, null),
        )
        assertEquals(
            "Mic ON • Double-tap anywhere to wake",
            OledSaverMotion.formatDetailLine(connected, "   "),
        )

        val stopped = StreamServiceState(status = StreamConnectionStatus.STOPPED)
        assertEquals(
            "Double-tap anywhere to wake • 72% ⚡ • 39°C",
            OledSaverMotion.formatDetailLine(stopped, "72% ⚡ • 39°C"),
        )
    }

    @Test
    fun formatBatterySummaryFormatsAllTelemetryStates() {
        assertEquals(
            "84% • 34°C",
            formatBatterySummary(DeviceTelemetry(batteryPct = 84, temperatureC = 34.2f, isCharging = false)),
        )
        assertEquals(
            "84% ⚡ • 34°C",
            formatBatterySummary(DeviceTelemetry(batteryPct = 84, temperatureC = 34.4f, isCharging = true)),
        )
        assertEquals(
            "92%",
            formatBatterySummary(DeviceTelemetry(batteryPct = 92, temperatureC = null, isCharging = false)),
        )
        assertEquals(
            "92% ⚡",
            formatBatterySummary(DeviceTelemetry(batteryPct = 92, temperatureC = null, isCharging = true)),
        )
        assertEquals(
            "38°C",
            formatBatterySummary(DeviceTelemetry(batteryPct = null, temperatureC = 37.6f, isCharging = false)),
        )
        assertNull(formatBatterySummary(DeviceTelemetry(batteryPct = null, temperatureC = null, isCharging = false)))
        assertNull(formatBatterySummary(DeviceTelemetry(batteryPct = null, temperatureC = null, isCharging = true)))
    }

    @Test
    fun extractDeviceTelemetryHandlesValidAndInvalidBatteryExtras() {
        val charging = extractDeviceTelemetry(
            level = 42,
            scale = 50,
            tempTenthsC = 345,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
        )
        assertEquals(84, charging.batteryPct)
        assertEquals(34.5f, charging.temperatureC!!, 0.01f)
        assertTrue(charging.isCharging)

        val missing = extractDeviceTelemetry(
            level = -1,
            scale = 100,
            tempTenthsC = Int.MIN_VALUE,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING,
        )
        assertNull(missing.batteryPct)
        assertNull(missing.temperatureC)
        assertEquals(false, missing.isCharging)
    }
}
