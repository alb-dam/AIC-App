package io.github.thibaultbee.streampack.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.thibaultbee.streampack.app.data.SettingsRepository
import io.github.thibaultbee.streampack.app.data.device.AndroidAudioProvider
import io.github.thibaultbee.streampack.app.data.device.AndroidCameraProvider
import io.github.thibaultbee.streampack.app.data.device.AndroidStreamEngine
import io.github.thibaultbee.streampack.app.data.device.AndroidVideoEncoder
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

/**
 * Factory for constructing [MainViewModel] from the [Application].
 */
class MainViewModelFactory(
    private val application: Application,
    private val streamer: SingleStreamer,
    private val audioStreamer: SingleStreamer
) : ViewModelProvider.Factory {
    private val rotationRepository = RotationRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val streamEngine = AndroidStreamEngine(streamer, audioStreamer)
            val videoEncoder = AndroidVideoEncoder(streamer)
            val audioProvider = AndroidAudioProvider(streamer, audioStreamer)
            val cameraProvider = AndroidCameraProvider(streamer)

            return MainViewModel(
                rotationRepository,
                settingsRepository,
                streamEngine,
                videoEncoder,
                audioProvider,
                cameraProvider
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}