package org.connecttag.video.protocol

/**
 * Splits Annex-B, four-byte AVCC lengths, or a single unprefixed NAL unit into separate byte arrays.
 */
fun splitH264NalUnits(bytes: ByteArray): List<ByteArray> {
    if (bytes.isEmpty()) return emptyList()
    fun startCode(at: Int): Int = when {
        at + 3 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
            bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte() -> 4
        at + 2 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() &&
            bytes[at + 2] == 1.toByte() -> 3
        else -> 0
    }
    val starts = mutableListOf<Pair<Int, Int>>()
    var cursor = 0
    while (cursor < bytes.size) {
        val length = startCode(cursor)
        if (length > 0) { starts.add(cursor to length); cursor += length } else cursor++
    }
    fun valid(nal: ByteArray) = nal.isNotEmpty() && (nal[0].toInt() and 0x80) == 0 &&
        (nal[0].toInt() and 0x1f) in 1..23
    fun avcc(): List<ByteArray>? {
        val units = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            if (bytes.size - at < 4) return null
            var length = 0L
            repeat(4) { length = (length shl 8) or (bytes[at++].toLong() and 0xff) }
            if (length <= 0 || length > bytes.size - at) return null
            val nal = bytes.copyOfRange(at, at + length.toInt())
            if (!valid(nal)) return null
            units.add(nal); at += length.toInt()
        }
        return units
    }
    if (bytes[0] == 0.toByte()) avcc()?.let { return it }
    if (starts.isNotEmpty() && (0 until starts.first().first).all { bytes[it] == 0.toByte() }) {
        val units = starts.mapIndexed { index, (offset, prefix) ->
            bytes.copyOfRange(offset + prefix, starts.getOrNull(index + 1)?.first ?: bytes.size)
        }
        return units.takeIf { it.all(::valid) }.orEmpty()
    }
    return if (valid(bytes)) listOf(bytes) else emptyList()
}
