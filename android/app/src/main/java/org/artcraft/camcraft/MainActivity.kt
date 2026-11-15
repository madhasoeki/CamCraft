package org.artcraft.camcraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.artcraft.camcraft.ui.CamCraftScreen
import org.artcraft.camcraft.ui.CamCraftTheme
import org.artcraft.camcraft.ui.CamCraftUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamCraftTheme {
                CamCraftScreen(
                    state = CamCraftUiState(
                        statusBadge = "Waiting for PC (Port ${CcftProtocolConstants.DEFAULT_PORT})",
                    ),
                    onToggleStream = {},
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
    }
}
