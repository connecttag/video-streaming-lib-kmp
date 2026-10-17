package org.connecttag.video.protocol

/**
 * Low-Latency Fragmented UDP Video Streaming Framing Contract ("SKYV").
 *
 * Packet Structure:
 * - Bytes 0..3:  Magic Header ('S', 'K', 'Y', 'V')
 * - Bytes 4..7:  Frame Sequence Index (Int32 Big Endian)
 * - Bytes 8..9:  Chunk Index (0-based) (Uint16 Big Endian)
 * - Bytes 10..11: Total Chunks in Frame (Uint16 Big Endian)
 * - Bytes 12..15: Payload Length in Bytes (Int32 Big Endian)
 * - Bytes 16..N:  Payload chunk (JPEG / H.264 slice)
 */
object SkyvChunkContract {
    val MAGIC_HEADER = byteArrayOf(
        'S'.code.toByte(),
        'K'.code.toByte(),
        'Y'.code.toByte(),
        'V'.code.toByte()
    )

    const val HEADER_SIZE = 16
    const val HEADER_SIZE_BYTES = HEADER_SIZE
    const val CHUNK_PAYLOAD_SIZE = 1200
    const val MAX_CHUNK_PAYLOAD_SIZE = 1360
    const val MAX_UDP_PACKET_SIZE = HEADER_SIZE + MAX_CHUNK_PAYLOAD_SIZE
    const val MAX_FRAME_BYTES = 5 * 1024 * 1024
    const val MAX_CHUNKS_PER_FRAME = (MAX_FRAME_BYTES + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE
    const val MAX_DECODED_FRAME_PIXELS = 16_777_216L
    const val MAX_DECODED_FRAME_DIMENSION = 8192
    const val FRAME_MAX_AGE_MS = 1_500L
    const val ASSEMBLY_MAX_AGE_MS = 1_000L

    fun isSkyvPacket(data: ByteArray, length: Int = data.size): Boolean {
        if (length < HEADER_SIZE || length > data.size) return false
        return data[0] == MAGIC_HEADER[0] &&
            data[1] == MAGIC_HEADER[1] &&
            data[2] == MAGIC_HEADER[2] &&
            data[3] == MAGIC_HEADER[3]
    }

    fun decodedDimensionsAllowed(width: Int, height: Int): Boolean =
        width in 1..MAX_DECODED_FRAME_DIMENSION && height in 1..MAX_DECODED_FRAME_DIMENSION &&
            width.toLong() * height <= MAX_DECODED_FRAME_PIXELS

    fun captureIsFresh(capturedAtNanos: Long, nowNanos: Long): Boolean =
        nowNanos - capturedAtNanos in 0L..FRAME_MAX_AGE_MS * 1_000_000L

    data class Chunk(val frameId: Int, val index: Int, val total: Int, val payload: ByteArray) {
        val chunkIndex: Int get() = index
        val totalChunks: Int get() = total
    }

    fun decode(data: ByteArray, length: Int = data.size): Chunk? {
        if (!isSkyvPacket(data, length) || length > MAX_UDP_PACKET_SIZE) return null
        fun intAt(offset: Int) = ((data[offset].toInt() and 255) shl 24) or
            ((data[offset + 1].toInt() and 255) shl 16) or ((data[offset + 2].toInt() and 255) shl 8) or
            (data[offset + 3].toInt() and 255)
        fun shortAt(offset: Int) = ((data[offset].toInt() and 255) shl 8) or (data[offset + 1].toInt() and 255)
        val index = shortAt(8)
        val total = shortAt(10)
        val payloadLength = intAt(12)
        if (total !in 1..MAX_CHUNKS_PER_FRAME || index !in 0 until total ||
            payloadLength !in 1..MAX_CHUNK_PAYLOAD_SIZE || HEADER_SIZE + payloadLength > length) return null
        return Chunk(intAt(4), index, total, data.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength))
    }

    fun encode(frameId: Int, index: Int, total: Int, payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        require(total in 1..MAX_CHUNKS_PER_FRAME && index in 0 until total)
        require(length in 1..MAX_CHUNK_PAYLOAD_SIZE && offset + length <= payload.size)
        val buffer = ByteArray(HEADER_SIZE + length)
        MAGIC_HEADER.copyInto(buffer, 0)
        fun putInt(offset: Int, value: Int) {
            buffer[offset] = ((value ushr 24) and 255).toByte()
            buffer[offset + 1] = ((value ushr 16) and 255).toByte()
            buffer[offset + 2] = ((value ushr 8) and 255).toByte()
            buffer[offset + 3] = (value and 255).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            buffer[offset] = ((value ushr 8) and 255).toByte()
            buffer[offset + 1] = (value and 255).toByte()
        }
        putInt(4, frameId)
        putShort(8, index)
        putShort(10, total)
        putInt(12, length)
        payload.copyInto(buffer, HEADER_SIZE, offset, offset + length)
        return buffer
    }
}
