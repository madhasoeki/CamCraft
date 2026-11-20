package org.artcraft.camcraft.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import org.artcraft.camcraft.protocol.CameraInfo
import org.artcraft.camcraft.protocol.ResolutionOption

/**
 * Enumerates hardware camera sensors, supported output resolutions, zoom factors,
 * EV ranges, and flash availability using Android's Camera2 CameraCharacteristics.
 */
object CameraEnumerator {

    private const val TAG = "CameraEnumerator"

    /**
     * Scans all physical and logical camera IDs on the device.
     * Falls back to standard default cameras when context or camera service is unavailable (e.g. Unit Tests).
     */
    fun enumerateCameras(context: Context?): List<CameraInfo> {
        if (context == null) return fallbackCameras()

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return fallbackCameras()

            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) return fallbackCameras()

            val result = mutableListOf<CameraInfo>()
            var backCameraIndex = 0
            var frontCameraIndex = 0

            for (id in cameraIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facingInt = chars.get(CameraCharacteristics.LENS_FACING)

                    val facing = when (facingInt) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                        else -> "unknown"
                    }

                    val label = when (facing) {
                        "back" -> {
                            backCameraIndex++
                            if (backCameraIndex == 1) "Rear Main Camera"
                            else if (backCameraIndex == 2) "Rear Ultra-Wide Camera ($id)"
                            else "Rear Camera ($id)"
                        }
                        "front" -> {
                            frontCameraIndex++
                            if (frontCameraIndex == 1) "Front Selfie Camera"
                            else "Front Camera ($id)"
                        }
                        else -> "External Camera ($id)"
                    }

                    // Resolutions supported for SurfaceTexture / MediaCodec
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                    val fpsMetadata = CameraFpsCapabilities.extractCameraFpsMetadata(chars)

                    val targetResolutions = listOf(
                        Pair(3840, 2160),
                        Pair(1920, 1080),
                        Pair(1280, 720),
                    )

                    val resolutionOptions = mutableListOf<ResolutionOption>()
                    val availableSizes = sizes.map { Pair(it.width, it.height) }.toSet()

                    for ((w, h) in targetResolutions) {
                        if (availableSizes.contains(Pair(w, h))) {
                            val maxFps = CameraFpsCapabilities.maxFpsForResolution(
                                chars = chars,
                                metadata = fpsMetadata,
                                width = w,
                                height = h,
                            )
                            resolutionOptions.add(ResolutionOption(width = w, height = h, maxFps = maxFps))
                        }
                    }

                    // If standard resolutions were not in targetResolutions, pick up to 3 highest available
                    if (resolutionOptions.isEmpty()) {
                        val sortedSizes = sizes.sortedByDescending { it.width * it.height }.take(3)
                        for (size in sortedSizes) {
                            val maxFps = CameraFpsCapabilities.maxFpsForResolution(
                                chars = chars,
                                metadata = fpsMetadata,
                                width = size.width,
                                height = size.height,
                            )
                            resolutionOptions.add(
                                ResolutionOption(
                                    width = size.width,
                                    height = size.height,
                                    maxFps = maxFps,
                                )
                            )
                        }
                    }

                    val finalResolutions = if (resolutionOptions.isNotEmpty()) {
                        resolutionOptions
                    } else {
                        listOf(
                            ResolutionOption(1920, 1080, 60),
                            ResolutionOption(1280, 720, 60),
                        )
                    }

                    Log.i(
                        TAG,
                        "Camera $id ($facing): ${finalResolutions.joinToString { "${it.width}x${it.height}@${it.maxFps}fps" }}",
                    )

                    // Max zoom ratio
                    val maxZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper
                            ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                            ?: 1.0f
                    } else {
                        chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                    }

                    // EV compensation range
                    val aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.5f
                    val minEv = (aeRange?.lower ?: 0) * aeStep
                    val maxEv = (aeRange?.upper ?: 0) * aeStep

                    // Flash availability
                    val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                    result.add(
                        CameraInfo.create(
                            id = id,
                            facing = facing,
                            label = label,
                            resolutions = finalResolutions,
                            maxZoom = maxZoom.coerceAtLeast(1.0f),
                            minEv = minEv,
                            maxEv = maxEv,
                            hasFlash = hasFlash,
                        ),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query characteristics for camera $id: ${e.message}")
                }
            }

            if (result.isNotEmpty()) result else fallbackCameras()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate cameras: ${e.message}")
            fallbackCameras()
        }
    }

    /**
     * Standard fallback cameras used in automated testing and mock environments.
     */
    fun fallbackCameras(): List<CameraInfo> = listOf(
        CameraInfo(
            id = "0",
            facing = "back",
            label = "Rear Main Camera",
            resolutions = listOf(
                ResolutionOption(1280, 720, 60),
                ResolutionOption(1920, 1080, 60),
                ResolutionOption(3840, 2160, 60),
            ),
            maxZoom = 8.0f,
            evRange = listOf(-4.0f, 4.0f),
            hasFlash = true,
        ),
        CameraInfo(
            id = "1",
            facing = "front",
            label = "Front Selfie Camera",
            resolutions = listOf(
                ResolutionOption(1280, 720, 60),
                ResolutionOption(1920, 1080, 60),
            ),
            maxZoom = 2.0f,
            evRange = listOf(-2.0f, 2.0f),
            hasFlash = false,
        ),
    )
}
