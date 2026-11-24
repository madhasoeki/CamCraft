package org.artcraft.camcraft.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.delay
import org.artcraft.camcraft.BuildConfig
import org.artcraft.camcraft.R
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.network.StreamServiceState
import org.artcraft.camcraft.protocol.CameraInfo
import org.artcraft.camcraft.protocol.ResolutionOption
import org.artcraft.camcraft.ui.components.CamCraftIcons
import org.artcraft.camcraft.ui.theme.ArtCraftColors
import org.artcraft.camcraft.ui.theme.CamCraftTheme
import org.artcraft.camcraft.ui.theme.TabularMonoStyle
import org.artcraft.camcraft.update.UpdateUiState

/**
 * Stateless ArtCraft Studio v2 main screen.
 *
 * All service interactions are hoisted via callbacks so UI composables remain pure
 * and [PreviewView] can detach cleanly whenever [showViewfinder] becomes `false`
 * (e.g., during OLED Saver mode or Activity backgrounding).
 */
@Composable
fun CamCraftScreen(
    state: StreamServiceState,
    onToggleServer: () -> Unit,
    onSwitchCamera: () -> Unit = {},
    onToggleTorch: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onDimScreen: () -> Unit = {},
    onAttachPreview: (PreviewView) -> Unit = {},
    onDetachPreview: () -> Unit = {},
    onTapToFocus: (MeteringPoint) -> Unit = {},
    showViewfinder: Boolean = true,
    telemetry: DeviceTelemetry? = null,
    updateState: UpdateUiState = UpdateUiState.Idle,
    appVersionName: String = BuildConfig.VERSION_NAME,
    onUpdateAction: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isRunning = state.isServerActive
    val activeCamera = state.cameras.find { it.id == state.activeState.cameraId }
    val activeCameraLabel = activeCamera?.label ?: "Camera ${state.activeState.cameraId}"
    val hasFlash = activeCamera?.hasFlash ?: true
    val hasMultiCamera = state.cameras.size > 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ArtCraftColors.BgCarbon)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TopStatusHeader(
            status = state.status,
            clientAddress = state.clientAddress,
            errorMessage = state.errorMessage,
            listenPort = state.listenPort,
            wifiEndpoint = state.wifiEndpoint,
            mdnsRegistered = state.mdnsRegistered,
            width = state.activeState.width,
            height = state.activeState.height,
            fps = state.activeState.fps,
            telemetry = telemetry,
            updateState = updateState,
            appVersionName = appVersionName,
            onCheckUpdates = onCheckUpdates,
        )

        UpdateBanner(
            updateState = updateState,
            onUpdateAction = onUpdateAction,
            onDismiss = onDismissUpdate,
        )

        ViewfinderStage(
            status = state.status,
            hasActiveSession = state.hasActiveSession,
            showViewfinder = showViewfinder,
            wifiEndpoint = state.wifiEndpoint,
            listenPort = state.listenPort,
            mdnsRegistered = state.mdnsRegistered,
            mdnsServiceName = state.mdnsServiceName,
            errorMessage = state.errorMessage,
            width = state.activeState.width,
            height = state.activeState.height,
            targetFps = state.activeState.fps,
            bitrateKbps = state.bitrateKbps,
            currentFps = state.currentFps,
            framesSent = state.framesSent,
            zoomRatio = state.activeState.zoomRatio,
            exposureEv = state.activeState.exposureEv,
            activeCameraLabel = activeCameraLabel,
            onAttachPreview = onAttachPreview,
            onDetachPreview = onDetachPreview,
            onTapToFocus = onTapToFocus,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        QuickControlDock(
            isRunning = isRunning,
            isConnected = state.status == StreamConnectionStatus.CONNECTED,
            hasMultiCamera = hasMultiCamera,
            hasFlash = hasFlash,
            torchOn = state.activeState.torchOn,
            audioEnabled = state.activeState.audioEnabled,
            audioMuted = state.activeState.audioMuted,
            onSwitchCamera = onSwitchCamera,
            onToggleTorch = onToggleTorch,
            onToggleMute = onToggleMute,
            onDimScreen = onDimScreen,
        )

        PrimaryServerButton(
            isRunning = isRunning,
            onToggleServer = onToggleServer,
        )
    }
}

