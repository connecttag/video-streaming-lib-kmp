package org.connecttag.video.protocol

/**
 * Bounded streaming parser for HTTP multipart MJPEG video streams.
 * Correctly parses Content-Length headers and handles embedded JPEG thumbnails.
 */
class MjpegFrameParser {
    private var header = ByteArray(1024)
    private var headerSize = 0
    private var frame = ByteArray(64 * 1024)
    private var frameSize = 0
    private var expectedLength: Int? = null
    private var scanningJpeg = false
    private var previous = -1

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        feed(bytes, length) { list.add(it) }
        return list
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size, onFrame: (ByteArray) -> Unit): Boolean {
        if (length !in 0..bytes.size) return false
        var i = 0
        while (i < length) {
            val contentLength = expectedLength
            if (contentLength != null) {
                val count = minOf(length - i, contentLength - frameSize)
                ensureFrameCapacity(frameSize + count)
                bytes.copyInto(frame, frameSize, i, i + count)
                frameSize += count
                i += count
                previous = bytes[i - 1].toInt() and 255
                if (frameSize == contentLength) emitFrame(onFrame)
                continue
            }
            val value = bytes[i].toInt() and 255
            if (scanningJpeg) {
                if (frameSize >= SkyvChunkContract.MAX_FRAME_BYTES) return false
                ensureFrameCapacity(frameSize + 1)
                frame[frameSize++] = bytes[i]
                if (previous == 255 && value == 217) emitFrame(onFrame)
            } else if (previous == 255 && value == 216) {
                frame[0] = 255.toByte(); frame[1] = 216.toByte()
                frameSize = 2
                scanningJpeg = true
                headerSize = 0
            } else {
                if (headerSize >= MAX_HEADER_BYTES) return false
                if (headerSize == header.size) header = header.copyOf(minOf(header.size * 2, MAX_HEADER_BYTES))
                header[headerSize++] = bytes[i]
                if (headerSize >= 4 && header[headerSize - 4] == 13.toByte() && header[headerSize - 3] == 10.toByte() &&
                    header[headerSize - 2] == 13.toByte() && header[headerSize - 1] == 10.toByte()) {
                    val lengths = header.decodeToString(0, headerSize).lineSequence()
                        .map { it.trim() }.filter { it.substringBefore(':').equals("Content-Length", true) }
                        .map { it.substringAfter(':').trim().toIntOrNull() }.toList()
                    if (lengths.size > 1 || lengths.any { it == null || it !in 4..SkyvChunkContract.MAX_FRAME_BYTES }) return false
                    expectedLength = lengths.singleOrNull()
                    headerSize = 0
                    frameSize = 0
                }
            }
            previous = value
            i++
        }
        return true
    }

    private fun emitFrame(onFrame: (ByteArray) -> Unit) {
        val payload = frame.copyOf(frameSize)
        reset()
        onFrame(payload)
    }

    fun reset() {
        frameSize = 0
        headerSize = 0
        expectedLength = null
        scanningJpeg = false
        previous = -1
    }

    private fun ensureFrameCapacity(required: Int) {
        if (required <= frame.size) return
        var next = frame.size
        while (next < required) {
            next = if (next >= SkyvChunkContract.MAX_FRAME_BYTES / 2) SkyvChunkContract.MAX_FRAME_BYTES else next * 2
        }
        frame = frame.copyOf(next)
    }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
    }
}
