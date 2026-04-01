package io.github.thibaultbee.streampack.app.data.device

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import io.github.thibaultbee.streampack.app.domain.IVideoEncoderEngine
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.app.ApplicationConstants

class AndroidVideoEncoder(
    private val streamer: SingleStreamer
) : IVideoEncoderEngine {

    override fun getSupportedVideoMimeType(width: Int, height: Int, fps: Int): String {
        val hevcMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val avcMime = MediaFormat.MIMETYPE_VIDEO_AVC
        val formatSurface = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        
        for (info in mcl.codecInfos) {
            if (!info.isEncoder) continue
            try {
                val caps = info.getCapabilitiesForType(hevcMime)
                val supportsSurface = caps.colorFormats.contains(formatSurface)
                val supportsResolution = caps.videoCapabilities?.isSizeSupported(width, height) == true
                if (supportsSurface && supportsResolution) {
                    return hevcMime
                }
            } catch (ignored: IllegalArgumentException) {
            }
        }
        return avcMime
    }

    override suspend fun configureVideo(mimeType: String, startBitrate: Int, width: Int, height: Int, fps: Int) {
        streamer.setVideoConfig(
            VideoConfig(
                mimeType = mimeType,
                startBitrate = startBitrate,
                resolution = Size(width, height),
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
}