@Composable
private fun TopStatusHeader(
    status: StreamConnectionStatus,
    clientAddress: String?,
    errorMessage: String?,
    listenPort: Int,
    wifiEndpoint: String?,
    mdnsRegistered: Boolean,
    width: Int,
    height: Int,
    fps: Int,
    telemetry: DeviceTelemetry?,
    updateState: UpdateUiState,
    appVersionName: String,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var copiedFeedbackCount by remember { mutableIntStateOf(0) }
    var showCopied by remember { mutableStateOf(false) }

    LaunchedEffect(copiedFeedbackCount) {
        if (copiedFeedbackCount > 0) {
            showCopied = true
            delay(1_800L)
            showCopied = false
        }
    }

    val statusColor = when (status) {
        StreamConnectionStatus.CONNECTED -> ArtCraftColors.StatusEmerald
        StreamConnectionStatus.LISTENING -> ArtCraftColors.StatusAmber
        StreamConnectionStatus.ERROR -> ArtCraftColors.StatusRose
        StreamConnectionStatus.STOPPED -> ArtCraftColors.TextMuted
    }
    val statusLabel = when (status) {
        StreamConnectionStatus.CONNECTED -> "LIVE"
        StreamConnectionStatus.LISTENING -> "READY"
        StreamConnectionStatus.ERROR -> "ERROR"
        StreamConnectionStatus.STOPPED -> "STANDBY"
    }
    val isUsbConnected = status == StreamConnectionStatus.CONNECTED && clientAddress == "127.0.0.1"
    val statusIcon = when (status) {
        StreamConnectionStatus.CONNECTED -> if (isUsbConnected) CamCraftIcons.Usb else CamCraftIcons.Wifi
        StreamConnectionStatus.LISTENING -> CamCraftIcons.Wifi
        StreamConnectionStatus.ERROR,
        StreamConnectionStatus.STOPPED,
        -> CamCraftIcons.Power
    }
    val subtitleText = when (status) {
        StreamConnectionStatus.CONNECTED -> if (isUsbConnected) {
            "USB ADB • 127.0.0.1"
        } else {
            "Wi-Fi • ${clientAddress ?: "Connected"}"
        }
        StreamConnectionStatus.ERROR -> errorMessage ?: "Unknown server error"
        StreamConnectionStatus.LISTENING,
        StreamConnectionStatus.STOPPED,
        -> "Port $listenPort • USB / Wi-Fi"
    }

    val batterySummary = remember(telemetry) { telemetry?.let(::formatBatterySummary) }
    val thermalColor = remember(telemetry?.temperatureC) {
        val temp = telemetry?.temperatureC
        when {
            temp == null || temp < 40f -> ArtCraftColors.StatusEmerald
            temp < 44f -> ArtCraftColors.StatusAmber
            else -> ArtCraftColors.StatusRose
        }
    }

    Surface(
        color = ArtCraftColors.SurfaceCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_camcraft_brand_mark),
                            contentDescription = "CamCraft",
                            modifier = Modifier.size(20.dp),
                        )

                        Surface(
                            color = statusColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(statusColor, CircleShape),
                                )
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = statusLabel,
                                    color = ArtCraftColors.TextPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }

                        val versionLabel = when (updateState) {
                            UpdateUiState.Checking -> "Checking..."
                            UpdateUiState.UpToDate -> "v$appVersionName ✓"
                            is UpdateUiState.Available -> "v$appVersionName ↑"
                            else -> "v$appVersionName"
                        }
                        val versionAccent = when (updateState) {
                            UpdateUiState.UpToDate -> ArtCraftColors.StatusEmerald
                            is UpdateUiState.Available -> ArtCraftColors.AccentBlue
                            UpdateUiState.Checking -> ArtCraftColors.StatusAmber
                            else -> ArtCraftColors.BorderSubtle
                        }
                        Surface(
                            color = ArtCraftColors.SurfaceCardHighlight,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, versionAccent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(
                                    enabled = updateState !is UpdateUiState.Checking &&
                                        updateState !is UpdateUiState.Downloading,
                                    role = Role.Button,
                                    onClickLabel = "Check for app updates",
                                    onClick = onCheckUpdates,
                                ),
                        ) {
                            Text(
                                text = versionLabel,
                                color = ArtCraftColors.TextSecondary,
                                fontSize = 11.sp,
                                style = TabularMonoStyle,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }

                    Text(
                        text = subtitleText,
                        color = ArtCraftColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = ArtCraftColors.SurfaceCardHighlight,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
                    ) {
                        Text(
                            text = "${formatResolutionLabel(width, height)} • $fps FPS",
                            color = ArtCraftColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = TabularMonoStyle,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }

                    if (batterySummary != null) {
                        Surface(
                            color = thermalColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, thermalColor.copy(alpha = 0.35f)),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = "Device telemetry: $batterySummary"
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = CamCraftIcons.Battery,
                                    contentDescription = null,
                                    tint = thermalColor,
                                    modifier = Modifier.size(13.dp),
                                )
                                Text(
                                    text = batterySummary,
                                    color = ArtCraftColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TabularMonoStyle,
                                )
                            }
                        }
                    }
                }
            }

            // Interactive Wi-Fi Endpoint & mDNS Row
            val canCopyEndpoint = wifiEndpoint != null
            Surface(
                color = ArtCraftColors.SurfaceCardHighlight,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (canCopyEndpoint) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = "Copy Wi-Fi endpoint",
                            ) {
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    ClipData.newPlainText("CamCraft Wi-Fi Endpoint", wifiEndpoint),
                                )
                                copiedFeedbackCount++
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = CamCraftIcons.Wifi,
                        contentDescription = null,
                        tint = if (canCopyEndpoint) ArtCraftColors.AccentBlue else ArtCraftColors.StatusAmber,
                        modifier = Modifier.size(16.dp),
                    )

                    Text(
                        text = wifiEndpoint ?: "Wi-Fi: Not connected",
                        color = if (canCopyEndpoint) ArtCraftColors.TextPrimary else ArtCraftColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        style = TabularMonoStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (canCopyEndpoint) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (showCopied) CamCraftIcons.Check else CamCraftIcons.Copy,
                                contentDescription = if (showCopied) "Copied" else "Copy endpoint",
                                tint = if (showCopied) ArtCraftColors.StatusEmerald else ArtCraftColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                            if (showCopied) {
                                Text(
                                    text = "Copied",
                                    color = ArtCraftColors.StatusEmerald,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }

                    Surface(
                        color = if (mdnsRegistered) {
                            ArtCraftColors.StatusEmerald.copy(alpha = 0.16f)
                        } else {
                            ArtCraftColors.BgCarbon.copy(alpha = 0.7f)
                        },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (mdnsRegistered) {
                                ArtCraftColors.StatusEmerald.copy(alpha = 0.35f)
                            } else {
                                ArtCraftColors.BorderSubtle
                            },
                        ),
                    ) {
                        Text(
                            text = if (mdnsRegistered) "mDNS ON" else "mDNS OFF",
                            color = if (mdnsRegistered) {
                                ArtCraftColors.TextPrimary
                            } else {
                                ArtCraftColors.TextMuted
                            },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(
    updateState: UpdateUiState,
    onUpdateAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = updateState is UpdateUiState.Available ||
        updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.ReadyToInstall ||
        updateState is UpdateUiState.NeedsInstallPermission ||
        updateState is UpdateUiState.Error
    if (!visible) return

    val accent = when (updateState) {
        is UpdateUiState.ReadyToInstall -> ArtCraftColors.StatusEmerald
        is UpdateUiState.NeedsInstallPermission -> ArtCraftColors.StatusAmber
        is UpdateUiState.Error -> ArtCraftColors.StatusRose
        else -> ArtCraftColors.AccentBlue
    }

    Surface(
        color = ArtCraftColors.SurfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = CamCraftIcons.Download,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    val title = when (updateState) {
                        is UpdateUiState.Available -> "Update v${updateState.release.version} available"
                        is UpdateUiState.Downloading -> "Downloading v${updateState.version}..."
                        is UpdateUiState.ReadyToInstall -> "v${updateState.release.version} ready to install"
                        is UpdateUiState.NeedsInstallPermission -> "Allow unknown app installs"
                        is UpdateUiState.Error -> "Update failed"
                    }
                    val detail = when (updateState) {
                        is UpdateUiState.Available -> updateState.release.releaseNotes
                            .lineSequence()
                            .map { it.trim() }
                            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                            ?: "Tap Update to install directly without leaving CamCraft."
                        is UpdateUiState.Downloading -> {
                            val dlMb = updateState.downloadedBytes / (1024f * 1024f)
                            val totMb = updateState.totalBytes / (1024f * 1024f)
                            String.format(Locale.US, "%.1f / %.1f MB", dlMb, totMb)
                        }
                        is UpdateUiState.ReadyToInstall -> "SHA-256 verified. Tap Install to finish."
                        is UpdateUiState.NeedsInstallPermission -> "Grant permission in Settings to install APK."
                        is UpdateUiState.Error -> updateState.message
                    }
                    Text(
                        text = title,
                        color = ArtCraftColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        color = ArtCraftColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val actionLabel = when (updateState) {
                    is UpdateUiState.Available -> "Update"
                    is UpdateUiState.ReadyToInstall -> "Install"
                    is UpdateUiState.NeedsInstallPermission -> "Allow"
                    is UpdateUiState.Error -> if (updateState.retryRelease != null) "Retry" else null
                    else -> null
                }
                if (actionLabel != null) {
                    Button(
                        onClick = onUpdateAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = ArtCraftColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 32.dp),
                    ) {
                        Text(
                            text = actionLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (updateState !is UpdateUiState.Downloading) {
                    Text(
                        text = "×",
                        color = ArtCraftColors.TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Dismiss update banner",
                                onClick = onDismiss,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (updateState is UpdateUiState.Downloading) {
                LinearProgressIndicator(
                    progress = { updateState.progress.coerceIn(0f, 1f) },
                    color = ArtCraftColors.AccentBlue,
                    trackColor = ArtCraftColors.BgStage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}

@Composable
private fun ViewfinderStage(
    status: StreamConnectionStatus,
    hasActiveSession: Boolean,
    showViewfinder: Boolean,
    wifiEndpoint: String?,
    listenPort: Int,
    mdnsRegistered: Boolean,
    mdnsServiceName: String?,
    errorMessage: String?,
    width: Int,
    height: Int,
    targetFps: Int,
    bitrateKbps: Int,
    currentFps: Float,
    framesSent: Long,
    zoomRatio: Float,
    exposureEv: Float,
    activeCameraLabel: String,
    onAttachPreview: (PreviewView) -> Unit,
    onDetachPreview: () -> Unit,
    onTapToFocus: (MeteringPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGrid by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ArtCraftColors.BgStage)
            .border(1.dp, ArtCraftColors.BorderSubtle, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (status == StreamConnectionStatus.CONNECTED) {
            if (showViewfinder) {
                ViewfinderPreviewCanvas(
                    showGrid = showGrid,
                    onAttachPreview = onAttachPreview,
                    onDetachPreview = onDetachPreview,
                    onTapToFocus = onTapToFocus,
                )

                ViewfinderTopHud(
                    width = width,
                    height = height,
                    bitrateKbps = bitrateKbps,
                    showGrid = showGrid,
                    onToggleGrid = { showGrid = !showGrid },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            } else {
                ViewfinderPausedPlaceholder()
            }

            ViewfinderDiagnosticsBar(
                currentFps = currentFps,
                targetFps = targetFps,
                framesSent = framesSent,
                zoomRatio = zoomRatio,
                activeCameraLabel = activeCameraLabel,
                exposureEv = exposureEv,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        } else {
            IdleStageContent(
                status = status,
                hasActiveSession = hasActiveSession,
                wifiEndpoint = wifiEndpoint,
                listenPort = listenPort,
                mdnsRegistered = mdnsRegistered,
                mdnsServiceName = mdnsServiceName,
                errorMessage = errorMessage,
            )
        }
    }
}

@Composable
private fun ViewfinderPreviewCanvas(
    showGrid: Boolean,
    onAttachPreview: (PreviewView) -> Unit,
    onDetachPreview: () -> Unit,
    onTapToFocus: (MeteringPoint) -> Unit,
) {
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var focusRingOffset by remember { mutableStateOf<Offset?>(null) }
    var focusRingSequence by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusRingSequence) {
        if (focusRingSequence > 0) {
            delay(1_100L)
            focusRingOffset = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewViewRef = null
            onDetachPreview()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    previewViewRef = this
                    onAttachPreview(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onTapToFocus) {
                    detectTapGestures { offset ->
                        val view = previewViewRef ?: return@detectTapGestures
                        val point = view.meteringPointFactory.createPoint(offset.x, offset.y)
                        focusRingOffset = offset
                        focusRingSequence++
                        onTapToFocus(point)
                    }
                },
        )

        if (showGrid) {
            ViewfinderGridOverlay(modifier = Modifier.fillMaxSize())
        }

        val reticleCenter = focusRingOffset
        if (reticleCenter != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxSize = 52.dp.toPx()
                val half = boxSize / 2f
                val corner = 8.dp.toPx()
                drawRoundRect(
                    color = ArtCraftColors.AccentBlue,
                    topLeft = Offset(reticleCenter.x - half, reticleCenter.y - half),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    color = ArtCraftColors.TextPrimary.copy(alpha = 0.85f),
                    radius = 3.dp.toPx(),
                    center = reticleCenter,
                )
            }
        }
    }
}

@Composable
private fun ViewfinderGridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokePx = 1.dp.toPx()
        val lineColor = Color.White.copy(alpha = 0.28f)
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        for (i in 1..2) {
            val x = thirdW * i
            drawLine(
                color = lineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokePx,
            )
            val y = thirdH * i
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
            )
        }
    }
}

@Composable
private fun ViewfinderTopHud(
    width: Int,
    height: Int,
    bitrateKbps: Int,
    showGrid: Boolean,
    onToggleGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = ArtCraftColors.BgStage.copy(alpha = 0.78f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(ArtCraftColors.StatusEmerald, CircleShape),
                )
                Text(
                    text = "LIVE • ${width}x${height}",
                    color = ArtCraftColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = TabularMonoStyle,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = ArtCraftColors.BgStage.copy(alpha = 0.78f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
            ) {
                Text(
                    text = "${bitrateKbps / 1_000} Mbps",
                    color = ArtCraftColors.AccentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = TabularMonoStyle,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            Surface(
                onClick = onToggleGrid,
                color = if (showGrid) {
                    ArtCraftColors.AccentBlue.copy(alpha = 0.24f)
                } else {
                    ArtCraftColors.BgStage.copy(alpha = 0.78f)
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (showGrid) ArtCraftColors.AccentBlue else ArtCraftColors.BorderSubtle,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        role = Role.Switch
                        contentDescription = "Rule of thirds grid"
                        stateDescription = if (showGrid) "On" else "Off"
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = CamCraftIcons.GridOn,
                        contentDescription = null,
                        tint = if (showGrid) ArtCraftColors.TextPrimary else ArtCraftColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewfinderDiagnosticsBar(
    currentFps: Float,
    targetFps: Int,
    framesSent: Long,
    zoomRatio: Float,
    activeCameraLabel: String,
    exposureEv: Float,
    modifier: Modifier = Modifier,
) {
    val fpsHealthy = currentFps >= targetFps * 0.85f || currentFps == 0f
    Surface(
        color = ArtCraftColors.BgStage.copy(alpha = 0.84f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = String.format(Locale.US, "%.1f FPS", currentFps),
                    color = if (fpsHealthy) ArtCraftColors.StatusEmerald else ArtCraftColors.StatusAmber,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = TabularMonoStyle,
                )
                Text(
                    text = "Target: $targetFps FPS",
                    color = ArtCraftColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$framesSent frames • ${String.format(Locale.US, "%.1fx", zoomRatio)}",
                    color = ArtCraftColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TabularMonoStyle,
                )
                Text(
                    text = "$activeCameraLabel • EV ${String.format(Locale.US, "%+.1f", exposureEv)}",
                    color = ArtCraftColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ViewfinderPausedPlaceholder() {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = ArtCraftColors.SurfaceCard,
            shape = CircleShape,
            border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = CamCraftIcons.OledDim,
                    contentDescription = null,
                    tint = ArtCraftColors.TextSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            text = "Viewfinder Paused",
            color = ArtCraftColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Camera feed continues streaming to PC while display preview is detached.",
            color = ArtCraftColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdleStageContent(
    status: StreamConnectionStatus,
    hasActiveSession: Boolean,
    wifiEndpoint: String?,
    listenPort: Int,
    mdnsRegistered: Boolean,
    mdnsServiceName: String?,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (status) {
            StreamConnectionStatus.LISTENING -> {
                Surface(
                    color = ArtCraftColors.AccentBlue.copy(alpha = 0.14f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, ArtCraftColors.AccentBlue.copy(alpha = 0.35f)),
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = CamCraftIcons.Wifi,
                            contentDescription = null,
                            tint = ArtCraftColors.AccentBlue,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "READY FOR USB OR WI-FI",
                    color = ArtCraftColors.AccentBlue,
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = ArtCraftColors.SurfaceCardHighlight,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
                ) {
                    Text(
                        text = wifiEndpoint ?: "Port $listenPort (USB / Wi-Fi)",
                        color = ArtCraftColors.TextPrimary,
                        fontSize = if (wifiEndpoint != null) 20.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        style = TabularMonoStyle,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                if (mdnsRegistered) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = ArtCraftColors.StatusEmerald.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, ArtCraftColors.StatusEmerald.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = "mDNS: ${mdnsServiceName ?: "CamCraft Android"}",
                            color = ArtCraftColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Fedora camcraft-gui discovers this device automatically over Wi-Fi, or connect via USB ADB.",
                    color = ArtCraftColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            StreamConnectionStatus.ERROR -> {
                Surface(
                    color = ArtCraftColors.StatusRose.copy(alpha = 0.14f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, ArtCraftColors.StatusRose.copy(alpha = 0.35f)),
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = CamCraftIcons.Power,
                            contentDescription = null,
                            tint = ArtCraftColors.StatusRose,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (hasActiveSession) "VIDEO UNAVAILABLE" else "SERVER ERROR",
                    color = ArtCraftColors.StatusRose,
                    style = MaterialTheme.typography.titleSmall,
                    letterSpacing = 0.8.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "Unknown error",
                    color = ArtCraftColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            StreamConnectionStatus.STOPPED -> {
                Surface(
                    color = ArtCraftColors.BgCarbon,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, ArtCraftColors.BorderSubtle),
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_camcraft_brand_mark),
                            contentDescription = "CamCraft logo",
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "CamCraft Stream Server",
                    color = ArtCraftColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tap Start Server below to stream low-latency camera and audio to your Linux desktop.",
                    color = ArtCraftColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            StreamConnectionStatus.CONNECTED -> {
                // Rendered by viewfinder branch in ViewfinderStage
            }
        }
    }
}

@Composable
private fun QuickControlDock(
    isRunning: Boolean,
    isConnected: Boolean,
    hasMultiCamera: Boolean,
    hasFlash: Boolean,
    torchOn: Boolean,
    audioEnabled: Boolean,
    audioMuted: Boolean,
    onSwitchCamera: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleMute: () -> Unit,
    onDimScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickControlTile(
            label = "Lens",
            icon = CamCraftIcons.SwitchCamera,
            enabled = isConnected && hasMultiCamera,
            active = false,
            activeColor = ArtCraftColors.AccentBlue,
            role = Role.Button,
            stateDesc = if (isConnected && hasMultiCamera) "Switch camera lens" else "Unavailable",
            onClick = onSwitchCamera,
            modifier = Modifier.weight(1f),
        )

        QuickControlTile(
            label = if (torchOn) "Torch On" else "Torch",
            icon = if (torchOn) CamCraftIcons.FlashOn else CamCraftIcons.FlashOff,
            enabled = isConnected && hasFlash,
            active = torchOn,
            activeColor = ArtCraftColors.StatusAmber,
            role = Role.Switch,
            stateDesc = if (torchOn) "On" else "Off",
            onClick = onToggleTorch,
            modifier = Modifier.weight(1f),
        )

        val micLabel = when {
            !audioEnabled -> "Mic Off"
            audioMuted -> "Muted"
            else -> "Mic"
        }
        val micStateDesc = when {
            !audioEnabled -> "Disabled"
            audioMuted -> "Muted"
            else -> "Active"
        }
        QuickControlTile(
            label = micLabel,
            icon = if (!audioEnabled || audioMuted) CamCraftIcons.MicOff else CamCraftIcons.MicOn,
            enabled = isRunning && audioEnabled,
            active = audioEnabled && audioMuted,
            activeColor = ArtCraftColors.StatusRose,
            role = Role.Switch,
            stateDesc = micStateDesc,
            onClick = onToggleMute,
            modifier = Modifier.weight(1f),
        )

        QuickControlTile(
            label = "Dim",
            icon = CamCraftIcons.OledDim,
            enabled = true,
            active = false,
            activeColor = ArtCraftColors.AccentBlue,
            role = Role.Button,
            stateDesc = "Activate OLED battery saver",
            onClick = onDimScreen,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickControlTile(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    active: Boolean,
    activeColor: Color,
    role: Role,
    stateDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        active && enabled -> activeColor.copy(alpha = 0.16f)
        enabled -> ArtCraftColors.SurfaceCardHighlight
        else -> ArtCraftColors.SurfaceCard
    }
    val borderColor = when {
        active && enabled -> activeColor.copy(alpha = 0.7f)
        else -> ArtCraftColors.BorderSubtle
    }
    val iconTint = when {
        active && enabled -> activeColor
        enabled -> ArtCraftColors.TextPrimary
        else -> ArtCraftColors.TextMuted
    }
    val textColor = if (enabled) ArtCraftColors.TextPrimary else ArtCraftColors.TextMuted

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .heightIn(min = 58.dp)
            .semantics(mergeDescendants = true) {
                this.role = role
                this.stateDescription = stateDesc
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PrimaryServerButton(
    isRunning: Boolean,
    onToggleServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onToggleServer,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isRunning) ArtCraftColors.StatusRose else ArtCraftColors.AccentBlue,
            contentColor = ArtCraftColors.TextPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(
            imageVector = CamCraftIcons.Power,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isRunning) "Stop Server" else "Start Server",
            color = ArtCraftColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatResolutionLabel(width: Int, height: Int): String = when {
    height >= 2160 -> "4K"
    height == 1080 -> "1080p"
    height == 720 -> "720p"
    else -> "${width}x${height}"
}

@Preview(showBackground = true)
@Composable
fun CamCraftScreenListeningPreview() {
    CamCraftTheme {
        CamCraftScreen(
            state = StreamServiceState(
                status = StreamConnectionStatus.LISTENING,
                localIpAddress = "192.168.1.42",
                mdnsRegistered = true,
                mdnsServiceName = "Pixel 8 Pro",
            ),
            onToggleServer = {},
            telemetry = DeviceTelemetry(
                batteryPct = 84,
                temperatureC = 34.0f,
                isCharging = false,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CamCraftScreenStoppedPreview() {
    CamCraftTheme {
        CamCraftScreen(
            state = StreamServiceState(
                status = StreamConnectionStatus.STOPPED,
                localIpAddress = "192.168.1.42",
            ),
            onToggleServer = {},
            telemetry = DeviceTelemetry(
                batteryPct = 91,
                temperatureC = 31.5f,
                isCharging = true,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun CamCraftScreenConnectedPreview() {
    CamCraftTheme {
        CamCraftScreen(
            state = StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "192.168.1.10",
                localIpAddress = "192.168.1.42",
                mdnsRegistered = true,
                cameras = listOf(
                    CameraInfo.create(
                        id = "0",
                        facing = "back",
                        label = "Back Wide",
                        resolutions = listOf(ResolutionOption(1920, 1080, 60)),
                        maxZoom = 8.0f,
                        minEv = -2.0f,
                        maxEv = 2.0f,
                        hasFlash = true,
                    ),
                    CameraInfo.create(
                        id = "1",
                        facing = "front",
                        label = "Front",
                        resolutions = listOf(ResolutionOption(1920, 1080, 30)),
                        maxZoom = 2.0f,
                        minEv = -2.0f,
                        maxEv = 2.0f,
                        hasFlash = false,
                    ),
                ),
                currentFps = 60.0f,
                framesSent = 14_520L,
                bitrateKbps = 12_000,
            ),
            onToggleServer = {},
            showViewfinder = false,
            telemetry = DeviceTelemetry(
                batteryPct = 78,
                temperatureC = 41.2f,
                isCharging = true,
            ),
        )
    }
}
