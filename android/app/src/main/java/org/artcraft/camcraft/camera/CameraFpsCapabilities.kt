package org.artcraft.camcraft.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range

/**
 * Pure-Kotlin FPS range value object so FPS capability resolution is testable on the JVM
 * without depending on [android.util.Range].
 */
data class FpsRange(
    val minFps: Int,
    val maxFps: Int,
) {
    fun toAndroidRange(): Range<Int> = Range(minFps, maxFps)
}

/**
 * Resolution-specific FPS entry extracted from Qualcomm/OEM vendor characteristic tables.
 */
data class StreamFpsEntry(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
)

/**
 * Provenance of the selected [TargetFpsPlan].
 */
enum class FpsSupportSource {
    STANDARD_AE,
    VENDOR_STREAM_BASED,
    VENDOR_CUSTOM_HFR,
    MIN_FRAME_DURATION,
    FALLBACK_STANDARD,
}

/**
 * Concrete FPS configuration plan for CameraX + Camera2Interop binding.
 */
data class TargetFpsPlan(
    val range: FpsRange,
    val isStandardAeRange: Boolean,
    val useVideoSessionMode: Boolean,
    val source: FpsSupportSource,
)

/**
 * Parsed FPS metadata aggregated from standard Camera2 and vendor tables.
 */
data class CameraFpsMetadata(
    val standardAeRanges: List<FpsRange>,
    val streamBasedEntries: List<StreamFpsEntry>,
    val customHfrEntries: List<StreamFpsEntry>,
)

/**
 * Resolves true camera FPS capabilities across standard Camera2 characteristics and
 * Qualcomm CamX / Xiaomi vendor HFR tables (`StreamBasedFPSTable`, `CustomHFRFpsTable`).
 */
object CameraFpsCapabilities {

    private const val TAG = "CameraFpsCapabilities"

    const val KEY_STREAM_BASED_FPS_TABLE = "org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable"
    const val KEY_CUSTOM_HFR_FPS_TABLE = "org.quic.camera2.customhfrfps.info.CustomHFRFpsTable"
    const val KEY_XIAOMI_ALGO_HFR_FPS_TABLE = "com.xiaomi.capabilities.algoCustomHFRFpsTable"

    const val MAX_60FPS_FRAME_DURATION_NS = 17_000_000L

    /**
     * Parses `org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable` (`int32` stride-4 tuples:
     * `[width, height, minFps, maxFps]`).
     */
    fun parseStreamBasedFpsTable(raw: IntArray?): List<StreamFpsEntry> {
        if (raw == null || raw.size < 4 || raw.size % 4 != 0) return emptyList()

        val entries = ArrayList<StreamFpsEntry>(raw.size / 4)
        var i = 0
        while (i < raw.size) {
            val w = raw[i]
            val h = raw[i + 1]
            val minFps = raw[i + 2]
            val maxFps = raw[i + 3]
            if (w in 160..16384 && h in 120..16384 && minFps in 1..480 && maxFps in minFps..480) {
                entries.add(StreamFpsEntry(width = w, height = h, minFps = minFps, maxFps = maxFps))
            }
            i += 4
        }
        return entries
    }

    /**
     * Parses `CustomHFRFpsTable` / `algoCustomHFRFpsTable` (`int32` stride-3 `[w, h, fps]`
     * or stride-5 `[w, h, fps, ...]`).
     */
    fun parseCustomHfrFpsTable(raw: IntArray?): List<StreamFpsEntry> {
        if (raw == null || raw.size < 3) return emptyList()

        return when {
            raw.size % 3 == 0 -> {
                val entries = ArrayList<StreamFpsEntry>(raw.size / 3)
                var i = 0
                while (i < raw.size) {
                    val w = raw[i]
                    val h = raw[i + 1]
                    val fps = raw[i + 2]
                    if (w in 160..16384 && h in 120..16384 && fps in 24..480) {
                        entries.add(StreamFpsEntry(width = w, height = h, minFps = fps, maxFps = fps))
                    }
                    i += 3
                }
                entries
            }
            raw.size % 5 == 0 -> {
                val entries = ArrayList<StreamFpsEntry>(raw.size / 5)
                var i = 0
                while (i < raw.size) {
                    val w = raw[i]
                    val h = raw[i + 1]
                    val fps = raw[i + 2]
                    if (w in 160..16384 && h in 120..16384 && fps in 24..480) {
                        entries.add(StreamFpsEntry(width = w, height = h, minFps = fps, maxFps = fps))
                    }
                    i += 5
                }
                entries
            }
            else -> emptyList()
        }
    }

    private fun matchesSize(entry: StreamFpsEntry, width: Int, height: Int): Boolean =
        (entry.width == width && entry.height == height) ||
            (entry.width == height && entry.height == width)

