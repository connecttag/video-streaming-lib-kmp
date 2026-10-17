package org.connecttag.video.decoder

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Interface for platform-specific low-latency RTSP video decoders.
 * Streams decoded video frames as Compose [ImageBitmap] instances.
 */
interface RtspFrameDecoder {
    /**
     * Connects to [uri] and streams decoded video frames to [onFrame].
     * Suspends until the session is cancelled or an unrecoverable failure occurs.
     */
    suspend fun decode(uri: String, onFrame: (ImageBitmap) -> Unit)
}

/**
 * Platform factory to instantiate the optimal native RTSP decoder.
 * - Android: Media3 ExoPlayer + EGL SurfaceTexture
 * - Desktop: Embedded FFmpeg / JavaCPP + Skia
 */
expect fun createRtspFrameDecoder(): RtspFrameDecoder
