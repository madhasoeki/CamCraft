package org.artcraft.camcraft.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlinx.coroutines.delay
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.network.StreamServiceState

/**
 * Pure JVM helper encapsulating anti-burn-in waypoint motion, brightness target,
 * and status formatting for [OledSaverOverlay].
 */
object OledSaverMotion {
    const val OLED_SAVER_BRIGHTNESS: Float = 0.01f
    const val BURN_IN_SHIFT_INTERVAL_MS: Long = 30_000L

    val WAYPOINTS: List<Pair<Float, Float>> = listOf(
        -0.45f to -0.60f,
        0.45f to -0.30f,
        -0.35f to 0.15f,
        0.40f to 0.55f,
        -0.45f to 0.60f,
        0.35f to 0.10f,
        -0.30f to -0.25f,
        0.45f to -0.60f,
    )

    fun biasForStep(step: Int): Pair<Float, Float> {
        val index = Math.floorMod(step, WAYPOINTS.size)
        return WAYPOINTS[index]
    }

    fun formatStatusLine(state: StreamServiceState): String = when (state.status) {
        StreamConnectionStatus.CONNECTED -> {
            val fpsText = String.format(Locale.US, "%.1f", state.currentFps)
            "LIVE • ${state.activeState.width}x${state.activeState.height} • $fpsText FPS"
        }
        StreamConnectionStatus.LISTENING -> {
            val endpoint = state.wifiEndpoint ?: "Port ${state.listenPort}"
            "WAITING FOR PC • $endpoint"
        }
        StreamConnectionStatus.ERROR -> {
            "ERROR • ${state.errorMessage ?: "Connection issue"}"
        }
        StreamConnectionStatus.STOPPED -> "CAMCRAFT STANDBY"
    }

    fun formatDetailLine(state: StreamServiceState): String = when (state.status) {
        StreamConnectionStatus.CONNECTED,
        StreamConnectionStatus.LISTENING,
        -> {
            val micLabel = when {
                !state.activeState.audioEnabled -> "Mic OFF"
                state.activeState.audioMuted -> "Mic Muted"
                else -> "Mic ON"
            }
            "$micLabel • Double-tap anywhere to wake"
        }
        StreamConnectionStatus.ERROR,
        StreamConnectionStatus.STOPPED,
        -> "Double-tap anywhere to wake"
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Full-screen pure-black (`#000000`) OLED battery saver overlay.
 *
 * Drops window `screenBrightness` to [OledSaverMotion.OLED_SAVER_BRIGHTNESS] (`0.01f`),
 * hides system bars to avoid static icon burn-in, shifts a minimal low-luminance status
 * indicator every 30 seconds across the display, and exits on any double-tap or Back press.
 */
@Composable
fun OledSaverOverlay(
    state: StreamServiceState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)

    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, view) {
        val window = activity?.window
        if (window == null) {
            onDispose {}
        } else {
            val previousBrightness = window.attributes.screenBrightness
            val hadKeepScreenOn =
                (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

            val dimmedAttrs = WindowManager.LayoutParams().apply {
                copyFrom(window.attributes)
                screenBrightness = OledSaverMotion.OLED_SAVER_BRIGHTNESS
            }
            window.attributes = dimmedAttrs
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())

            onDispose {
                val restoredAttrs = WindowManager.LayoutParams().apply {
                    copyFrom(window.attributes)
                    screenBrightness = previousBrightness
                }
                window.attributes = restoredAttrs
                if (!hadKeepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var positionStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(OledSaverMotion.BURN_IN_SHIFT_INTERVAL_MS)
            positionStep = (positionStep + 1) % OledSaverMotion.WAYPOINTS.size
        }
    }

    val (targetBiasX, targetBiasY) = OledSaverMotion.biasForStep(positionStep)
    val animatedBiasX by animateFloatAsState(
        targetValue = targetBiasX,
        animationSpec = tween(durationMillis = 1_400),
        label = "oledBiasX",
    )
    val animatedBiasY by animateFloatAsState(
        targetValue = targetBiasY,
        animationSpec = tween(durationMillis = 1_400),
        label = "oledBiasY",
    )

    val statusLine = OledSaverMotion.formatStatusLine(state)
    val detailLine = OledSaverMotion.formatDetailLine(state)
    val dotColor = when (state.status) {
        StreamConnectionStatus.CONNECTED -> ArtCraftColors.StatusEmerald.copy(alpha = 0.35f)
        StreamConnectionStatus.LISTENING -> ArtCraftColors.StatusAmber.copy(alpha = 0.35f)
        StreamConnectionStatus.ERROR -> ArtCraftColors.StatusRose.copy(alpha = 0.35f)
        StreamConnectionStatus.STOPPED -> Color.White.copy(alpha = 0.25f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(onExit) {
                detectTapGestures(
                    onTap = { /* consume single tap so underlying UI is never triggered */ },
                    onDoubleTap = { onExit() },
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "OLED Battery Saver active. $statusLine. Double-tap anywhere to wake."
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = BiasAlignment(animatedBiasX, animatedBiasY),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(dotColor, CircleShape),
                    )
                    Text(
                        text = statusLine,
                        color = Color.White.copy(alpha = 0.28f),
                        fontWeight = FontWeight.Light,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = detailLine,
                    color = Color.White.copy(alpha = 0.16f),
                    fontWeight = FontWeight.Light,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OledSaverOverlayPreview() {
    CamCraftTheme {
        OledSaverOverlay(
            state = StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                currentFps = 60.0f,
                localIpAddress = "192.168.1.42",
            ),
            onExit = {},
        )
    }
}
