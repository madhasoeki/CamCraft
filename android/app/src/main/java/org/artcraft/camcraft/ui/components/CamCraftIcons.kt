package org.artcraft.camcraft.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight pure Jetpack Compose vector icon set for CamCraft Studio v2.
 * All icons use a 24dp viewport with 2dp rounded-cap strokes.
 */
object CamCraftIcons {

    private val StrokeBrush = SolidColor(Color.White)

    private inline fun strokeIcon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private inline fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 2f,
        crossinline pathData: PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = StrokeBrush,
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = { pathData() },
        )
    }

    val SwitchCamera: ImageVector by lazy {
        strokeIcon("SwitchCamera") {
            // Outer camera silhouette
            strokePath {
                moveTo(4f, 6f)
                lineTo(7.5f, 6f)
                lineTo(9.2f, 4f)
                lineTo(14.8f, 4f)
                lineTo(16.5f, 6f)
                lineTo(20f, 6f)
                quadTo(22f, 6f, 22f, 8f)
                lineTo(22f, 18f)
                quadTo(22f, 20f, 20f, 20f)
                lineTo(4f, 20f)
                quadTo(2f, 20f, 2f, 18f)
                lineTo(2f, 8f)
                quadTo(2f, 6f, 4f, 6f)
                close()
            }
            // Top curved rotation arrow
            strokePath {
                moveTo(8.5f, 11.5f)
                arcTo(
                    horizontalEllipseRadius = 3.8f,
                    verticalEllipseRadius = 3.8f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 15.5f,
                    y1 = 11.5f,
                )
                moveTo(13.5f, 11.5f)
                lineTo(15.7f, 11.7f)
                lineTo(15.5f, 9.5f)
            }
            // Bottom curved rotation arrow
            strokePath {
                moveTo(15.5f, 14.5f)
                arcTo(
                    horizontalEllipseRadius = 3.8f,
                    verticalEllipseRadius = 3.8f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 8.5f,
                    y1 = 14.5f,
                )
                moveTo(10.5f, 14.5f)
                lineTo(8.3f, 14.3f)
                lineTo(8.5f, 16.5f)
            }
        }
    }

    val FlashOn: ImageVector by lazy {
        strokeIcon("FlashOn") {
            strokePath {
                moveTo(13f, 2f)
                lineTo(3f, 14f)
                lineTo(12f, 14f)
                lineTo(11f, 22f)
                lineTo(21f, 10f)
                lineTo(12f, 10f)
                close()
            }
        }
    }

    val FlashOff: ImageVector by lazy {
        strokeIcon("FlashOff") {
            strokePath {
                moveTo(10.5f, 5f)
                lineTo(13f, 2f)
                lineTo(12f, 9f)
                moveTo(17f, 10f)
                lineTo(21f, 10f)
                lineTo(16.5f, 15.4f)
                moveTo(8.5f, 8.5f)
                lineTo(3f, 14f)
                lineTo(12f, 14f)
                lineTo(11f, 22f)
                lineTo(14.5f, 17.8f)
                moveTo(2f, 2f)
                lineTo(22f, 22f)
            }
        }
    }

    val MicOn: ImageVector by lazy {
        strokeIcon("MicOn") {
            strokePath {
                moveTo(12f, 2f)
                quadTo(15f, 2f, 15f, 5f)
                lineTo(15f, 10f)
                quadTo(15f, 13f, 12f, 13f)
                quadTo(9f, 13f, 9f, 10f)
                lineTo(9f, 5f)
                quadTo(9f, 2f, 12f, 2f)
                close()
            }
            strokePath {
                moveTo(5f, 10f)
                lineTo(5f, 11f)
                arcTo(
                    horizontalEllipseRadius = 7f,
                    verticalEllipseRadius = 7f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 19f,
                    y1 = 11f,
                )
                lineTo(19f, 10f)
                moveTo(12f, 18f)
                lineTo(12f, 22f)
                moveTo(8f, 22f)
                lineTo(16f, 22f)
            }
        }
    }

    val MicOff: ImageVector by lazy {
        strokeIcon("MicOff") {
            strokePath {
                moveTo(9f, 6f)
                lineTo(9f, 5f)
                quadTo(9f, 2f, 12f, 2f)
                quadTo(15f, 2f, 15f, 5f)
                lineTo(15f, 9.5f)
                moveTo(9.3f, 11.4f)
                quadTo(10f, 13f, 12f, 13f)
                lineTo(12.6f, 12.9f)
                moveTo(5f, 10f)
                lineTo(5f, 11f)
                arcTo(
                    horizontalEllipseRadius = 7f,
                    verticalEllipseRadius = 7f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = false,
                    x1 = 15.2f,
                    y1 = 17.2f,
                )
                moveTo(19f, 10f)
                lineTo(19f, 11f)
                arcTo(
                    horizontalEllipseRadius = 7f,
                    verticalEllipseRadius = 7f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 18.5f,
                    y1 = 13.6f,
                )
                moveTo(12f, 18f)
                lineTo(12f, 22f)
                moveTo(8f, 22f)
                lineTo(16f, 22f)
                moveTo(2f, 2f)
                lineTo(22f, 22f)
            }
        }
    }

    val GridOn: ImageVector by lazy {
        strokeIcon("GridOn") {
            strokePath {
                moveTo(5f, 3f)
                lineTo(19f, 3f)
                quadTo(21f, 3f, 21f, 5f)
                lineTo(21f, 19f)
                quadTo(21f, 21f, 19f, 21f)
                lineTo(5f, 21f)
                quadTo(3f, 21f, 3f, 19f)
                lineTo(3f, 5f)
                quadTo(3f, 3f, 5f, 3f)
                close()
                moveTo(9f, 3f)
                lineTo(9f, 21f)
                moveTo(15f, 3f)
                lineTo(15f, 21f)
                moveTo(3f, 9f)
                lineTo(21f, 9f)
                moveTo(3f, 15f)
                lineTo(21f, 15f)
            }
        }
    }

    val OledDim: ImageVector by lazy {
        strokeIcon("OledDim") {
            strokePath {
                moveTo(12f, 3f)
                arcTo(
                    horizontalEllipseRadius = 9f,
                    verticalEllipseRadius = 9f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = false,
                    x1 = 21f,
                    y1 = 12f,
                )
                arcTo(
                    horizontalEllipseRadius = 7.28f,
                    verticalEllipseRadius = 7.28f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 12f,
                    y1 = 3f,
                )
                close()
            }
        }
    }

    val Wifi: ImageVector by lazy {
        strokeIcon("Wifi") {
            strokePath {
                moveTo(2.1f, 10.1f)
                arcTo(
                    horizontalEllipseRadius = 14f,
                    verticalEllipseRadius = 14f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 21.9f,
                    y1 = 10.1f,
                )
                moveTo(5.28f, 13.28f)
                arcTo(
                    horizontalEllipseRadius = 9.5f,
                    verticalEllipseRadius = 9.5f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 18.72f,
                    y1 = 13.28f,
                )
                moveTo(8.46f, 16.46f)
                arcTo(
                    horizontalEllipseRadius = 5f,
                    verticalEllipseRadius = 5f,
                    theta = 0f,
                    isMoreThanHalf = false,
                    isPositiveArc = true,
                    x1 = 15.54f,
                    y1 = 16.46f,
                )
            }
            strokePath(strokeWidth = 2.5f) {
                moveTo(12f, 19.5f)
                lineTo(12.01f, 19.5f)
            }
        }
    }

    val Usb: ImageVector by lazy {
        strokeIcon("Usb") {
            strokePath {
                // Main shaft and top arrow
                moveTo(12f, 17.7f)
                lineTo(12f, 3.5f)
                moveTo(9.5f, 6f)
                lineTo(12f, 3.5f)
                lineTo(14.5f, 6f)
                // Bottom connector circle centered at (12, 19.5), r = 1.8
                moveTo(12f, 17.7f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 21.3f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 17.7f)
                // Left square branch
                moveTo(12f, 15.5f)
                lineTo(7f, 12.5f)
                lineTo(7f, 9.5f)
                moveTo(5.5f, 7f)
                lineTo(8.5f, 7f)
                lineTo(8.5f, 9.5f)
                lineTo(5.5f, 9.5f)
                close()
                // Right circle branch centered at (17, 7), r = 1.5
                moveTo(12f, 13.5f)
                lineTo(17f, 10.5f)
                lineTo(17f, 8.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 5.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 8.5f)
            }
        }
    }

    val Power: ImageVector by lazy {
        strokeIcon("Power") {
            strokePath {
                moveTo(12f, 2f)
                lineTo(12f, 12f)
                moveTo(17.66f, 7.34f)
                arcTo(
                    horizontalEllipseRadius = 8f,
                    verticalEllipseRadius = 8f,
                    theta = 0f,
                    isMoreThanHalf = true,
                    isPositiveArc = true,
                    x1 = 6.34f,
                    y1 = 7.34f,
                )
            }
        }
    }

    val Copy: ImageVector by lazy {
        strokeIcon("Copy") {
            strokePath {
                moveTo(11f, 9f)
                lineTo(18f, 9f)
                quadTo(20f, 9f, 20f, 11f)
                lineTo(20f, 18f)
                quadTo(20f, 20f, 18f, 20f)
                lineTo(11f, 20f)
                quadTo(9f, 20f, 9f, 18f)
                lineTo(9f, 11f)
                quadTo(9f, 9f, 11f, 9f)
                close()
                moveTo(5f, 15f)
                quadTo(4f, 15f, 4f, 14f)
                lineTo(4f, 6f)
                quadTo(4f, 4f, 6f, 4f)
                lineTo(14f, 4f)
                quadTo(15f, 4f, 15f, 5f)
            }
        }
    }

    val Battery: ImageVector by lazy {
        strokeIcon("Battery") {
            strokePath {
                moveTo(4f, 6f)
                lineTo(17f, 6f)
                quadTo(19f, 6f, 19f, 8f)
                lineTo(19f, 16f)
                quadTo(19f, 18f, 17f, 18f)
                lineTo(4f, 18f)
                quadTo(2f, 18f, 2f, 16f)
                lineTo(2f, 8f)
                quadTo(2f, 6f, 4f, 6f)
                close()
                moveTo(22f, 10f)
                lineTo(22f, 14f)
                moveTo(6f, 10f)
                lineTo(6f, 14f)
                moveTo(10f, 10f)
                lineTo(10f, 14f)
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            strokePath {
                moveTo(4.5f, 12.5f)
                lineTo(9.5f, 17.5f)
                lineTo(19.5f, 6.5f)
            }
        }
    }

    val Download: ImageVector by lazy {
        strokeIcon("Download") {
            strokePath {
                moveTo(12f, 3f)
                lineTo(12f, 15f)
                moveTo(7.5f, 10.5f)
                lineTo(12f, 15f)
                lineTo(16.5f, 10.5f)
                moveTo(4f, 16.5f)
                lineTo(4f, 19.5f)
                quadTo(4f, 20.5f, 5f, 20.5f)
                lineTo(19f, 20.5f)
                quadTo(20f, 20.5f, 20f, 19.5f)
                lineTo(20f, 16.5f)
            }
        }
    }
}
