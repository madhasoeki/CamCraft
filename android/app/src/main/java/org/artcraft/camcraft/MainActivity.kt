package org.artcraft.camcraft

import android.Manifest
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            CamCraftStreamService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
    }

    private fun startServerWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionStatus == PackageManager.PERMISSION_GRANTED) {
                CamCraftStreamService.start(this)
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            CamCraftStreamService.start(this)
        }
    }
}
