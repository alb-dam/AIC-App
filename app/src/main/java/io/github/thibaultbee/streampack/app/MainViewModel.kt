package io.github.thibaultbee.streampack.app

import android.Manifest
import android.hardware.camera2.CameraMetadata
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresPermission
import android.media.MediaCodecList
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import io.github.thibaultbee.streampack.app.data.SettingsRepository
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import kotlinx.coroutines.flow.merge

class MainViewModel(
    private val rotationRepository: RotationRepository,
    val settingsRepository: SettingsRepository,
    val streamer: SingleStreamer,
    val audioStreamer: SingleStreamer
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val RETRY_DELAY_MS = 3000L
    }

    // ──────────────────────────────────────────────
    //  Observable state
    // ──────────────────────────────────────────────

    /** Streaming state (active / stopped). */
    /** Streaming state (active / stopped) - combining both. */
    private val _isStreamingLiveData = MutableLiveData(false)
    val isStreamingLiveData: LiveData<Boolean> = _isStreamingLiveData

    private var isVideoStreaming = false
    private var isAudioStreaming = false

    /** Connection attempt in progress. */
    private val _isTryingConnectionLiveData = MutableLiveData(false)
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    /** Retry in progress (waiting between attempts). */
    private val _isRetryingLiveData = MutableLiveData(false)
    val isRetryingLiveData: LiveData<Boolean> = _isRetryingLiveData

    private var isVideoTrying = false
    private var isAudioTrying = false
    private var isVideoRetrying = false
    private var isAudioRetrying = false

    private fun updateTryingState() {
        _isTryingConnectionLiveData.postValue(isVideoTrying || isAudioTrying)
    }

    private fun updateRetryingState() {
        _isRetryingLiveData.postValue(isVideoRetrying || isAudioRetrying)
    }

    /** Active retry coroutines. */
    private var videoRetryJob: Job? = null
    private var audioRetryJob: Job? = null

    /** Async disconnection errors. */
    val closedThrowableLiveData: LiveData<Throwable> =
        merge(streamer.throwableFlow, audioStreamer.throwableFlow)
            .filterNotNull()
            .filter { it.isClosedException }
            .asLiveData()

    /** Generic streamer errors. */
    val throwableLiveData: LiveData<Throwable> =
        merge(streamer.throwableFlow, audioStreamer.throwableFlow)
            .filterNotNull()
            .filter { !it.isClosedException }
            .asLiveData()

    /** Current video resolution. */
    val videoResolutionLiveData = MutableLiveData(ApplicationConstants.DEFAULT_RESOLUTION)

    /** Current FPS. */
    val videoFpsLiveData = MutableLiveData(ApplicationConstants.DEFAULT_FPS)

    /** Focus state: -1 = auto, 0-100 = manual (0 near, 100 infinity). */
    val focusProgressLiveData = MutableLiveData(-1)

    // ──────────────────────────────────────────────
    //  Init – device rotation
    // ──────────────────────────────────────────────

    init {
        viewModelScope.launch {
            streamer.isStreamingFlow.collect {
                isVideoStreaming = it
                _isStreamingLiveData.postValue(isVideoStreaming || isAudioStreaming)
            }
        }
        viewModelScope.launch {
            audioStreamer.isStreamingFlow.collect {
                isAudioStreaming = it
                _isStreamingLiveData.postValue(isVideoStreaming || isAudioStreaming)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            rotationRepository.rotationFlow.collect { rotation ->
                streamer.setTargetRotation(rotation)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Streaming
    // ──────────────────────────────────────────────

    suspend fun startStream() = coroutineScope {
        isVideoTrying = true
        isAudioTrying = true
        updateTryingState()
        
        val videoJob = launch {
            try { streamer.startStream(settingsRepository.srtUrl) }
            catch (e: Exception) { Log.e(TAG, "Video start failed", e) }
        }
        val audioJob = launch {
            try { audioStreamer.startStream(settingsRepository.audioSrtUrl) }
            catch (e: Exception) { Log.e(TAG, "Audio start failed", e) }
        }
        
        videoJob.join()
        audioJob.join()
        
        isVideoTrying = false
        isAudioTrying = false
        updateTryingState()
    }

    suspend fun stopStream() {
        try { streamer.stopStream() } catch (e: Exception) { Log.e(TAG, "Error stopping main stream", e) }
        try { audioStreamer.stopStream() } catch (e: Exception) { Log.e(TAG, "Error stopping audio stream", e) }
    }

    /**
     * Starts retry loops that keep attempting to connect
     * every [RETRY_DELAY_MS] until successful or cancelled.
     */
    fun startStreamWithRetry() {
        startVideoStreamWithRetry()
        startAudioStreamWithRetry()
    }

    private fun startVideoStreamWithRetry() {
        videoRetryJob?.cancel()
        videoRetryJob = viewModelScope.launch {
            while (isActive) {
                // Return if already streaming successfully
                if (isVideoStreaming) {
                    isVideoRetrying = false
                    isVideoTrying = false
                    updateTryingState()
                    updateRetryingState()
                    break
                }

                isVideoTrying = true
                updateTryingState()
                try {
                    streamer.startStream(settingsRepository.srtUrl)
                    // Connected successfully
                    isVideoRetrying = false
                    isVideoTrying = false
                    updateTryingState()
                    updateRetryingState()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Video connection failed, retrying in ${RETRY_DELAY_MS}ms…", e)
                    isVideoTrying = false
                    isVideoRetrying = true
                    updateTryingState()
                    updateRetryingState()
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private fun startAudioStreamWithRetry() {
        audioRetryJob?.cancel()
        audioRetryJob = viewModelScope.launch {
            while (isActive) {
                // Return if already streaming successfully
                if (isAudioStreaming) {
                    isAudioRetrying = false
                    isAudioTrying = false
                    updateTryingState()
                    updateRetryingState()
                    break
                }

                isAudioTrying = true
                updateTryingState()
                try {
                    audioStreamer.startStream(settingsRepository.audioSrtUrl)
                    // Connected successfully
                    isAudioRetrying = false
                    isAudioTrying = false
                    updateTryingState()
                    updateRetryingState()
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Audio connection failed, retrying in ${RETRY_DELAY_MS}ms…", e)
                    isAudioTrying = false
                    isAudioRetrying = true
                    updateTryingState()
                    updateRetryingState()
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    /** Stops streaming and cancels any pending retry. */
    fun stopStreamAndRetry() {
        videoRetryJob?.cancel()
        audioRetryJob?.cancel()
        videoRetryJob = null
        audioRetryJob = null
        
        isVideoRetrying = false
        isAudioRetrying = false
        updateRetryingState()
        
        isVideoTrying = false
        isAudioTrying = false
        updateTryingState()

        viewModelScope.launch {
            stopStream()
        }
    }

    /** Called when the stream disconnects mid-session; re-enters the retry loop. */
    fun onStreamDisconnected() {
        startStreamWithRetry()
    }

    // ──────────────────────────────────────────────
    //  Audio / Video Configuration
    // ──────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun setAudioConfig() {
        val config = AudioConfig(
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate = ApplicationConstants.AUDIO_SAMPLE_RATE,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
        )
        streamer.setAudioConfig(config)
        audioStreamer.setAudioConfig(config)
    }

    suspend fun setVideoConfig() {
        val resolution = videoResolutionLiveData.value ?: ApplicationConstants.DEFAULT_RESOLUTION
        val fps = videoFpsLiveData.value ?: ApplicationConstants.DEFAULT_FPS
        val mimeType = getSupportedVideoMimeType(resolution, fps)

        streamer.setVideoConfig(
            VideoConfig(
                mimeType = mimeType,
                startBitrate = settingsRepository.videoBitrate,
                resolution = resolution,
                fps = fps,
                gopDurationInS = ApplicationConstants.VIDEO_GOP_DURATION
            ) { _ ->
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
        )
    }

    /**
     * Queries available encoders at runtime to find if HEVC is fully supported for surface encoding.
     * Some emulators do not support COLOR_FormatSurface with HEVC, so we fallback to AVC.
     */
    private fun getSupportedVideoMimeType(resolution: Size, fps: Int): String {
        val hevcMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val avcMime = MediaFormat.MIMETYPE_VIDEO_AVC
        val formatSurface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        
        for (info in mcl.codecInfos) {
            if (!info.isEncoder) continue
            try {
                val caps = info.getCapabilitiesForType(hevcMime)
                // Check if COLOR_FormatSurface is supported.
                val supportsSurface = caps.colorFormats.contains(formatSurface)
                val supportsResolution = caps.videoCapabilities?.isSizeSupported(resolution.width, resolution.height) == true
                if (supportsSurface && supportsResolution) {
                    return hevcMime
                }
            } catch (ignored: IllegalArgumentException) {
                // Codec does not support HEVC
            }
        }
        return avcMime
    }

    // ──────────────────────────────────────────────
    //  A/V Sources
    // ──────────────────────────────────────────────

    suspend fun setAudioSource() {
        streamer.setAudioSource(MicrophoneSourceFactory())
        audioStreamer.setAudioSource(MicrophoneSourceFactory())
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun setCameraId(cameraId: String) {
        streamer.setCameraId(cameraId)
    }

    // ──────────────────────────────────────────────
    //  User settings (resolution / fps)
    // ──────────────────────────────────────────────

    fun setResolution(size: Size) {
        videoResolutionLiveData.value = size
    }

    fun setFps(fps: Int) {
        videoFpsLiveData.value = fps
    }

    // ──────────────────────────────────────────────
    //  Manual / automatic focus
    // ──────────────────────────────────────────────

    suspend fun setAutoFocus() {
        val focus = cameraFocusOrNull() ?: return
        when {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in focus.availableAutoModes ->
                focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            CameraMetadata.CONTROL_AF_MODE_AUTO in focus.availableAutoModes ->
                focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_AUTO)
        }
        focusProgressLiveData.postValue(-1)
    }

    /**
     * Sets a manual focus distance.
     * @param progress 0.0 (nearest) → 1.0 (infinity)
     */
    suspend fun setManualFocus(progress: Float) {
        val focus = cameraFocusOrNull() ?: return
        focus.setAutoMode(CameraMetadata.CONTROL_AF_MODE_OFF)
        val range = focus.availableLensDistanceRange
        focus.setLensDistance(range.upper * (1f - progress))
    }

    // ──────────────────────────────────────────────
    //  Private helpers
    // ──────────────────────────────────────────────

    /** Returns the focus controller of the current camera, or null. */
    private fun cameraFocusOrNull() =
        (streamer.videoInput?.sourceFlow?.value as? ICameraSource)?.settings?.focus
}