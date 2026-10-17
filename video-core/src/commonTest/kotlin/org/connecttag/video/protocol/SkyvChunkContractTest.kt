package org.connecttag.video.protocol

import kotlin.test.*

class SkyvChunkContractTest {

    @Test
    fun roundtripHeaderEncoding() {
        val header = SkyvChunkContract.Header(
            frameId = 42,
            chunkIndex = 1,
            totalChunks = 5,
            payloadSize = 1024,
            timestampMs = 123456789L
        )

        val bytes = SkyvChunkContract.writeHeader(header)
        assertEquals(SkyvChunkContract.HEADER_SIZE_BYTES, bytes.size)

        val parsed = SkyvChunkContract.parseHeader(bytes)
        assertNotNull(parsed)
        assertEquals(header.frameId, parsed.frameId)
        assertEquals(header.chunkIndex, parsed.chunkIndex)
        assertEquals(header.totalChunks, parsed.totalChunks)
        assertEquals(header.payloadSize, parsed.payloadSize)
        assertEquals(header.timestampMs, parsed.timestampMs)
    }

    @Test
    fun rejectsInvalidMagic() {
        val bytes = ByteArray(SkyvChunkContract.HEADER_SIZE_BYTES)
        // Corrupt magic
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'A'.code.toByte()
        bytes[2] = 'D'.code.toByte()
        bytes[3] = '!'.code.toByte()

        assertNull(SkyvChunkContract.parseHeader(bytes))
    }

    @Test
    fun chunkPayloadSplitsAndReconstructs() {
        val frameData = ByteArray(3500) { (it % 256).toByte() }
        val chunks = SkyvChunkContract.chunkPayload(
            frameId = 100,
            payload = frameData,
            timestampMs = 5000L,
            maxPayloadPerChunk = 1000
        )

        assertEquals(4, chunks.size) // 1000 + 1000 + 1000 + 500
        val assembler = SkyvFrameAssembler()
        var assembledFrame: ByteArray? = null

        for (packet in chunks) {
            val res = assembler.offerChunk(packet)
            if (res != null) {
                assembledFrame = res
            }
        }

        assertNotNull(assembledFrame)
        assertContentEquals(frameData, assembledFrame)
    }
}
