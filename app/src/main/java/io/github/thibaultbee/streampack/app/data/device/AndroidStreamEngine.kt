package io.github.thibaultbee.streampack.app.data.device

import io.github.thibaultbee.streampack.app.domain.IStreamEngine
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import kotlinx.coroutines.flow.Flow

class AndroidStreamEngine(
    private val streamer: SingleStreamer,
    private val audioStreamer: SingleStreamer
) : IStreamEngine {

    override val isVideoStreamingFlow: Flow<Boolean>
        get() = streamer.isStreamingFlow

    override val isAudioStreamingFlow: Flow<Boolean>
        get() = audioStreamer.isStreamingFlow

    override val videoThrowableFlow: Flow<Throwable?>
        get() = streamer.throwableFlow

    override val audioThrowableFlow: Flow<Throwable?>
        get() = audioStreamer.throwableFlow

    override suspend fun startVideoStream(url: String) {
        streamer.startStream(url)
    }

    override suspend fun startAudioStream(url: String) {
        audioStreamer.startStream(url)
    }

    override suspend fun stopVideoStream() {
        streamer.stopStream()
    }

    override suspend fun stopAudioStream() {
        audioStreamer.stopStream()
    }

    override suspend fun setTargetRotation(rotation: Int) {
        streamer.setTargetRotation(rotation)
    }
}
