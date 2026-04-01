package io.github.thibaultbee.streampack.app.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

interface IStreamEngine {
    val isVideoStreamingFlow: Flow<Boolean>
    val isAudioStreamingFlow: Flow<Boolean>
    val videoThrowableFlow: Flow<Throwable?>
    val audioThrowableFlow: Flow<Throwable?>

    suspend fun startVideoStream(url: String)
    suspend fun startAudioStream(url: String)
    suspend fun stopVideoStream()
    suspend fun stopAudioStream()
    
    suspend fun setTargetRotation(rotation: Int)
}
