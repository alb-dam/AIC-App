package io.github.thibaultbee.streampack.app.data.device

import android.Manifest
import android.hardware.camera2.CameraMetadata
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.app.domain.ICameraProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

class AndroidCameraProvider(
    private val streamer: SingleStreamer
) : ICameraProvider {

    @RequiresPermission(Manifest.permission.CAMERA)
    override suspend fun setCameraId(cameraId: String) {
        streamer.setCameraId(cameraId)
    }

    override suspend fun setAutoFocus(): Boolean {
        val focus = cameraFocusOrNull() ?: return false
        when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in focus.availableAutoModes ->
                focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            CameraMetadata.CONTROL_AF_MODE_AUTO in focus.availableAutoModes ->
                focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_AUTO)
            else -> return false
        }
        return true
    }

    override suspend fun setManualFocus(progress: Float): Boolean {
        val focus = cameraFocusOrNull() ?: return false
        focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_OFF)
        val range = focus.availableLensDistanceRange
        focus.setLensDistance(range.upper * (1f - progress))
        return true
    }

    private fun cameraFocusOrNull() =
        (streamer.videoInput?.sourceFlow?.value as? ICameraSource)?.settings?.focus
}
