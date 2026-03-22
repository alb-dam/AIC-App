package io.github.thibaultbee.streampack.app

import android.content.pm.ActivityInfo
import android.util.Size

/**
 * Application configuration constants.
 */
object ApplicationConstants {
    /**
     * Default application orientation.
     * Also set in `AndroidManifest.xml` `android:screenOrientation` attribute.
     */
    const val supportedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

    // ── SRT ──
    const val DEFAULT_SRT_URL = "srt://100.117.124.124:9999" +
        "?mode=caller&transtype=live" +
        "&latency=3000&peerlatency=3000" +
        "&rcvbuf=16777216&sndbuf=16777216" +
        "&pkt_size=1316" +
        "&tlpktdrop=0" 
    
    // ── Audio SRT ──
    const val DEFAULT_AUDIO_SRT_URL = "srt://100.117.124.124:9998" +
        "?mode=caller&transtype=live" +
        "&latency=3000&peerlatency=3000" +
        "&rcvbuf=16777216&sndbuf=16777216" +
        "&pkt_size=1316" +
        "&tlpktdrop=0" 
    //  "&streamid=f8cbf735-685d-47fe-9118-c1ae692c3f2f.stream,mode:publish"
    // ── Video ──
    const val DEFAULT_VIDEO_BITRATE      = 2_000_000   // 3 Mbps CBR
    const val VIDEO_GOP_DURATION  = 2f          // 1 s between I-frames
    val DEFAULT_RESOLUTION        = Size(1920, 1080)
    const val DEFAULT_FPS         = 30

    // ── Audio ──
    const val AUDIO_SAMPLE_RATE   = 44_100
}

/**
 * Available resolution presets for streaming.
 */
enum class ResolutionOption(val label: String, val size: Size) {
    RES_8K("8K", Size(7680, 4320)),
    RES_UHD("UHD", Size(3840, 2160)),
    RES_FHD("FHD", Size(1920, 1080)),
    RES_HD("HD", Size(1280, 720));

    companion object {
        fun fromSize(size: Size): ResolutionOption? =
            entries.find { it.size == size }

        fun labelForSize(size: Size): String =
            fromSize(size)?.label ?: "CST"
    }
}