package org.connecttag.video.decoder

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import org.bytedeco.ffmpeg.avcodec.*
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avutil.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.Pointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.connecttag.video.model.VideoDecodeException
import org.connecttag.video.model.VideoFailure
import org.connecttag.video.model.VideoPlaybackPolicy
import java.util.concurrent.atomic.AtomicLong

actual fun createRtspFrameDecoder(): RtspFrameDecoder = DesktopRtspFrameDecoder()

/** FFmpeg is packaged with the application; no VLC installation or external player process needed. */
class DesktopRtspFrameDecoder : RtspFrameDecoder {
    private companion object {
        val networkReady: Unit by lazy {
            if (avformat_network_init() < 0) throw VideoDecodeException(VideoFailure.NETWORK)
        }
    }

    override suspend fun decode(uri: String, onFrame: (ImageBitmap) -> Unit) {
        try { withContext(Dispatchers.IO) { receive(uri, onFrame, currentCoroutineContext().job) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { throw VideoDecodeException(VideoFailure.DECODER_UNAVAILABLE) }
    }

    private fun receive(uri: String, onFrame: (ImageBitmap) -> Unit, owner: Job) {
        networkReady
        fun clock() = System.nanoTime() / 1_000_000L
        val deadline = AtomicLong(clock() + VideoPlaybackPolicy.READ_TIMEOUT_MS)
        fun armTimeout() { deadline.set(clock() + VideoPlaybackPolicy.READ_TIMEOUT_MS) }
        av_log_set_level(AV_LOG_QUIET)
        val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
            override fun call(opaque: Pointer?): Int = if (!owner.isActive || clock() >= deadline.get()) 1 else 0
        }
        val input = avformat_alloc_context()
        var codec: AVCodecContext? = null
        var packet: AVPacket? = null
        var decoded: AVFrame? = null
        var rgba: AVFrame? = null
        var scaler: SwsContext? = null
        val options = AVDictionary(null as Pointer?)
        var opened = false
        fun checked(code: Int, failure: VideoFailure = VideoFailure.DECODE) {
            owner.ensureActive()
            if (code < 0) throw VideoDecodeException(failure)
        }
        try {
            if (input == null || input.isNull) throw VideoDecodeException(VideoFailure.DECODER_UNAVAILABLE)
            input.interrupt_callback().callback(interrupt)
            av_dict_set(options, "rtsp_transport", "tcp", 0)
            av_dict_set(options, "timeout", (VideoPlaybackPolicy.READ_TIMEOUT_MS * 1000).toString(), 0)
            av_dict_set(options, "rw_timeout", (VideoPlaybackPolicy.READ_TIMEOUT_MS * 1000).toString(), 0)
            av_dict_set(options, "flags", "low_delay", 0)
            av_dict_set(options, "analyzeduration", "1000000", 0)
            av_dict_set(options, "probesize", "1048576", 0)
            armTimeout()
            val openResult = avformat_open_input(input, uri, null, options)
            checked(openResult, if (openResult in setOf(AVERROR_HTTP_UNAUTHORIZED(), AVERROR_HTTP_FORBIDDEN()))
                VideoFailure.AUTHENTICATION else VideoFailure.NETWORK)
            opened = true
            armTimeout()
            checked(avformat_find_stream_info(input, null as AVDictionary?), VideoFailure.NETWORK)
            val stream = (0 until input.nb_streams()).firstOrNull {
                input.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO
            } ?: throw VideoDecodeException(VideoFailure.UNSUPPORTED_CODEC)
            val parameters = input.streams(stream).codecpar()
            if (parameters.codec_id() != AV_CODEC_ID_H264) throw VideoDecodeException(VideoFailure.UNSUPPORTED_CODEC)
            if (parameters.width() > 8192 || parameters.height() > 8192) throw VideoDecodeException(VideoFailure.DECODE)
            val decoder = avcodec_find_decoder(parameters.codec_id())
            if (decoder == null || decoder.isNull) throw VideoDecodeException(VideoFailure.DECODER_UNAVAILABLE)
            codec = avcodec_alloc_context3(decoder)
            if (codec == null || codec.isNull) throw VideoDecodeException(VideoFailure.DECODE)
            checked(avcodec_parameters_to_context(codec, parameters))
            codec.thread_count(2)
            checked(avcodec_open2(codec, decoder, null as AVDictionary?))
            packet = av_packet_alloc(); decoded = av_frame_alloc(); rgba = av_frame_alloc()
            if (packet == null || packet.isNull || decoded == null || decoded.isNull || rgba == null || rgba.isNull)
                throw VideoDecodeException(VideoFailure.DECODE)
            var outputSize = 0 to 0
            var lastPublished = Long.MIN_VALUE / 2
            while (owner.isActive) {
                armTimeout()
                checked(av_read_frame(input, packet), VideoFailure.NETWORK)
                try {
                    if (packet.stream_index() != stream) continue
                    var pending = true
                    while (pending && owner.isActive) {
                        val sent = avcodec_send_packet(codec, packet)
                        pending = sent == AVERROR_EAGAIN()
                        if (!pending) checked(sent)
                        var drained = false
                        while (owner.isActive) {
                            val result = avcodec_receive_frame(codec, decoded)
                            if (result == AVERROR_EAGAIN() || result == AVERROR_EOF) break
                            checked(result)
                            drained = true
                            try {
                                val now = clock()
                                if (now - lastPublished < VideoPlaybackPolicy.FRAME_INTERVAL_MS) continue
                                val size = VideoPlaybackPolicy.outputSize(decoded.width(), decoded.height())
                                if (size != outputSize) {
                                    av_frame_unref(rgba)
                                    rgba.format(AV_PIX_FMT_RGBA).width(size.first).height(size.second)
                                    checked(av_frame_get_buffer(rgba, 32))
                                    outputSize = size
                                }
                                checked(av_frame_make_writable(rgba))
                                scaler = sws_getCachedContext(scaler, decoded.width(), decoded.height(), decoded.format(),
                                    size.first, size.second, AV_PIX_FMT_RGBA, SWS_FAST_BILINEAR, null, null, null as DoublePointer?)
                                if (scaler == null || scaler.isNull) throw VideoDecodeException(VideoFailure.DECODE)
                                val coefficients = sws_getCoefficients(when (decoded.colorspace()) {
                                    AVCOL_SPC_BT709 -> SWS_CS_ITU709
                                    AVCOL_SPC_SMPTE240M -> SWS_CS_SMPTE240M
                                    AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> SWS_CS_BT2020
                                    else -> SWS_CS_ITU601
                                })
                                checked(sws_setColorspaceDetails(scaler, coefficients,
                                    if (decoded.color_range() == AVCOL_RANGE_JPEG) 1 else 0,
                                    coefficients, 1, 0, 1 shl 16, 1 shl 16))
                                checked(sws_scale(scaler, decoded.data(), decoded.linesize(), 0, decoded.height(), rgba.data(), rgba.linesize()))
                                val rowBytes = size.first * 4
                                val bytes = ByteArray(rowBytes * size.second)
                                val pixels = rgba.data(0)
                                for (row in 0 until size.second)
                                    pixels.position(row.toLong() * rgba.linesize(0)).get(bytes, row * rowBytes, rowBytes)
                                pixels.position(0)
                                owner.ensureActive()
                                val image = Image.makeRaster(ImageInfo(size.first, size.second, ColorType.RGBA_8888,
                                    ColorAlphaType.OPAQUE), bytes, rowBytes)
                                onFrame(image.toComposeImageBitmap())
                                lastPublished = now
                            } finally { av_frame_unref(decoded) }
                        }
                        if (pending && !drained) throw VideoDecodeException(VideoFailure.DECODE)
                    }
                } finally { av_packet_unref(packet) }
            }
            owner.ensureActive()
        } finally {
            if (scaler != null && !scaler.isNull) sws_freeContext(scaler)
            if (rgba != null && !rgba.isNull) av_frame_free(rgba)
            if (decoded != null && !decoded.isNull) av_frame_free(decoded)
            if (packet != null && !packet.isNull) av_packet_free(packet)
            if (codec != null && !codec.isNull) avcodec_free_context(codec)
            if (input != null && !input.isNull) {
                if (opened) avformat_close_input(input) else avformat_free_context(input)
            }
            av_dict_free(options)
            interrupt.close()
        }
    }
}
