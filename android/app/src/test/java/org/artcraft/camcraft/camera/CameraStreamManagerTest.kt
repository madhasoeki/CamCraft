package org.artcraft.camcraft.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamManagerTest {

    @Test
    fun needsEncoderRestartCoversCameraSwitchGeometryFpsAndMissingEncoder() {
        // Switching camera at identical resolution and FPS must restart encoder for fresh VIDEO_CONFIG + IDR.
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = true,
                geometryOrFpsChanged = false,
                hasEncoder = true,
            ),
        )
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = true,
                hasEncoder = true,
            ),
        )
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = false,
                hasEncoder = false,
            ),
        )
        assertFalse(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = false,
                hasEncoder = true,
            ),
        )
    }

    @Test
    fun evaluateRebindDecisionUsesDynamicBitrateOnlyWhenCameraAndGeometryUnchanged() {
        val bitrateOnly = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = false,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = true,
        )
        assertFalse(bitrateOnly.needsEncoderRestart)
        assertTrue(bitrateOnly.needsBitrateUpdate)

        val cameraAndBitrate = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = true,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = true,
        )
        assertTrue(cameraAndBitrate.needsEncoderRestart)
        assertFalse(cameraAndBitrate.needsBitrateUpdate)
    }

    @Test
    fun previewAttachAndDetachTransitionsAreIdempotentAndPreserveEncoder() {
        // Not streaming / released -> NO_OP
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = false,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = false,
            ),
        )
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = true,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = true,
            ),
        )

        // Same view already attached and uiPreview bound -> deduplicated NO_OP
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = true,
                uiPreviewBound = true,
                encoderPreviewBound = true,
            ),
        )

        // Attaching when encoderPreview is healthy binds ONLY uiPreview
        assertEquals(
            CameraStreamManager.PreviewSyncAction.REBIND_UI_ONLY,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = true,
            ),
        )

        // Attaching before encoderPreview is bound performs FULL_REBIND
        assertEquals(
            CameraStreamManager.PreviewSyncAction.FULL_REBIND,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = false,
            ),
        )

        // Detach when already detached -> NO_OP (prevents double-detach on backgrounding)
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideDetachPreviewAction(
                isStreaming = true,
                isReleased = false,
                hasAttachedOrUiPreview = false,
            ),
        )

        // Detach while attached & streaming -> UNBIND_UI_ONLY (encoderPreview stays bound)
        assertEquals(
            CameraStreamManager.PreviewSyncAction.UNBIND_UI_ONLY,
            CameraStreamManager.decideDetachPreviewAction(
                isStreaming = true,
                isReleased = false,
                hasAttachedOrUiPreview = true,
            ),
        )
    }
}
