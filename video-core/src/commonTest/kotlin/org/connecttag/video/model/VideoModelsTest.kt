package org.connecttag.video.model

import kotlin.test.*

class VideoModelsTest {

    @Test
    fun parseValidRtspSource() {
        val result = VideoSource.parse("rtsp://192.168.1.50:8554/live")
        assertTrue(result.isSuccess)
        val source = result.getOrThrow()
        assertEquals(VideoProtocol.RTSP, source.protocol)
        assertEquals("rtsp://192.168.1.50:8554/live", source.uri)
        assertEquals("RTSP", source.toString()) // Never leaks sensitive authority details
    }

    @Test
    fun parseValidUdpSource() {
        val result = VideoSource.parse("udp://0.0.0.0:5600")
        assertTrue(result.isSuccess)
        val source = result.getOrThrow()
        assertEquals(VideoProtocol.UDP, source.protocol)
    }

    @Test
    fun parseValidMjpegSource() {
        val result = VideoSource.parse("http://192.168.1.50:5000/video_feed")
        assertTrue(result.isSuccess)
        val source = result.getOrThrow()
        assertEquals(VideoProtocol.MJPEG, source.protocol)
    }

    @Test
    fun rejectsInvalidSource() {
        assertTrue(VideoSource.parse("").isFailure)
        assertTrue(VideoSource.parse("not-a-url").isFailure)
        assertTrue(VideoSource.parse("ftp://192.168.1.1").isFailure)
        assertTrue(VideoSource.parse("udp://192.168.1.1:999999").isFailure)
    }

    @Test
    fun policyOutputSizeScalesWithinLimits() {
        val (w, h) = VideoPlaybackPolicy.outputSize(3840, 2160) // 4K -> 1080p
        assertTrue(w <= 1920)
        assertTrue(h <= 1080)
        assertEquals(1920, w)
        assertEquals(1080, h)

        val (wSmall, hSmall) = VideoPlaybackPolicy.outputSize(640, 480)
        assertEquals(640, wSmall)
        assertEquals(480, hSmall)
    }

    @Test
    fun authenticationRejectedDetection() {
        assertTrue(VideoPlaybackPolicy.authenticationRejected("DESCRIBE 401"))
        assertTrue(VideoPlaybackPolicy.authenticationRejected("SETUP 403"))
        assertTrue(VideoPlaybackPolicy.authenticationRejected("Server returned 401 Unauthorized"))
        assertFalse(VideoPlaybackPolicy.authenticationRejected("Connection refused"))
    }
}
