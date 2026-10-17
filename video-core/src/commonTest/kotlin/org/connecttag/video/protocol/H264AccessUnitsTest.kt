package org.connecttag.video.protocol

import kotlin.test.*

class H264AccessUnitsTest {

    @Test
    fun annexBSplittingFindsAllNals() {
        val stream = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, // 4-byte start code + SPS
            0x00, 0x00, 0x01, 0x68, 0xCE, 0x38,       // 3-byte start code + PPS
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88        // 4-byte start code + IDR
        )

        val nals = splitH264NalUnits(stream)
        assertEquals(3, nals.size)
        assertEquals(0x67.toByte(), nals[0][0])
        assertEquals(0x68.toByte(), nals[1][0])
        assertEquals(0x65.toByte(), nals[2][0])
    }

    @Test
    fun avccSplittingWithLengthPrefixes() {
        // 2 NALs with 4-byte big-endian lengths
        val nal1 = byteArrayOf(0x67, 0x42, 0x11)
        val nal2 = byteArrayOf(0x68, 0x22)
        val stream = byteArrayOf(
            0, 0, 0, 3, 0x67, 0x42, 0x11,
            0, 0, 0, 2, 0x68, 0x22
        )

        val nals = splitH264AvccUnits(stream)
        assertEquals(2, nals.size)
        assertContentEquals(nal1, nals[0])
        assertContentEquals(nal2, nals[1])
    }

    @Test
    fun isKeyframeIdentifiesIdrAndSps() {
        assertTrue(H264AccessUnits.isKeyframe(byteArrayOf(0x67))) // SPS (7)
        assertTrue(H264AccessUnits.isKeyframe(byteArrayOf(0x65))) // IDR (5)
        assertFalse(H264AccessUnits.isKeyframe(byteArrayOf(0x61))) // Non-IDR (1)
    }
}
