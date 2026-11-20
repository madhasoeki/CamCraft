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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import org.artcraft.camcraft.network.CamCraftStreamService
import org.artcraft.camcraft.network.StreamConnectionStatus
import org.artcraft.camcraft.ui.CamCraftScreen
import org.artcraft.camcraft.ui.CamCraftTheme

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

        if (intent?.getBooleanExtra("autostart", false) == true) {
            startServerWithPermissionCheck()
        }

        setContent {
            val serviceState by CamCraftStreamService.serviceState.collectAsState()

            CamCraftTheme {
                CamCraftScreen(
                    state = serviceState,
                    onToggleServer = {
                        val isRunning = serviceState.status == StreamConnectionStatus.LISTENING ||
                            serviceState.status == StreamConnectionStatus.CONNECTED

                        if (isRunning) {
                            CamCraftStreamService.stop(this)
                        } else {
                            startServerWithPermissionCheck()
                        }
                    },
                    onSwitchCamera = { CamCraftStreamService.switchCamera() },
                    onToggleTorch = { CamCraftStreamService.toggleTorch() },
                    onToggleMute = { CamCraftStreamService.toggleMute() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
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
