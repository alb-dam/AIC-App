package io.github.thibaultbee.streampack.app.domain

interface IVideoEncoderEngine {
    fun getSupportedVideoMimeType(width: Int, height: Int, fps: Int): String
    suspend fun configureVideo(mimeType: String, startBitrate: Int, width: Int, height: Int, fps: Int)
}
