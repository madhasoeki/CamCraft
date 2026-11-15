package org.artcraft.camcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object ArtCraftColors {
    val BgCarbon = Color(0xFF111318)
    val SurfaceCard = Color(0xFF1C2026)
    val AccentBlue = Color(0xFF3B82F6)
    val StatusEmerald = Color(0xFF10B981)
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

data class CamCraftUiState(
    val statusBadge: String = "Waiting for PC (Port 9876)",
    val resolutionLabel: String = "1080p • 60 FPS",
    val isStreaming: Boolean = false,
)

@Composable
fun CamCraftScreen(
    state: CamCraftUiState,
    onToggleStream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ArtCraftColors.BgCarbon)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Surface(
            color = ArtCraftColors.SurfaceCard,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.statusBadge,
                    color = if (state.isStreaming) ArtCraftColors.StatusEmerald else ArtCraftColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.resolutionLabel,
                    color = ArtCraftColors.TextMuted,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(ArtCraftColors.SurfaceCard, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CameraX Viewfinder (Stage 3)",
                color = ArtCraftColors.TextMuted,
            )
        }

        Button(
            onClick = onToggleStream,
            colors = ButtonDefaults.buttonColors(containerColor = ArtCraftColors.AccentBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (state.isStreaming) "Stop Server" else "Start Server (Stage 2)",
                color = ArtCraftColors.TextPrimary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}
