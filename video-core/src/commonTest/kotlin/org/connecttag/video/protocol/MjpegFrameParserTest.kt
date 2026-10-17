package org.connecttag.video.protocol

import kotlin.test.*

class MjpegFrameParserTest {

    @Test
    fun parsesMjpegMultipartStream() {
        val parser = MjpegFrameParser()
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val jpegFooter = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val fakeJpeg = jpegHeader + byteArrayOf(0x01, 0x02, 0x03) + jpegFooter

        val streamPart = buildString {
            append("--frame\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${fakeJpeg.size}\r\n\r\n")
        }.encodeToByteArray() + fakeJpeg + "\r\n".encodeToByteArray()

        val frames = parser.feed(streamPart)
        assertEquals(1, frames.size)
        assertContentEquals(fakeJpeg, frames[0])
    }

    @Test
    fun handlesChunkedMjpegChunksAcrossFeedCalls() {
        val parser = MjpegFrameParser()
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xFF.toByte(), 0xD9.toByte())

        val prefix = "--boundary\r\nContent-Type: image/jpeg\r\n\r\n".encodeToByteArray()
        val part1 = prefix + fakeJpeg.copyOfRange(0, 3)
        val part2 = fakeJpeg.copyOfRange(3, fakeJpeg.size) + "\r\n--boundary\r\n".encodeToByteArray()

        val r1 = parser.feed(part1)
        assertEquals(0, r1.size)

        val r2 = parser.feed(part2)
        assertEquals(1, r2.size)
        assertContentEquals(fakeJpeg, r2[0])
    }
}
