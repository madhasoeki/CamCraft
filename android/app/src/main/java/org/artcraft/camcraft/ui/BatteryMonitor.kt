package org.artcraft.camcraft.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Snapshot of OS-pushed Android battery and thermal telemetry.
 */
@Immutable
data class DeviceTelemetry(
    val batteryPct: Int?,
    val temperatureC: Float?,
    val isCharging: Boolean,
)

/**
 * Pure JVM helper converting raw Android battery broadcast extras into [DeviceTelemetry].
 */
internal fun extractDeviceTelemetry(
    level: Int,
    scale: Int,
    tempTenthsC: Int,
    status: Int,
): DeviceTelemetry {
    val batteryPct = if (level >= 0 && scale > 0) {
        ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    } else {
        null
    }
    val temperatureC = if (tempTenthsC != Int.MIN_VALUE && tempTenthsC > -500) {
        tempTenthsC / 10f
    } else {
        null
    }
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    return DeviceTelemetry(
        batteryPct = batteryPct,
        temperatureC = temperatureC,
        isCharging = isCharging,
    )
}

internal fun parseBatteryIntent(intent: Intent?): DeviceTelemetry {
    if (intent == null) {
        return DeviceTelemetry(batteryPct = null, temperatureC = null, isCharging = false)
    }
    return extractDeviceTelemetry(
        level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        tempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
    )
}

/**
 * Pure testable formatter for compact battery & thermal readout (e.g. `"84% • 34°C"` or
 * `"84% ⚡ • 34°C"`), returning `null` if both values are unavailable.
 */
fun formatBatterySummary(telemetry: DeviceTelemetry): String? {
    val pctText = telemetry.batteryPct?.let { pct ->
        if (telemetry.isCharging) "$pct% ⚡" else "$pct%"
    }
    val tempText = telemetry.temperatureC?.let { temp ->
        "${temp.roundToInt()}°C"
    }

    return when {
        pctText != null && tempText != null -> "$pctText • $tempText"
        pctText != null -> pctText
        tempText != null -> tempText
        else -> null
    }
}

/**
 * Observes OS-pushed [Intent.ACTION_BATTERY_CHANGED] updates without polling.
 */
@Composable
fun rememberDeviceTelemetry(): DeviceTelemetry {
    val context = LocalContext.current
    var telemetry by remember(context) {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        mutableStateOf(parseBatteryIntent(sticky))
    }

    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                telemetry = parseBatteryIntent(intent)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return telemetry
}
