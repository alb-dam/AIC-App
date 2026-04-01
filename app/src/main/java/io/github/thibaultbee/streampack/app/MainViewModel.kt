package io.github.thibaultbee.streampack.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thibaultbee.streampack.app.data.SettingsRepository
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.app.domain.IAudioProvider
import io.github.thibaultbee.streampack.app.domain.ICameraProvider
import io.github.thibaultbee.streampack.app.domain.IStreamEngine
import io.github.thibaultbee.streampack.app.domain.IVideoEncoderEngine
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// To avoid Android SDK in ViewModel, we use a simple Pair for resolution
data class Resolution(val width: Int, val height: Int)

class MainViewModel(
    private val rotationRepository: RotationRepository,
    val settingsRepository: SettingsRepository,
    private val streamEngine: IStreamEngine,
    private val videoEncoder: IVideoEncoderEngine,
    private val audioProvider: IAudioProvider,
    private val cameraProvider: ICameraProvider
) : ViewModel() {

    companion object {
        private const val RETRY_DELAY_MS = 3000L
    }

    // ──────────────────────────────────────────────
    //  Observable state (MVI)
    // ──────────────────────────────────────────────

    private val _uiState = MutableStateFlow<StreamState>(StreamState.Idle)
    val uiState: StateFlow<StreamState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    /** Current video resolution. */
    val videoResolutionLiveData = MutableLiveData(Resolution(1920, 1080))

    /** Current FPS. */
    val videoFpsLiveData = MutableLiveData(30)

    /** Focus state: -1 = auto, 0-100 = manual (0 near, 100 infinity). */
    val focusProgressLiveData = MutableLiveData(-1)

    // ──────────────────────────────────────────────
    //  Init – observing device rotation and engine flow
    // ──────────────────────────────────────────────

    init {
        // Sync engine streaming state to MVI Live state
        viewModelScope.launch {
            combine(streamEngine.isVideoStreamingFlow, streamEngine.isAudioStreamingFlow) { video, audio ->
                video to audio
            }.collect { (video, audio) ->
                _uiState.update { currentState ->
                    // Only update to Live/Idle if we aren't in explicit connecting/retrying state 
                    // that might briefly overlap, or if we transition to Live.
                    if (video || audio) {
                        StreamState.Live(video, audio)
                    } else if (currentState is StreamState.Live) {
                        StreamState.Idle
                    } else {
                        currentState
                    }
                }
            }
        }

        // Handle async disconnects or generic errors
        viewModelScope.launch {
            merge(streamEngine.videoThrowableFlow, streamEngine.audioThrowableFlow)
                .filterNotNull()
                .collect { throwable ->
                    if (throwable.isClosedException) {
                        processIntent(StreamIntent.Disconnected(throwable))
                    } else {
                        // Generic error
                        _uiState.update { StreamState.Error(throwable, recoverable = false) }
                        processIntent(StreamIntent.Disconnected(throwable))
                    }
                }
        }

        viewModelScope.launch(Dispatchers.Default) {
            rotationRepository.rotationFlow.collect { rotation ->
                streamEngine.setTargetRotation(rotation)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Intent Processor
    // ──────────────────────────────────────────────

    fun processIntent(intent: StreamIntent) {
        when (intent) {
            is StreamIntent.StartStream -> startStreamWithFlowRetry()
            is StreamIntent.StopStream -> stopStreamAndReset()
            is StreamIntent.RetryStream -> startStreamWithFlowRetry()
            is StreamIntent.Disconnected -> {
                println("Stream disconnected: ${intent.cause.message}. Retrying...")
                startStreamWithFlowRetry()
            }
            is StreamIntent.ToggleCamera -> {
                viewModelScope.launch {
                    intent.cameraId?.let { setCameraId(it) }
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Streaming logic
    // ──────────────────────────────────────────────

    private fun startStreamWithFlowRetry() {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            _uiState.update { StreamState.Connecting }
            
            // Video Flow with retry
            val videoFlow = flow {
                streamEngine.startVideoStream(settingsRepository.srtUrl)
                emit(Unit)
            }.retryWhen { cause, attempt ->
                println("MainViewModel: Video connection failed, retrying in ${RETRY_DELAY_MS}ms…")
                _uiState.update { StreamState.Retrying(attempt.toInt() + 1, RETRY_DELAY_MS, StreamSource.VIDEO) }
                delay(RETRY_DELAY_MS)
                true // always retry
            }

            // Audio Flow with retry
            val audioFlow = flow {
                streamEngine.startAudioStream(settingsRepository.audioSrtUrl)
                emit(Unit)
            }.retryWhen { cause, attempt ->
                println("MainViewModel: Audio connection failed, retrying in ${RETRY_DELAY_MS}ms…")
                _uiState.update { StreamState.Retrying(attempt.toInt() + 1, RETRY_DELAY_MS, StreamSource.AUDIO) }
                delay(RETRY_DELAY_MS)
                true // always retry
            }

            // Launch both concurrently
            launch { videoFlow.collect() }
            launch { audioFlow.collect() }
        }
    }

    private fun stopStreamAndReset() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update { StreamState.Idle }

        viewModelScope.launch {
            try { streamEngine.stopVideoStream() } catch (e: Exception) { println("Error stopping video stream") }
            try { streamEngine.stopAudioStream() } catch (e: Exception) { println("Error stopping audio stream") }
        }
    }
    
    // Kept for backward compatibility if MainActivity still calls it directly
    fun startStreamWithRetry() = processIntent(StreamIntent.StartStream)
    fun stopStreamAndRetry() = processIntent(StreamIntent.StopStream)
    fun onStreamDisconnected() = processIntent(StreamIntent.RetryStream)

    // ──────────────────────────────────────────────
    //  Audio / Video Configuration
    // ──────────────────────────────────────────────

    suspend fun setAudioConfig() {
        audioProvider.configureAudio()
    }

    suspend fun setVideoConfig() {
        val resolution = videoResolutionLiveData.value ?: Resolution(1920, 1080)
        val fps = videoFpsLiveData.value ?: 30
        
        val mimeType = videoEncoder.getSupportedVideoMimeType(resolution.width, resolution.height, fps)
        val startBitrate = settingsRepository.videoBitrate

        videoEncoder.configureVideo(mimeType, startBitrate, resolution.width, resolution.height, fps)
    }

    // ──────────────────────────────────────────────
    //  A/V Sources
    // ──────────────────────────────────────────────

    suspend fun setAudioSource() {
        audioProvider.setAudioSource()
    }

    suspend fun setCameraId(cameraId: String) {
        cameraProvider.setCameraId(cameraId)
    }

    // ──────────────────────────────────────────────
    //  User settings
    // ──────────────────────────────────────────────

    fun setResolution(width: Int, height: Int) {
        videoResolutionLiveData.value = Resolution(width, height)
    }

    fun setFps(fps: Int) {
        videoFpsLiveData.value = fps
    }

    // ──────────────────────────────────────────────
    //  Manual / automatic focus
    // ──────────────────────────────────────────────

    suspend fun setAutoFocus() {
        val success = cameraProvider.setAutoFocus()
        if (success) {
            focusProgressLiveData.postValue(-1)
        }
    }

    suspend fun setManualFocus(progress: Float) {
        val success = cameraProvider.setManualFocus(progress)
        if (success) {
            // Keep the manual focus progress logic if needed
        }
    }
}