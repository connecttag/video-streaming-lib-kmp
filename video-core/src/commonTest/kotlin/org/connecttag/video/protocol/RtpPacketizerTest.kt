package org.connecttag.video.protocol

import kotlin.test.*

class RtpPacketizerTest {

    @Test
    fun singleNalWithinMtuIsPackedDirectly() {
        val spsNal = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        val packets = RtpPacketizer.packetizeH264(spsNal, timestamp = 12345L, payloadType = 96, mtu = 1400)
        assertEquals(1, packets.size)
        val packet = packets[0]
        assertEquals(12 + spsNal.size, packet.size)
        // RTP Header: V=2, P=0, X=0, CC=0 -> 0x80
        assertEquals(0x80.toByte(), packet[0])
        // Marker bit set for single NAL: (1 << 7) | 96 = 0xE0
        assertEquals(0xE0.toByte(), packet[1])
        // Check payload starts at index 12
        assertContentEquals(spsNal, packet.copyOfRange(12, packet.size))
    }

    @Test
    fun largeNalIsFragmentedIntoFuA() {
        val payloadSize = 2500
        val largeNal = ByteArray(payloadSize) { it.toByte() }
        largeNal[0] = 0x65.toByte() // IDR NAL unit type 5, ref_idc 3

        val packets = RtpPacketizer.packetizeH264(largeNal, timestamp = 99999L, payloadType = 96, mtu = 1000)
        assertTrue(packets.size > 2)

        // First packet must have Start bit set in FU header
        val p0 = packets[0]
        val fuIndicator0 = p0[12]
        val fuHeader0 = p0[13]
        // FU indicator: (nal[0] & 0x60) | 28
        assertEquals(((0x65 and 0x60) or 28).toByte(), fuIndicator0)
        // Start bit = 0x80, End bit = 0x00, type = (0x65 & 0x1F) = 5
        assertEquals((0x80 or 5).toByte(), fuHeader0)
        // Marker bit must NOT be set on the start packet
        assertEquals(96.toByte(), p0[1])

        // Last packet must have End bit set and Marker bit in RTP header
        val pLast = packets.last()
        val fuHeaderLast = pLast[13]
        // End bit = 0x40, type = 5
        assertEquals((0x40 or 5).toByte(), fuHeaderLast)
        assertEquals((0x80 or 96).toByte(), pLast[1])
    }
}