    /**
     * Computes advertised maximum FPS (`60` or `30`) for a given stream resolution.
     */
    fun computeMaxFpsForSize(
        width: Int,
        height: Int,
        minFrameDurationNs: Long,
        @Suppress("UNUSED_PARAMETER") standardAeRanges: List<FpsRange>,
        streamBasedEntries: List<StreamFpsEntry>,
        customHfrEntries: List<StreamFpsEntry>,
    ): Int {
        if (streamBasedEntries.any { matchesSize(it, width, height) && it.maxFps >= 60 }) {
            return 60
        }
        if (customHfrEntries.any { matchesSize(it, width, height) && it.maxFps >= 60 }) {
            return 60
        }
        if (minFrameDurationNs in 1..MAX_60FPS_FRAME_DURATION_NS) {
            return 60
        }
        return 30
    }

    /**
     * Resolves the best [TargetFpsPlan] for `requestedFps` at `(width, height)`.
     */
    fun resolveTargetFpsPlan(
        width: Int,
        height: Int,
        requestedFps: Int,
        minFrameDurationNs: Long,
        standardAeRanges: List<FpsRange>,
        streamBasedEntries: List<StreamFpsEntry>,
        customHfrEntries: List<StreamFpsEntry>,
    ): TargetFpsPlan? {
        if (requestedFps <= 0) return null

        // 1. Exact fixed range in standard AE ranges (e.g. [60, 60] on Pixel/Samsung)
        val exactStandard = standardAeRanges.firstOrNull {
            it.minFps == requestedFps && it.maxFps == requestedFps
        }
        if (exactStandard != null) {
            return TargetFpsPlan(
                range = exactStandard,
                isStandardAeRange = true,
                useVideoSessionMode = requestedFps > 30,
                source = FpsSupportSource.STANDARD_AE,
            )
        }

        // 2. Variable range in standard AE ranges matching maxFps (prefer highest minFps)
        val variableStandard = standardAeRanges
            .filter { it.maxFps == requestedFps }
            .maxByOrNull { it.minFps }
        if (variableStandard != null) {
            return TargetFpsPlan(
                range = variableStandard,
                isStandardAeRange = true,
                useVideoSessionMode = requestedFps > 30,
                source = FpsSupportSource.STANDARD_AE,
            )
        }

        // 3. Qualcomm StreamBasedFPSTable matching resolution & requestedFps
        val matchingStreamEntries = streamBasedEntries.filter {
            matchesSize(it, width, height) && it.maxFps >= requestedFps
        }
        if (matchingStreamEntries.isNotEmpty()) {
            val exactVendor = matchingStreamEntries.firstOrNull {
                it.minFps == requestedFps && it.maxFps == requestedFps
            }
            val selectedRange = if (exactVendor != null) {
                FpsRange(requestedFps, requestedFps)
            } else {
                val exactMaxEntry = matchingStreamEntries
                    .filter { it.maxFps == requestedFps }
                    .maxByOrNull { it.minFps }
                if (exactMaxEntry != null) {
                    FpsRange(exactMaxEntry.minFps, exactMaxEntry.maxFps)
                } else {
                    FpsRange(requestedFps, requestedFps)
                }
            }
            return TargetFpsPlan(
                range = selectedRange,
                isStandardAeRange = false,
                useVideoSessionMode = true,
                source = FpsSupportSource.VENDOR_STREAM_BASED,
            )
        }

        // 4. Qualcomm / Xiaomi CustomHFRFpsTable matching resolution & requestedFps
        if (customHfrEntries.any { matchesSize(it, width, height) && it.maxFps >= requestedFps }) {
            return TargetFpsPlan(
                range = FpsRange(requestedFps, requestedFps),
                isStandardAeRange = false,
                useVideoSessionMode = true,
                source = FpsSupportSource.VENDOR_CUSTOM_HFR,
            )
        }

        // 5. Standard min frame duration supports requested FPS even if omitted from AE ranges
        val maxDurationForRequestedNs = (1_000_000_000L / requestedFps) + 500_000L
        if (minFrameDurationNs in 1..maxDurationForRequestedNs) {
            return TargetFpsPlan(
                range = FpsRange(requestedFps, requestedFps),
                isStandardAeRange = false,
                useVideoSessionMode = requestedFps > 30,
                source = FpsSupportSource.MIN_FRAME_DURATION,
            )
        }

        // 6. Safe fallback to highest standard AE range <= requestedFps (preferring fixed [30, 30] over [7, 30])
        val fallback = standardAeRanges
            .filter { it.maxFps <= requestedFps }
            .maxWithOrNull(compareBy<FpsRange> { it.maxFps }.thenBy { it.minFps })
            ?: return null

        return TargetFpsPlan(
            range = fallback,
            isStandardAeRange = true,
            useVideoSessionMode = false,
            source = FpsSupportSource.FALLBACK_STANDARD,
        )
    }

