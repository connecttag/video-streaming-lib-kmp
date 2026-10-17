package org.connecttag.video.protocol

/**
 * RFC 6184 RTP Packetizer for H.264 (AVC) video streaming over UDP/TCP.
 * Pure Kotlin Multiplatform implementation supporting:
 *  - Single NAL unit packets (NAL <= MTU)
 *  - Fragmentation Units (FU-A) for NAL units larger than MTU
 */
class RtpPacketizer(
    private val mtu: Int = 1360,
    private val payloadType: Int = 96,
    private val ssrc: Int = 0x12345678
) {
    private var sequenceNumber: Int = 1
    private val rtpClockRate = 90000L // 90 kHz standard clock for video

    init { require(mtu >= 15) }

    fun packetizeAccessUnit(accessUnit: ByteArray, timestampUs: Long): List<ByteArray> {
        val units = splitH264NalUnits(accessUnit)
        return units.flatMapIndexed { index, nal ->
            packetize(nal, timestampUs, isLastNalOfFrame = index == units.lastIndex)
        }
    }

    fun packetize(nalUnit: ByteArray, timestampUs: Long, isLastNalOfFrame: Boolean = true): List<ByteArray> {
        var offset = 0
        if (nalUnit.size >= 4 && nalUnit[0] == 0.toByte() && nalUnit[1] == 0.toByte() && nalUnit[2] == 0.toByte() && nalUnit[3] == 1.toByte()) {
            offset = 4
        } else if (nalUnit.size >= 3 && nalUnit[0] == 0.toByte() && nalUnit[1] == 0.toByte() && nalUnit[2] == 1.toByte()) {
            offset = 3
        }

        val nalLength = nalUnit.size - offset
        if (nalLength <= 0) return emptyList()

        val nalHeader = nalUnit[offset]
        val rtpTimestamp = ((timestampUs * rtpClockRate) / 1000000L) and 0xFFFFFFFFL

        val packets = mutableListOf<ByteArray>()
        val maxPayloadSize = mtu - 12 // 12-byte RTP header

        if (nalLength <= maxPayloadSize) {
            // Mode 1: Single NAL Unit Packet
            val packet = ByteArray(12 + nalLength)
            writeRtpHeader(
                buf = packet,
                marker = isLastNalOfFrame,
                timestamp = rtpTimestamp
            )
            nalUnit.copyInto(packet, destinationOffset = 12, startIndex = offset, endIndex = offset + nalLength)
            packets.add(packet)
        } else {
            // Mode 2: Fragmentation Unit (FU-A) - RFC 6184 Section 5.8
            val nalRefIdc = (nalHeader.toInt() and 0x60).toByte()
            val nalType = (nalHeader.toInt() and 0x1F).toByte()

            val fuIndicator = ((nalRefIdc.toInt() or 28)).toByte() // 28 is FU-A type
            val payloadDataOffset = offset + 1 // skip the original NAL header
            val rawPayloadLength = nalLength - 1

            val maxFuPayloadSize = mtu - 14 // 12-byte RTP header + 1-byte FU indicator + 1-byte FU header
            var bytesRemaining = rawPayloadLength
            var currentOffset = payloadDataOffset
            var isFirstFragment = true

            while (bytesRemaining > 0) {
                val chunkSize = minOf(bytesRemaining, maxFuPayloadSize)
                val isLastFragment = (bytesRemaining - chunkSize == 0)

                var fuHeader = nalType.toInt()
                if (isFirstFragment) {
                    fuHeader = fuHeader or 0x80 // S = 1 (Start bit)
                }
                if (isLastFragment) {
                    fuHeader = fuHeader or 0x40 // E = 1 (End bit)
                }

                val packet = ByteArray(12 + 2 + chunkSize)
                writeRtpHeader(
                    buf = packet,
                    marker = isLastNalOfFrame && isLastFragment,
                    timestamp = rtpTimestamp
                )

                packet[12] = fuIndicator
                packet[13] = fuHeader.toByte()

                nalUnit.copyInto(
                    destination = packet,
                    destinationOffset = 14,
                    startIndex = currentOffset,
                    endIndex = currentOffset + chunkSize
                )

                packets.add(packet)

                currentOffset += chunkSize
                bytesRemaining -= chunkSize
                isFirstFragment = false
            }
        }

        return packets
    }

    private fun writeRtpHeader(buf: ByteArray, marker: Boolean, timestamp: Long) {
        buf[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        val mBit = if (marker) 0x80 else 0x00
        buf[1] = ((mBit or (payloadType and 0x7F))).toByte()

        val seq = sequenceNumber and 0xFFFF
        buf[2] = ((seq ushr 8) and 0xFF).toByte()
        buf[3] = (seq and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        buf[4] = ((timestamp ushr 24) and 0xFF).toByte()
        buf[5] = ((timestamp ushr 16) and 0xFF).toByte()
        buf[6] = ((timestamp ushr 8) and 0xFF).toByte()
        buf[7] = (timestamp and 0xFF).toByte()

        buf[8] = ((ssrc ushr 24) and 0xFF).toByte()
        buf[9] = ((ssrc ushr 16) and 0xFF).toByte()
        buf[10] = ((ssrc ushr 8) and 0xFF).toByte()
        buf[11] = (ssrc and 0xFF).toByte()
    }
}
