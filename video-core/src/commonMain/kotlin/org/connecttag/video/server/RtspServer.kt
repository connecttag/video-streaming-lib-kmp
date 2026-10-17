package org.connecttag.video.server

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connecttag.video.protocol.RtpPacketizer
import org.connecttag.video.protocol.splitH264NalUnits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

enum class RtspServerFailure { DISABLED, CLOSED }
class RtspServerException(val failure: RtspServerFailure) : Exception()

/**
 * Pure Kotlin RFC 2326 RTSP 1.0 / H.264 streaming server.
 * Supports:
 * - OPTIONS, DESCRIBE (with dynamic SDP generation containing SPS/PPS), SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER.
 * - Transport via TCP interleaved (RTP/AVP/TCP) and UDP unicast pairs (RTP/AVP).
 * - RTCP Sender Reports.
 * - Keyframe request callbacks (IDR gating).
 */
class RtspServer(
    private val mayStream: () -> Boolean = { true },
    serverDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val defaultPort: Int = 8554,
    val serverName: String = "ConnectTag-RTSP/1.0"
) : AutoCloseable {

    companion object {
        private const val MAX_LINE = 2048
        private const val MAX_HEADERS = 64
        private const val MAX_BODY = 8192
        private const val MAX_CLIENTS = 4
        private const val MAX_ACCESS_UNIT = 2 * 1024 * 1024
        private const val MAX_WRITE_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + serverDispatcher)
    private val lifecycle = Any()
    private val sourceLock = Any()
    private val epoch = AtomicLong()
    private val sourceGeneration = AtomicLong()
    private var sourceInvalidated = false

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var closed = false
    private var serverJob: Job? = null
    private val clients = CopyOnWriteArrayList<ClientSession>()
    private val ids = AtomicLong(1000)

    private val mutableStreaming = MutableStateFlow(false)
    val isStreaming = mutableStreaming.asStateFlow()

    private val mutablePort = MutableStateFlow(defaultPort)
    val boundPort = mutablePort.asStateFlow()

    private val mutableClients = MutableStateFlow(0)
    val connectedClients = mutableClients.asStateFlow()

    val isRunning: Boolean get() = mutableStreaming.value && listener?.isClosed == false

    @Volatile var onKeyFrameRequested: (() -> Unit)? = null
    @Volatile var spsProvider: (() -> ByteArray?)? = null
    @Volatile var ppsProvider: (() -> ByteArray?)? = null

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var packetizer = RtpPacketizer(ssrc = Random.nextInt())

    private data class AccessUnit(val epoch: Long, val generation: Long, val packets: List<ByteArray>)
    private data class QueuedUnit(val unit: AccessUnit, val playback: Long)

    private inner class ClientSession(val socket: Socket, val binding: Long) {
        val id = ids.incrementAndGet().toString()
        val output = socket.getOutputStream()
        val writeLock = Any()
        val queue = Channel<QueuedUnit>(3)
        val playback = AtomicLong()
        val writingAt = AtomicLong(-1)
        val lastRequestAt = AtomicLong(System.nanoTime())
        @Volatile var playing = false
        @Volatile var waitingForIdr = true
        @Volatile var setup = false
        var tcp = true
        var rtpChannel = 0
        var rtcpChannel = 1
        var rtpSocket: DatagramSocket? = null
        var rtcpSocket: DatagramSocket? = null
        var clientRtp = 0
        var clientRtcp = 0
        var writer: Job? = null
        var watchdog: Job? = null
        var packetCount = 0L
        var byteCount = 0L
        var lastReport = 0L

        fun current() = !socket.isClosed && epoch.get() == binding && mayStream()

        fun close() {
            playing = false
            runCatching { socket.close() }
            runCatching { rtpSocket?.close() }
            runCatching { rtcpSocket?.close() }
            rtpSocket = null
            rtcpSocket = null
            queue.close()
            writer?.cancel()
            watchdog?.cancel()
            clients.remove(this)
            mutableClients.value = clients.size
        }

        fun response(code: Int, reason: String, cseq: String, headers: String = "", body: ByteArray = byteArrayOf()) {
            val text = "RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\nServer: $serverName\r\n" +
                headers + if (body.isEmpty()) "\r\n" else "Content-Length: ${body.size}\r\n\r\n"
            synchronized(writeLock) {
                writingAt.set(System.nanoTime())
                try {
                    output.write(text.toByteArray(Charsets.US_ASCII))
                    if (body.isNotEmpty()) output.write(body)
                    output.flush()
                } finally {
                    writingAt.set(-1)
                }
            }
        }

        fun binary(packet: ByteArray, channel: Int, rtcp: Boolean) {
            if (tcp) {
                val header = byteArrayOf(0x24, channel.toByte(), (packet.size ushr 8).toByte(), packet.size.toByte())
                output.write(header)
                output.write(packet)
            } else {
                val udp = if (rtcp) rtcpSocket else rtpSocket
                requireNotNull(udp).send(DatagramPacket(packet, packet.size, socket.inetAddress, if (rtcp) clientRtcp else clientRtp))
            }
        }

        fun writeAccessUnit(queued: QueuedUnit) = synchronized(writeLock) {
            val unit = queued.unit
            if (!current() || !playing || queued.playback != playback.get() ||
                unit.epoch != binding || unit.generation != sourceGeneration.get()) return@synchronized
            writingAt.set(System.nanoTime())
            try {
                for (packet in unit.packets) {
                    if (!current() || !playing || queued.playback != playback.get() ||
                        unit.generation != sourceGeneration.get()) return@synchronized
                    binary(packet, rtpChannel, false)
                    packetCount++
                    byteCount += packet.size - 12
                }
                val now = System.currentTimeMillis()
                if (unit.packets.isNotEmpty() && now - lastReport >= 1000) {
                    val first = unit.packets.first()
                    val sr = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                    sr.put(0x80.toByte()).put(200.toByte()).putShort(6)
                    sr.put(first, 8, 4)
                    sr.putInt((now / 1000 + 2_208_988_800L).toInt())
                    sr.putInt(((now % 1000) * 0x1_0000_0000L / 1000).toInt())
                    sr.put(first, 4, 4).putInt(packetCount.toInt()).putInt(byteCount.toInt())
                    binary(sr.array(), rtcpChannel, true)
                    lastReport = now
                }
                if (tcp) output.flush()
            } finally {
                writingAt.set(-1)
            }
        }
    }

    fun start(port: Int = defaultPort): Result<Unit> = synchronized(lifecycle) {
        if (closed) return@synchronized Result.failure(RtspServerException(RtspServerFailure.CLOSED))
        stop()
        if (!mayStream()) return@synchronized Result.failure(RtspServerException(RtspServerFailure.DISABLED))
        try {
            val socket = ServerSocket(port)
            val binding = epoch.incrementAndGet()
            listener = socket
            mutablePort.value = socket.localPort
            mutableStreaming.value = true
            serverJob = scope.launch { accept(socket, binding) }
            Result.success(Unit)
        } catch (error: Exception) {
            stop()
            Result.failure(error)
        }
    }

    fun stop() = synchronized(lifecycle) {
        epoch.incrementAndGet()
        mutableStreaming.value = false
        runCatching { listener?.close() }
        listener = null
        serverJob?.cancel()
        serverJob = null
        clients.toList().forEach { it.close() }
        mutableClients.value = clients.size
    }

    fun invalidateSource(generation: Long = sourceGeneration.get() + 1) {
        synchronized(sourceLock) {
            sourceGeneration.set(generation)
            sourceInvalidated = true
            sps = null
            pps = null
            packetizer = RtpPacketizer(ssrc = Random.nextInt())
            clients.toList().forEach { it.close() }
        }
    }

    fun onH264NalUnit(
        bytes: ByteArray,
        timestampUs: Long,
        isKeyFrame: Boolean,
        generation: Long = sourceGeneration.get()
    ) {
        if (bytes.isEmpty() || bytes.size > MAX_ACCESS_UNIT || timestampUs < 0 || !isRunning || !mayStream()) return
        synchronized(sourceLock) {
            if (generation != sourceGeneration.get()) return
            val units = splitH264NalUnits(bytes)
            for (nal in units) {
                when (nal.firstOrNull()?.toInt()?.and(31)) {
                    7 -> sps = nal.copyOf()
                    8 -> pps = nal.copyOf()
                }
            }
            val idr = isKeyFrame && units.any { it.firstOrNull()?.toInt()?.and(31) == 5 }
            val active = clients.filter { it.current() && it.playing && (!it.waitingForIdr || idr) }
            if (active.isEmpty()) return
            val packets = packetizer.packetizeAccessUnit(bytes, timestampUs)
            if (packets.isEmpty()) return
            val batch = AccessUnit(epoch.get(), generation, packets)
            for (client in active) {
                client.waitingForIdr = false
                if (!client.queue.trySend(QueuedUnit(batch, client.playback.get())).isSuccess) client.close()
            }
        }
    }

    private suspend fun accept(socket: ServerSocket, binding: Long) {
        while (scope.isActive && epoch.get() == binding && !socket.isClosed) {
            val peer = try { socket.accept() } catch (_: Exception) { break }
            if (epoch.get() != binding || !mayStream() || clients.size >= MAX_CLIENTS) {
                peer.close()
                continue
            }
            peer.soTimeout = 6000
            peer.tcpNoDelay = true
            peer.sendBufferSize = 16384
            val client = ClientSession(peer, binding)
            synchronized(lifecycle) {
                if (epoch.get() != binding || listener !== socket) {
                    client.close()
                } else {
                    clients += client
                    mutableClients.value = clients.size
                }
            }
            if (!client.current()) {
                client.close()
                continue
            }
            client.writer = scope.launch {
                try {
                    for (unit in client.queue) client.writeAccessUnit(unit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                } finally {
                    client.close()
                }
            }
            client.watchdog = scope.launch {
                while (isActive && client.current()) {
                    delay(250)
                    val since = client.writingAt.get()
                    if (since >= 0 && (System.nanoTime() - since) / 1_000_000 > MAX_WRITE_MS) {
                        client.close()
                        break
                    }
                    if ((System.nanoTime() - client.lastRequestAt.get()) / 1_000_000 > 60_000) {
                        client.close()
                        break
                    }
                }
            }
            scope.launch { handle(client) }
        }
    }

    private suspend fun handle(client: ClientSession) {
        val input = client.socket.getInputStream()
        try {
            while (currentCoroutineContext().isActive && client.current()) {
                val first = try { input.read() } catch (_: SocketTimeoutException) { continue }
                if (first < 0) break
                if (first == 0x24) {
                    val channel = input.read()
                    val hi = input.read()
                    val lo = input.read()
                    if (channel < 0 || hi < 0 || lo < 0) break
                    require(client.setup && client.tcp && channel == client.rtcpChannel)
                    readExact(input, (hi shl 8) or lo)
                    continue
                }
                val line = readLine(input, first) ?: break
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val header = readLine(input) ?: throw IllegalArgumentException()
                    if (header.isEmpty()) break
                    require(headers.size < MAX_HEADERS)
                    val colon = header.indexOf(':')
                    require(colon > 0)
                    val name = header.substring(0, colon).trim().lowercase()
                    require(name !in headers)
                    headers[name] = header.substring(colon + 1).trim()
                }
                val body = headers["content-length"]?.toIntOrNull() ?: if ("content-length" in headers) throw IllegalArgumentException() else 0
                require(body in 0..MAX_BODY)
                readExact(input, body)
                command(line, headers, client)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        } finally {
            client.close()
        }
    }

    private fun command(line: String, headers: Map<String, String>, client: ClientSession) {
        val parts = line.split(' ')
        val cseq = headers["cseq"]?.takeIf { it.toLongOrNull()?.let { value -> value >= 0 } == true } ?: throw IllegalArgumentException()
        if (parts.size != 3 || parts[2] != "RTSP/1.0") {
            client.response(400, "Bad Request", cseq)
            return
        }
        val method = parts[0]
        val uri = runCatching { URI(parts[1]) }.getOrNull() ?: run {
            client.response(400, "Bad Request", cseq)
            return
        }
        if (!(method == "OPTIONS" && parts[1] == "*") && (!uri.scheme.equals("rtsp", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.port != -1 && uri.port !in 1..65535 || uri.rawUserInfo != null ||
            uri.rawPath !in setOf("/live", "/live/", "/live/trackID=0") || uri.rawQuery != null || uri.rawFragment != null)) {
            client.response(404, "Not Found", cseq)
            return
        }
        if (!client.current()) {
            client.response(403, "Forbidden", cseq)
            client.close()
            return
        }
        if (method in setOf("PLAY", "PAUSE", "TEARDOWN", "GET_PARAMETER") &&
            (headers["session"]?.substringBefore(';') != client.id || !client.setup)) {
            client.response(454, "Session Not Found", cseq)
            return
        }
        if ("session" in headers && headers["session"]?.substringBefore(';') != client.id) {
            client.response(454, "Session Not Found", cseq)
            return
        }
        client.lastRequestAt.set(System.nanoTime())
        when (method) {
            "OPTIONS" -> client.response(200, "OK", cseq, "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n")
            "DESCRIBE" -> {
                val config = synchronized(sourceLock) {
                    val actualSps = sps ?: if (!sourceInvalidated) spsProvider?.invoke() else null
                    val actualPps = pps ?: if (!sourceInvalidated) ppsProvider?.invoke() else null
                    actualSps?.takeIf { it.size >= 4 && it[0].toInt() and 31 == 7 }?.copyOf() to
                        actualPps?.takeIf { it.isNotEmpty() && it[0].toInt() and 31 == 8 }?.copyOf()
                }
                val currentSps = config.first
                val currentPps = config.second
                if (currentSps == null || currentPps == null) {
                    client.response(503, "Service Unavailable", cseq, "Retry-After: 1\r\n")
                    onKeyFrameRequested?.invoke()
                    return
                }
                val address = client.socket.localAddress.hostAddress ?: throw IllegalArgumentException()
                val family = if (client.socket.localAddress is Inet6Address) "IP6" else "IP4"
                val profile = currentSps.drop(1).take(3).joinToString("") { "%02x".format(it.toInt() and 255) }
                val b64 = Base64.getEncoder()
                val sdp = "v=0\r\no=- ${client.id} ${sourceGeneration.get()} IN $family $address\r\ns=ConnectTag\r\n" +
                    "c=IN $family $address\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\n" +
                    "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;profile-level-id=$profile;" +
                    "sprop-parameter-sets=${b64.encodeToString(currentSps)},${b64.encodeToString(currentPps)}\r\na=control:trackID=0\r\n"
                val base = URI(uri.scheme, null, uri.host, uri.port, "/live/", null, null).toASCIIString()
                client.response(200, "OK", cseq, "Content-Type: application/sdp\r\nContent-Base: $base\r\n", sdp.toByteArray(Charsets.US_ASCII))
            }
            "SETUP" -> {
                if (client.playing) {
                    client.response(455, "Method Not Valid in This State", cseq)
                    return
                }
                val transport = headers["transport"].orEmpty()
                val profile = transport.substringBefore(';').uppercase()
                if (',' in transport || transport.contains("multicast", true) || transport.contains("destination=", true)) {
                    client.response(461, "Unsupported Transport", cseq)
                    return
                }
                client.setup = false
                client.playback.incrementAndGet()
                while (client.queue.tryReceive().isSuccess) { }
                client.rtpSocket?.close()
                client.rtcpSocket?.close()
                client.rtpSocket = null
                client.rtcpSocket = null
                if (profile == "RTP/AVP/TCP") {
                    val pair = Regex("(?:^|;)interleaved=(\\d+)-(\\d+)(?:;|$)", RegexOption.IGNORE_CASE).find(transport)
                    val rtp = pair?.groupValues?.get(1)?.toIntOrNull()
                    val rtcp = pair?.groupValues?.get(2)?.toIntOrNull()
                    if (rtp == null || rtcp == null || rtp !in 0..255 || rtcp !in 0..255 || rtp == rtcp) {
                        client.response(461, "Unsupported Transport", cseq)
                        return
                    }
                    client.tcp = true
                    client.rtpChannel = rtp
                    client.rtcpChannel = rtcp
                    client.setup = true
                    client.response(200, "OK", cseq, "Session: ${client.id};timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp\r\n")
                } else if (profile == "RTP/AVP" || profile == "RTP/AVP/UDP") {
                    val pair = Regex("(?:^|;)client_port=(\\d+)-(\\d+)(?:;|$)", RegexOption.IGNORE_CASE).find(transport)
                    val rtp = pair?.groupValues?.get(1)?.toIntOrNull()
                    val rtcp = pair?.groupValues?.get(2)?.toIntOrNull()
                    if (rtp == null || rtcp == null || rtp !in 1024..65535 || rtcp !in 1024..65535 || rtp == rtcp) {
                        client.response(461, "Unsupported Transport", cseq)
                        return
                    }
                    var sockets: Pair<DatagramSocket, DatagramSocket>? = null
                    repeat(16) {
                        if (sockets != null) return@repeat
                        val one = DatagramSocket(0)
                        if (one.localPort % 2 != 0 || one.localPort == 65535) {
                            one.close()
                            return@repeat
                        }
                        val two = try { DatagramSocket(one.localPort + 1) } catch (_: Exception) { one.close(); return@repeat }
                        sockets = one to two
                    }
                    val allocated = sockets ?: run {
                        client.response(503, "Service Unavailable", cseq)
                        return
                    }
                    client.tcp = false
                    client.clientRtp = rtp
                    client.clientRtcp = rtcp
                    client.rtpSocket = allocated.first
                    client.rtcpSocket = allocated.second
                    client.setup = true
                    client.response(200, "OK", cseq, "Session: ${client.id};timeout=60\r\nTransport: RTP/AVP;unicast;client_port=$rtp-$rtcp;" +
                        "server_port=${allocated.first.localPort}-${allocated.second.localPort}\r\n")
                } else {
                    client.response(461, "Unsupported Transport", cseq)
                }
            }
            "PLAY" -> {
                synchronized(sourceLock) {
                    client.playing = false
                    client.playback.incrementAndGet()
                    while (client.queue.tryReceive().isSuccess) { }
                }
                client.response(200, "OK", cseq, "Session: ${client.id}\r\nRange: npt=0.000-\r\n")
                client.lastReport = System.currentTimeMillis()
                synchronized(sourceLock) {
                    client.waitingForIdr = true
                    client.playing = true
                }
                onKeyFrameRequested?.invoke()
            }
            "PAUSE" -> {
                synchronized(sourceLock) {
                    client.playing = false
                    client.playback.incrementAndGet()
                    while (client.queue.tryReceive().isSuccess) { }
                }
                client.response(200, "OK", cseq, "Session: ${client.id}\r\n")
            }
            "TEARDOWN" -> {
                client.response(200, "OK", cseq, "Session: ${client.id}\r\n")
                client.close()
            }
            "GET_PARAMETER" -> client.response(200, "OK", cseq, "Session: ${client.id}\r\n")
            else -> client.response(501, "Not Implemented", cseq)
        }
    }

    private fun readLine(input: InputStream, initial: Int? = null): String? {
        val bytes = ByteArrayOutputStream()
        var next = initial ?: input.read()
        if (next < 0) return null
        while (next != 10) {
            if (next < 0 || bytes.size() >= MAX_LINE) throw IllegalArgumentException()
            if (next != 13) bytes.write(next)
            next = input.read()
        }
        return bytes.toString("US-ASCII")
    }

    private fun readExact(input: InputStream, count: Int) {
        var remaining = count
        val bytes = ByteArray(minOf(remaining, 4096))
        while (remaining > 0) {
            val read = input.read(bytes, 0, minOf(bytes.size, remaining))
            if (read <= 0) throw IllegalArgumentException()
            remaining -= read
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            closed = true
            stop()
            scope.cancel()
        }
    }
}
