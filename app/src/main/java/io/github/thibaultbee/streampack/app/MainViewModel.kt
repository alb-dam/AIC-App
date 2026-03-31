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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val isStreamingLiveData: LiveData<Boolean>
        get() = streamer.isStreamingFlow.asLiveData()

    /** Connection attempt in progress. */
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    /** Retry in progress (waiting between attempts). */
    private val _isRetryingLiveData = MutableLiveData(false)
    val isRetryingLiveData: LiveData<Boolean> = _isRetryingLiveData

    /** Active retry coroutine. */
    private var retryJob: Job? = null

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
        viewModelScope.launch(Dispatchers.Default) {
            rotationRepository.rotationFlow.collect { rotation ->
                streamer.setTargetRotation(rotation)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Streaming
    // ──────────────────────────────────────────────

    suspend fun startStream() {
        _isTryingConnectionLiveData.postValue(true)
        try {
            streamer.startStream(settingsRepository.srtUrl)
            try {
                audioStreamer.startStream(settingsRepository.audioSrtUrl)
            } catch (e: Exception) {
                streamer.stopStream()
                throw e
            }
        } finally {
            _isTryingConnectionLiveData.postValue(false)
        }
    }

    suspend fun stopStream() {
        try { streamer.stopStream() } catch (e: Exception) { Log.e(TAG, "Error stopping main stream", e) }
        try { audioStreamer.stopStream() } catch (e: Exception) { Log.e(TAG, "Error stopping audio stream", e) }
    }

    /**
     * Starts a retry loop that keeps attempting to connect
     * every [RETRY_DELAY_MS] until successful or cancelled.
     */
    fun startStreamWithRetry() {
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            while (isActive) {
                _isTryingConnectionLiveData.postValue(true)
                try {
                    streamer.startStream(settingsRepository.srtUrl)
                    try {
                        audioStreamer.startStream(settingsRepository.audioSrtUrl)
                    } catch (e: Exception) {
                        streamer.stopStream()
                        throw e
                    }
                    // Connected successfully
                    _isRetryingLiveData.postValue(false)
                    _isTryingConnectionLiveData.postValue(false)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Connection failed, retrying in ${RETRY_DELAY_MS}ms…", e)
                    _isTryingConnectionLiveData.postValue(false)
                    _isRetryingLiveData.postValue(true)
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    /** Stops streaming and cancels any pending retry. */
    fun stopStreamAndRetry() {
        retryJob?.cancel()
        retryJob = null
        _isRetryingLiveData.postValue(false)
        viewModelScope.launch {
            stopStream()
        }
    }

    /** Called when the stream disconnects mid-session; re-enters the retry loop. */
    fun onStreamDisconnected() {
        if (retryJob?.isActive == true) return
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