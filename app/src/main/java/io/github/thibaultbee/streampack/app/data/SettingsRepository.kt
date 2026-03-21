package io.github.thibaultbee.streampack.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.thibaultbee.streampack.app.ApplicationConstants

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("streampack_settings", Context.MODE_PRIVATE)

    var srtUrl: String
        get() = prefs.getString(KEY_SRT_URL, ApplicationConstants.DEFAULT_SRT_URL) ?: ApplicationConstants.DEFAULT_SRT_URL
        set(value) = prefs.edit { putString(KEY_SRT_URL, value) }

    var videoBitrate: Int
        get() = prefs.getInt(KEY_VIDEO_BITRATE, ApplicationConstants.DEFAULT_VIDEO_BITRATE)
        set(value) = prefs.edit { putInt(KEY_VIDEO_BITRATE, value) }

    companion object {
        private const val KEY_SRT_URL = "srt_url"
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
