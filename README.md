# ConnectTag Video Streaming Library (`connecttag-video-streaming`)

A high-performance, ultra-low latency Kotlin Multiplatform (KMP) video streaming, packetizing, and hardware-accelerated decoding library developed by **Connect Tag** (`org.connecttag.video`).

Designed for mission-critical video pipelines, real-time drone telemetry feeds, security cameras, and embedded companion computers.

---

## Features

- **Multiplatform (KMP):** Common API targeting Android (API 26+) and Desktop JVM (Windows, Linux, macOS).
- **RTSP 1.0 Server (Pure Kotlin Coroutines):**
  - RFC 2326 compliant RTSP streaming server (`RtspServer`).
  - Supports `OPTIONS`, `DESCRIBE` (dynamic SDP generation), `SETUP`, `PLAY`, `PAUSE`, `TEARDOWN`.
  - Supports both **TCP Interleaved** (RTP over RTSP channel) and **UDP unicast** RTP transport.
  - Automatic SPS/PPS caching and IDR keyframe gating to ensure clients receive immediate sync without artifacts.
- **RTP Packetizer:**
  - RFC 6184 H.264 video payload packetizer supporting single NAL unit packets and FU-A fragmentation units.
- **H.264 Access Unit Parser:**
  - Fast, zero-allocation Annex-B start-code (`00 00 01` / `00 00 00 01`) and AVCC 4-byte length prefix splitting.
  - Keyframe and SPS/PPS identification.
- **UDP SKYV Chunking Protocol:**
  - Low-overhead framing (`SKYV`) for raw or JPEG/H.264 video frames over unreliable UDP.
  - Robust chunk assembler (`SkyvFrameAssembler`) with bounds checking, out-of-order packet reordering, and stale frame eviction.
- **MJPEG Streaming Parser:**
  - High-throughput multipart boundary parser with `Content-Length` optimization and fallback marker scanning.
- **Native Zero-Latency Hardware Decoders:**
  - **Android:** Hardware `MediaCodec` acceleration via Media3/ExoPlayer pipeline rendering offscreen to EGL `SurfaceTexture` producing Compose `ImageBitmap` frames.
  - **Desktop JVM:** Embedded FFmpeg (JavaCPP) `low_delay` TCP decoding with Skia rasterization directly to Compose `ImageBitmap`. No VLC or external player process required.

---

## Module Structure

```
connecttag-video-streaming/
├── video-core/
│   ├── src/commonMain/kotlin/org/connecttag/video/
│   │   ├── model/         # VideoProtocol, VideoSource, VideoPlaybackState, VideoPlaybackPolicy
│   │   ├── protocol/      # RtpPacketizer, H264AccessUnits, SkyvChunkContract, SkyvFrameAssembler, MjpegFrameParser
│   │   ├── server/        # RtspServer (Pure Kotlin RFC 2326 server)
│   │   └── decoder/       # RtspFrameDecoder (expect/actual native decoder interface)
│   ├── src/androidMain/kotlin/org/connecttag/video/
│   │   └── decoder/       # Media3 + EGL SurfaceTexture decoder
│   └── src/desktopMain/kotlin/org/connecttag/video/
│       └── decoder/       # Embedded FFmpeg (JavaCPP) + Skia decoder
```

---

## Installation

### Gradle Composite Build (`settings.gradle.kts`)

```kotlin
includeBuild("../mainlib/video-streaming")
```

### Module Dependency (`build.gradle.kts`)

```kotlin
dependencies {
    implementation("org.connecttag.video:video-core:0.1.0")
}
```

---

## Quick Start Examples

### 1. Launching an RTSP Server

```kotlin
val rtspServer = RtspServer(
    port = 8554,
    streamPath = "/live",
    videoWidth = 1920,
    videoHeight = 1080,
    fps = 30
)

coroutineScope.launch {
    rtspServer.start()
}

// Push Annex-B H.264 NAL units into the server
val h264NalData: ByteArray = ...
rtspServer.sendVideoFrame(h264NalData)

// Graceful stop
rtspServer.stop()
```

### 2. Decoding an RTSP Stream in Compose Multiplatform

```kotlin
val decoder = createRtspFrameDecoder()

LaunchedEffect(streamUri) {
    decoder.decode(streamUri) { imageBitmap ->
        currentFrame = imageBitmap
    }
}
```

### 3. Assembling UDP SKYV Packets

```kotlin
val assembler = SkyvFrameAssembler()

fun onUdpPacketReceived(packet: ByteArray) {
    val completedFrame = assembler.offerChunk(packet)
    if (completedFrame != null) {
        processJpegFrame(completedFrame)
    }
}
```

---

## License

Proprietary — Connect Tag (https://connecttag.org). All rights reserved.
