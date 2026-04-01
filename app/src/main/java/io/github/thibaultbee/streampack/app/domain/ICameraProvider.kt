package io.github.thibaultbee.streampack.app.domain

interface ICameraProvider {
    suspend fun setCameraId(cameraId: String)
    suspend fun setAutoFocus(): Boolean
    suspend fun setManualFocus(progress: Float): Boolean
}
