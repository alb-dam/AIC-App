package io.github.thibaultbee.streampack.app.domain

interface IAudioProvider {
    suspend fun configureAudio()
    suspend fun setAudioSource()
}