    /**
     * Extracts standard AE ranges and Qualcomm/Xiaomi HFR tables from [CameraCharacteristics].
     */
    fun extractCameraFpsMetadata(chars: CameraCharacteristics): CameraFpsMetadata {
        val standardAeRanges = try {
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { FpsRange(it.lower, it.upper) }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES: ${e.message}")
            emptyList()
        }

        val streamBasedRaw = readVendorIntArray(chars, KEY_STREAM_BASED_FPS_TABLE)
        val customHfrRaw = readVendorIntArray(chars, KEY_CUSTOM_HFR_FPS_TABLE)
        val algoHfrRaw = readVendorIntArray(chars, KEY_XIAOMI_ALGO_HFR_FPS_TABLE)

        val streamBasedEntries = parseStreamBasedFpsTable(streamBasedRaw)
        val customHfrEntries = (parseCustomHfrFpsTable(customHfrRaw) + parseCustomHfrFpsTable(algoHfrRaw))
            .distinct()

        return CameraFpsMetadata(
            standardAeRanges = standardAeRanges,
            streamBasedEntries = streamBasedEntries,
            customHfrEntries = customHfrEntries,
        )
    }

    private fun readVendorIntArray(chars: CameraCharacteristics, keyName: String): IntArray? {
        // Prefer the key advertised by the HAL; otherwise construct the vendor key by name
        // (CameraCharacteristics.Key has a public constructor).
        val advertised = try {
            chars.keys.firstOrNull { it.name == keyName }
        } catch (_: Exception) {
            null
        }
        val candidates = ArrayList<CameraCharacteristics.Key<IntArray>>(2)
        if (advertised != null) {
            @Suppress("UNCHECKED_CAST")
            candidates.add(advertised as CameraCharacteristics.Key<IntArray>)
        }
        candidates.add(CameraCharacteristics.Key(keyName, IntArray::class.java))

        for (key in candidates) {
            try {
                val value = chars.get(key)
                if (value != null) {
                    Log.i(TAG, "Vendor FPS key $keyName -> ${value.size} ints (advertised=${advertised != null})")
                    return value
                }
            } catch (e: Exception) {
                Log.d(TAG, "Vendor FPS key $keyName read failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        Log.i(TAG, "Vendor FPS key $keyName not available (advertised=${advertised != null})")
        return null
    }

    /**
     * Safely queries the minimum SurfaceTexture frame duration in nanoseconds for `(width, height)`.
     */
    fun queryMinFrameDurationNs(chars: CameraCharacteristics, width: Int, height: Int): Long {
        return try {
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 0L
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return 0L
            val sizeObj = sizes.firstOrNull { it.width == width && it.height == height } ?: return 0L
            map.getOutputMinFrameDuration(SurfaceTexture::class.java, sizeObj)
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying min frame duration for ${width}x$height: ${e.message}")
            0L
        }
    }

    /**
     * Returns advertised max FPS (`60` or `30`) for the specified resolution on [chars].
     */
    fun maxFpsForResolution(
        chars: CameraCharacteristics,
        metadata: CameraFpsMetadata,
        width: Int,
        height: Int,
    ): Int {
        val minDurationNs = queryMinFrameDurationNs(chars, width, height)
        return computeMaxFpsForSize(
            width = width,
            height = height,
            minFrameDurationNs = minDurationNs,
            standardAeRanges = metadata.standardAeRanges,
            streamBasedEntries = metadata.streamBasedEntries,
            customHfrEntries = metadata.customHfrEntries,
        )
    }

    /**
     * Resolves a [TargetFpsPlan] for `cameraId` using [CameraManager].
     */
    fun resolveForCamera(
        context: Context,
        cameraId: String,
        width: Int,
        height: Int,
        requestedFps: Int,
    ): TargetFpsPlan? {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val chars = manager.getCameraCharacteristics(cameraId)
            val metadata = extractCameraFpsMetadata(chars)
            val minDurationNs = queryMinFrameDurationNs(chars, width, height)
            resolveTargetFpsPlan(
                width = width,
                height = height,
                requestedFps = requestedFps,
                minFrameDurationNs = minDurationNs,
                standardAeRanges = metadata.standardAeRanges,
                streamBasedEntries = metadata.streamBasedEntries,
                customHfrEntries = metadata.customHfrEntries,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving target FPS plan for camera $cameraId: ${e.message}")
            null
        }
    }

    /**
     * Checks whether the camera HAL advertises support for a specific Camera2 StreamUseCase (API 33+).
     */
    fun supportsStreamUseCase(
        context: Context,
        cameraId: String,
        useCase: Long,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            val chars = manager.getCameraCharacteristics(cameraId)
            val availableUseCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
                ?: return false
            availableUseCases.any { it == useCase }
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking stream use case $useCase for camera $cameraId: ${e.message}")
            false
        }
    }
}
