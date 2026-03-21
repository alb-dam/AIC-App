package io.github.thibaultbee.streampack.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.thibaultbee.streampack.app.data.SettingsRepository
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

/**
 * Factory for constructing [MainViewModel] from the [Application].
 */
class MainViewModelFactory(private val application: Application) :
    ViewModelProvider.Factory {
    private val rotationRepository = RotationRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val streamer = createStreamer(application)
            return MainViewModel(rotationRepository, settingsRepository, streamer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }

    companion object {
        private fun createStreamer(application: Application): SingleStreamer {
            return SingleStreamer(
                application, withAudio = true, withVideo = true
            )
        }
    }
}