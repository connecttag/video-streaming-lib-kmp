package org.connecttag.video.model

import androidx.compose.ui.graphics.ImageBitmap

/** Supported low-latency video streaming transport protocols. */
enum class VideoProtocol { MJPEG, UDP, RTSP }

/** Lifecycle phases of a video playback session. */
enum class VideoPlaybackPhase { IDLE, CONNECTING, PLAYING, RETRYING, FAILED }

/** Classifications of video streaming and decoding failures. */
enum class VideoFailure {
    INVALID_SOURCE,
    CREDENTIAL_STORAGE,
    NETWORK,
    AUTHENTICATION,
    DECODER_UNAVAILABLE,
    UNSUPPORTED_CODEC,
    DECODE,
    FRAME_TIMEOUT
}

/** Exception thrown during video decoding or connection setup. */
class VideoDecodeException(val failure: VideoFailure, message: String? = failure.name, cause: Throwable? = null) :
    Exception(message, cause)

/** Policy limits and timeout configurations for video playback. */
object VideoPlaybackPolicy {
    const val FRAME_STALE_MS = 1_500L
    const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    const val READ_TIMEOUT_MS = 4_000L
    const val RECONNECT_AFTER_MS = 4_000L
    const val FRAME_INTERVAL_MS = 33L
    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080

    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) throw VideoDecodeException(VideoFailure.DECODE)
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    fun retryDelay(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 3)).coerceAtMost(5_000L)

    fun retryable(failure: VideoFailure): Boolean =
        failure in setOf(VideoFailure.NETWORK, VideoFailure.DECODE, VideoFailure.FRAME_TIMEOUT)

    fun authenticationRejected(detail: String?): Boolean = detail?.trim()?.let {
        Regex("(?i)^(OPTIONS|DESCRIBE|SETUP|PLAY|PAUSE|TEARDOWN|GET_PARAMETER|SET_PARAMETER)\\s+(401|403)$").matches(it) ||
            it.contains("401 Unauthorized", ignoreCase = true) || it.contains("403 Forbidden", ignoreCase = true)
    } == true
}

/**
 * Validated video stream source URI.
 * Never includes raw credentials in toString() to protect secrets.
 */
class VideoSource private constructor(val protocol: VideoProtocol, val uri: String) {
    override fun toString(): String = protocol.name
    override fun equals(other: Any?): Boolean = other is VideoSource && protocol == other.protocol && uri == other.uri
    override fun hashCode(): Int = 31 * protocol.hashCode() + uri.hashCode()

    companion object {
        fun parse(uri: String): Result<VideoSource> = try {
            val text = uri.trim()
            if (!text.contains("://")) throw VideoDecodeException(VideoFailure.INVALID_SOURCE)
            val scheme = text.substringBefore("://").lowercase()
            val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
            val protocol = when (scheme) {
                "http", "https" -> VideoProtocol.MJPEG
                "udp" -> VideoProtocol.UDP
                "rtsp" -> VideoProtocol.RTSP
                else -> throw VideoDecodeException(VideoFailure.INVALID_SOURCE)
            }
            val host = authority.substringBefore(':')
            val portPart = if (authority.contains(':')) authority.substringAfter(':') else null
            val port = portPart?.toIntOrNull()

            if (authority.isBlank() || host.isBlank() || host.any { it.isWhitespace() } ||
                authority.endsWith(':') || (portPart != null && port == null) ||
                text.any { it.isISOControl() || it.isWhitespace() } ||
                (protocol == VideoProtocol.UDP && (port == null || port !in 1..65535))) {
                throw VideoDecodeException(VideoFailure.INVALID_SOURCE)
            }
            Result.success(VideoSource(protocol, text))
        } catch (_: Exception) {
            Result.failure(VideoDecodeException(VideoFailure.INVALID_SOURCE))
        }

        fun configured(protocol: String, udpPort: Int, mjpeg: String, rtsp: String): Result<VideoSource> {
            val expected = VideoProtocol.entries.firstOrNull { it.name == protocol }
                ?: return Result.failure(VideoDecodeException(VideoFailure.INVALID_SOURCE))
            return parse(when (expected) {
                VideoProtocol.UDP -> "udp://0.0.0.0:$udpPort"
                VideoProtocol.MJPEG -> mjpeg
                VideoProtocol.RTSP -> rtsp
            }).mapCatching { if (it.protocol != expected) throw VideoDecodeException(VideoFailure.INVALID_SOURCE) else it }
        }
    }
}

/** Represents a decoded frame or playback state snapshot. */
data class VideoPlaybackState(
    val generation: Long = 0,
    val decoderAttempt: Long = 0,
    val protocol: VideoProtocol? = null,
    val phase: VideoPlaybackPhase = VideoPlaybackPhase.IDLE,
    val failure: VideoFailure? = null,
    val frame: ImageBitmap? = null,
    val lastFrameAtMs: Long? = null
)
