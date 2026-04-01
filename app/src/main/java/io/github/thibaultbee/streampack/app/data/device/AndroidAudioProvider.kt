package io.github.thibaultbee.streampack.app.data.device

import android.Manifest
import android.media.AudioFormat
import android.media.MediaFormat
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.app.ApplicationConstants
import io.github.thibaultbee.streampack.app.domain.IAudioProvider
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer

class AndroidAudioProvider(
    private val streamer: SingleStreamer,
    private val audioStreamer: SingleStreamer
) : IAudioProvider {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configureAudio() {
        val config = AudioConfig(
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate = ApplicationConstants.AUDIO_SAMPLE_RATE,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
        )
        streamer.setAudioConfig(config)
        audioStreamer.setAudioConfig(config)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun setAudioSource() {
        streamer.setAudioSource(MicrophoneSourceFactory())
        audioStreamer.setAudioSource(MicrophoneSourceFactory())
    }
}
