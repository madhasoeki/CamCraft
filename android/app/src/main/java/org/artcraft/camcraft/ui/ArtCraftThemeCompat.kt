package org.artcraft.camcraft.ui

import androidx.compose.runtime.Composable

/**
 * Compatibility exports preserving `org.artcraft.camcraft.ui.ArtCraftColors` and
 * `org.artcraft.camcraft.ui.CamCraftTheme` imports across existing UI files and previews.
 */
typealias ArtCraftColors = org.artcraft.camcraft.ui.theme.ArtCraftColors

@Composable
fun CamCraftTheme(content: @Composable () -> Unit) {
    org.artcraft.camcraft.ui.theme.CamCraftTheme(content = content)
}
