package org.artcraft.camcraft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import org.artcraft.camcraft.network.CamCraftStreamService
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.ui.ArtCraftColors
import org.artcraft.camcraft.ui.CamCraftScreen
import org.artcraft.camcraft.ui.CamCraftTheme
import org.artcraft.camcraft.ui.OledSaverOverlay

class MainActivity : ComponentActivity() {

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED)

        if (cameraGranted) {
            CamCraftStreamService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CamCraftStreamService.refreshLocalIp(this)

        if (intent?.getBooleanExtra("autostart", false) == true) {
            startServerWithPermissionCheck()
        }

        setContent {
            val serviceState by CamCraftStreamService.serviceState.collectAsState()
            var isDimmed by rememberSaveable { mutableStateOf(false) }

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
                        modifier = Modifier.safeDrawingPadding(),
                    )

                    if (isDimmed) {
                        OledSaverOverlay(
                            state = serviceState,
                            onExit = { isDimmed = false },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CamCraftStreamService.refreshLocalIp(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("autostart", false)) {
            startServerWithPermissionCheck()
        }
    }

    private fun startServerWithPermissionCheck() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            CamCraftStreamService.start(this)
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }
}
