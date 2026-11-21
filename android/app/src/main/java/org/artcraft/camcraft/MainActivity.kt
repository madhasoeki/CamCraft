package org.artcraft.camcraft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.hardware.usb.UsbAccessory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.artcraft.camcraft.network.CamCraftStreamService
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.network.UsbAccessoryTransport
import org.artcraft.camcraft.ui.CamCraftScreen
import org.artcraft.camcraft.ui.OledSaverOverlay
import org.artcraft.camcraft.ui.rememberDeviceTelemetry
import org.artcraft.camcraft.ui.theme.ArtCraftColors
import org.artcraft.camcraft.ui.theme.CamCraftTheme
import org.artcraft.camcraft.update.AppUpdateManager

class MainActivity : ComponentActivity() {

    private var pendingUsbAccessory: UsbAccessory? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED)

        val accessory = pendingUsbAccessory
        pendingUsbAccessory = null

        if (cameraGranted) {
            if (accessory != null) {
                CamCraftStreamService.startUsbAccessory(this, accessory)
            } else {
                CamCraftStreamService.start(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        CamCraftStreamService.refreshLocalIp(this)
        AppUpdateManager.checkOnStartup()

        handleIncomingIntent(intent, checkAttachedAccessory = true)

        setContent {
            val serviceState by CamCraftStreamService.serviceState.collectAsStateWithLifecycle()
            val updateState by AppUpdateManager.state.collectAsStateWithLifecycle()
            val deviceTelemetry = rememberDeviceTelemetry()
            var isDimmed by rememberSaveable { mutableStateOf(false) }
            var isForeground by remember { mutableStateOf(true) }

            LifecycleStartEffect(Unit) {
                isForeground = true
                onStopOrDispose {
                    isForeground = false
                    CamCraftStreamService.detachPreviewView()
                }
            }

            CamCraftTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isDimmed) Color.Black else ArtCraftColors.BgCarbon),
                ) {
                    CamCraftScreen(
                        state = serviceState,
                        onToggleServer = {
                            val isRunning = serviceState.status == StreamConnectionStatus.LISTENING ||
                                serviceState.status == StreamConnectionStatus.CONNECTED

                            if (isRunning) {
                                CamCraftStreamService.stop(this@MainActivity)
                            } else {
                                startServerWithPermissionCheck()
                            }
                        },
                        onSwitchCamera = { CamCraftStreamService.switchCamera() },
                        onToggleTorch = { CamCraftStreamService.toggleTorch() },
                        onToggleMute = { CamCraftStreamService.toggleMute() },
                        onDimScreen = { isDimmed = true },
                        onAttachPreview = { CamCraftStreamService.attachPreviewView(it) },
                        onDetachPreview = { CamCraftStreamService.detachPreviewView() },
                        onTapToFocus = { CamCraftStreamService.focusAtPoint(it) },
                        showViewfinder = !isDimmed && isForeground,
                        telemetry = deviceTelemetry,
                        updateState = updateState,
                        onUpdateAction = { AppUpdateManager.downloadAndInstall(this@MainActivity) },
                        onDismissUpdate = { AppUpdateManager.dismiss() },
                        onCheckUpdates = { AppUpdateManager.checkManually() },
                        modifier = Modifier.safeDrawingPadding(),
                    )

                    if (isDimmed) {
                        OledSaverOverlay(
                            state = serviceState,
                            onExit = { isDimmed = false },
                            telemetry = deviceTelemetry,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CamCraftStreamService.refreshLocalIp(this)
        AppUpdateManager.onResumeCheckPendingInstall(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent, checkAttachedAccessory = false)
    }

    private fun handleIncomingIntent(intent: Intent?, checkAttachedAccessory: Boolean) {
        val accessoryFromIntent = UsbAccessoryTransport.extractAccessoryFromIntent(intent)
            ?.takeIf { UsbAccessoryTransport.isCamCraftAccessory(it) }
        val accessory = accessoryFromIntent
            ?: if (checkAttachedAccessory) {
                UsbAccessoryTransport.findAttachedCamCraftAccessory(this)
            } else {
                null
            }

        if (accessory != null) {
            startUsbAccessoryWithPermissionCheck(accessory)
        } else if (intent?.getBooleanExtra("autostart", false) == true) {
            startServerWithPermissionCheck()
        }
    }

    private fun missingStreamPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startUsbAccessoryWithPermissionCheck(accessory: UsbAccessory) {
        val missing = missingStreamPermissions()
        if (missing.isEmpty()) {
            pendingUsbAccessory = null
            CamCraftStreamService.startUsbAccessory(this, accessory)
        } else {
            pendingUsbAccessory = accessory
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startServerWithPermissionCheck() {
        val attachedAccessory = UsbAccessoryTransport.findAttachedCamCraftAccessory(this)
        if (attachedAccessory != null) {
            startUsbAccessoryWithPermissionCheck(attachedAccessory)
            return
        }

        val missing = missingStreamPermissions()
        if (missing.isEmpty()) {
            pendingUsbAccessory = null
            CamCraftStreamService.start(this)
        } else {
            pendingUsbAccessory = null
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }
}
