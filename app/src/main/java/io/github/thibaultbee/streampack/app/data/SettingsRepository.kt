package io.github.thibaultbee.streampack.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.thibaultbee.streampack.app.ApplicationConstants

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("streampack_settings", Context.MODE_PRIVATE)

    var srtIp: String
        get() = prefs.getString(KEY_SRT_IP, ApplicationConstants.DEFAULT_SRT_IP) ?: ApplicationConstants.DEFAULT_SRT_IP
        set(value) = prefs.edit { putString(KEY_SRT_IP, value) }

    var srtPort: Int
        get() = prefs.getInt(KEY_SRT_PORT, ApplicationConstants.DEFAULT_SRT_PORT)
        set(value) = prefs.edit { putInt(KEY_SRT_PORT, value) }

    var audioSrtPort: Int
        get() = prefs.getInt(KEY_AUDIO_SRT_PORT, ApplicationConstants.DEFAULT_AUDIO_SRT_PORT)
        set(value) = prefs.edit { putInt(KEY_AUDIO_SRT_PORT, value) }

    val srtUrl: String
        get() = "srt://$srtIp:$srtPort${ApplicationConstants.SRT_PARAMS}"

    val audioSrtUrl: String
        get() = "srt://$srtIp:$audioSrtPort${ApplicationConstants.SRT_PARAMS}"

    var videoBitrate: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, ApplicationConstants.DEFAULT_VIDEO_BITRATE)
        set(value) = prefs.edit { putInt(KEY_VIDEO_BITRATE, value) }

    companion object {
        private const val KEY_SRT_IP = "srt_ip"
        private const val KEY_SRT_PORT = "srt_port"
        private const val KEY_AUDIO_SRT_PORT = "audio_srt_port"
        private const val KEY_VIDEO_BITRATE = "video_bitrate"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE?.let { return it }
                SettingsRepository(context).apply { INSTANCE = this }
            }
        }
    }
}
