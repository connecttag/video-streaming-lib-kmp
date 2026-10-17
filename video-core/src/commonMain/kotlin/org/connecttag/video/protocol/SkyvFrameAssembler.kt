package org.connecttag.video.protocol

/**
 * Thread-safe bounded reassembler for SKYV fragmented UDP packets.
 * Protects against buffer bloat and late or corrupted chunk arrivals.
 */
class SkyvFrameAssembler(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onStarted: (Int) -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val maxAssemblyLifetimeMs: Long = SkyvChunkContract.ASSEMBLY_MAX_AGE_MS
) {
    init { require(maxAssemblyLifetimeMs > 0L) }
    private var frameId: Int? = null
    private var expected = 0
    private var bytes = 0
    private var completed = false
    private var lastAcceptedMs = 0L
    private var frameStartedMs = 0L
    private val chunks = mutableMapOf<Int, ByteArray>()

    fun offerChunk(packet: ByteArray, length: Int = packet.size): ByteArray? {
        val chunk = SkyvChunkContract.decode(packet, length) ?: return null
        return accept(chunk.frameId, chunk.chunkIndex, chunk.totalChunks, chunk.payload)
    }

    fun accept(id: Int, index: Int, total: Int, payload: ByteArray): ByteArray? {
        if (total !in 1..SkyvChunkContract.MAX_CHUNKS_PER_FRAME || index !in 0 until total ||
            payload.isEmpty() || payload.size > SkyvChunkContract.MAX_CHUNK_PAYLOAD_SIZE) return null
        val now = nowMs()
        val previous = frameId
        if (previous == null || (id != previous && (id - previous > 0 ||
                now - lastAcceptedMs >= SkyvChunkContract.ASSEMBLY_MAX_AGE_MS))) {
            frameId = id
            expected = total
            bytes = 0
            completed = false
            frameStartedMs = now
            chunks.clear()
            onStarted(id)
        }
        if (id != frameId || total != expected || completed || chunks.containsKey(index)) return null
        if (now - frameStartedMs !in 0L..maxAssemblyLifetimeMs ||
            payload.size > SkyvChunkContract.MAX_FRAME_BYTES - bytes) {
            completed = true
            chunks.clear()
            bytes = 0
            return null
        }
        chunks[index] = payload
        bytes += payload.size
        lastAcceptedMs = now
        if (chunks.size != expected) return null
        val frame = ByteArray(bytes)
        var offset = 0
        for (part in 0 until expected) {
            val chunk = chunks.getValue(part)
            chunk.copyInto(frame, offset)
            offset += chunk.size
        }
        completed = true
        chunks.clear()
        onCompleted()
        return frame
    }
}
