package io.github.thibaultbee.streampack.app

enum class StreamSource {
    VIDEO, AUDIO, BOTH
}

sealed class StreamState {
    object Idle : StreamState()
    object Connecting : StreamState()
    
    /**
     * @param videoActive True if video stream is active.
     * @param audioActive True if audio stream is active.
     */
    data class Live(val videoActive: Boolean = false, val audioActive: Boolean = false) : StreamState()
    
    /**
     * @param attempt The current retry attempt.
     * @param delayMs The delay before the next retry.
     * @param source Which source is retrying.
     */
    data class Retrying(val attempt: Int, val delayMs: Long, val source: StreamSource) : StreamState()
    
    /**
     * @param cause The throwable exception that caused the error.
     * @param recoverable Whether the stream can recover from this error.
     */
    data class Error(val cause: Throwable, val recoverable: Boolean) : StreamState()
}
