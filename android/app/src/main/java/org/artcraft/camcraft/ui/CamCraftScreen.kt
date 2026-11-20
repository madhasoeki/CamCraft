package org.artcraft.camcraft.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.artcraft.camcraft.network.CamCraftStreamService
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.network.StreamServiceState

object ArtCraftColors {
    val BgCarbon = Color(0xFF111318)
    val SurfaceCard = Color(0xFF1C2026)
    val SurfaceCardHighlight = Color(0xFF262C36)
    val AccentBlue = Color(0xFF3B82F6)
    val StatusEmerald = Color(0xFF10B981)
    val StatusAmber = Color(0xFFF59E0B)
    val StatusRose = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF3F4F6)
    val TextMuted = Color(0xFF9CA3AF)
}

private val ArtCraftDarkColorScheme = darkColorScheme(
    primary = ArtCraftColors.AccentBlue,
    secondary = ArtCraftColors.StatusEmerald,
    background = ArtCraftColors.BgCarbon,
    surface = ArtCraftColors.SurfaceCard,
    onPrimary = ArtCraftColors.TextPrimary,
    onBackground = ArtCraftColors.TextPrimary,
    onSurface = ArtCraftColors.TextPrimary,
)

@Composable
fun CamCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArtCraftDarkColorScheme,
        content = content,
    )
}

@Composable
fun CamCraftScreen(
    state: StreamServiceState,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning = state.status == StreamConnectionStatus.LISTENING ||
        state.status == StreamConnectionStatus.CONNECTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ArtCraftColors.BgCarbon)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Top Status Header Card
        Surface(
            color = ArtCraftColors.SurfaceCard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = when (state.status) {
                            StreamConnectionStatus.STOPPED -> "Server Inactive"
                            StreamConnectionStatus.LISTENING -> "Waiting for PC..."
                            StreamConnectionStatus.CONNECTED -> "PC Client Connected"
                            StreamConnectionStatus.ERROR -> "Error Occurred"
                        },
                        color = when (state.status) {
                            StreamConnectionStatus.STOPPED -> ArtCraftColors.TextMuted
                            StreamConnectionStatus.LISTENING -> ArtCraftColors.StatusAmber
                            StreamConnectionStatus.CONNECTED -> ArtCraftColors.StatusEmerald
                            StreamConnectionStatus.ERROR -> ArtCraftColors.StatusRose
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = when (state.status) {
                            StreamConnectionStatus.CONNECTED -> state.clientAddress ?: "127.0.0.1"
                            StreamConnectionStatus.ERROR -> state.errorMessage ?: "Unknown error"
                            else -> "Port 9876 (USB / Wi-Fi)"
                        },
                        color = ArtCraftColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }

                Text(
                    text = "${state.activeState.width}x${state.activeState.height} • ${state.activeState.fps} FPS",
                    color = ArtCraftColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Center Viewfinder / Stream Diagnostics Card
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArtCraftColors.SurfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            if (state.status == StreamConnectionStatus.CONNECTED) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            CamCraftStreamService.attachPreviewView(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                DisposableEffect(Unit) {
                    onDispose {
                        CamCraftStreamService.detachPreviewView()
                    }
                }

                // Overlay HUD
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // Top Live & Resolution Pill
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(
                                ArtCraftColors.BgCarbon.copy(alpha = 0.75f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(ArtCraftColors.StatusEmerald, CircleShape),
                        )
                        Text(
                            text = "LIVE • ${state.activeState.width}x${state.activeState.height}",
                            color = ArtCraftColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Top-Right Bitrate Pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                ArtCraftColors.BgCarbon.copy(alpha = 0.75f),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "${state.bitrateKbps / 1_000} Mbps",
                            color = ArtCraftColors.AccentBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Bottom Diagnostics Banner
                    Surface(
                        color = ArtCraftColors.BgCarbon.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = String.format("%.1f FPS", state.currentFps),
                                    color = ArtCraftColors.StatusEmerald,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = "Target: ${state.activeState.fps} FPS",
                                    color = ArtCraftColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${state.framesSent} frames",
                                    color = ArtCraftColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = "Camera ${state.activeState.cameraId}",
                                    color = ArtCraftColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (state.status) {
                        StreamConnectionStatus.LISTENING -> {
                            Text(
                                text = "READY FOR ADB USB TUNNEL",
                                color = ArtCraftColors.AccentBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Listening on TCP 9876",
                                color = ArtCraftColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Run `cargo run -p camcraft-cli -- connect --usb` on your Fedora PC",
                                color = ArtCraftColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                        StreamConnectionStatus.ERROR -> {
                            Text(
                                text = "SERVER ERROR",
                                color = ArtCraftColors.StatusRose,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.errorMessage ?: "Unknown error",
                                color = ArtCraftColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                        StreamConnectionStatus.STOPPED -> {
                            Text(
                                text = "CamCraft Stream Server",
                                color = ArtCraftColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the button below to start the background stream service",
                                color = ArtCraftColors.TextMuted,
                                fontSize = 13.sp,
                            )
                        }
                        StreamConnectionStatus.CONNECTED -> {
                            // Handled in if block above
                        }
                    }
                }
            }
        }

        // Bottom Action Button
        Button(
            onClick = onToggleServer,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) ArtCraftColors.StatusRose else ArtCraftColors.AccentBlue,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isRunning) "Stop Server" else "Start Server",
                color = ArtCraftColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
