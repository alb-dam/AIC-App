package io.github.thibaultbee.streampack.app

sealed class StreamIntent {
    object StartStream : StreamIntent()
    object StopStream : StreamIntent()
    object RetryStream : StreamIntent()
    data class ToggleCamera(val cameraId: String? = null) : StreamIntent()
    data class Disconnected(val cause: Throwable) : StreamIntent()
}
