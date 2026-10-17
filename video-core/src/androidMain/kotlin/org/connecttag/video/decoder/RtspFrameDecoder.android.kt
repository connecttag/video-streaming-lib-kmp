package org.connecttag.video.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.connecttag.video.model.VideoDecodeException
import org.connecttag.video.model.VideoFailure
import org.connecttag.video.model.VideoPlaybackPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resumeWithException

/**
 * Global context holder for the Android video decoder when using parameterless factory.
 */
object AndroidVideoContext {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    var context: Context? = null
}

actual fun createRtspFrameDecoder(): RtspFrameDecoder = AndroidRtspFrameDecoder()

fun createAndroidRtspFrameDecoder(context: Context): RtspFrameDecoder = AndroidRtspFrameDecoder(context)

class AndroidRtspFrameDecoder(private val explicitContext: Context? = null) : RtspFrameDecoder {
    override suspend fun decode(uri: String, onFrame: (ImageBitmap) -> Unit) {
        val thread = HandlerThread("ConnectTagRtspDecoder").apply { start() }
        val handler = Handler(thread.looper)
        val disposed = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { result ->
                val session = AndroidRtspSession(explicitContext, thread, handler, result, onFrame) { disposed.complete(Unit) }
                result.invokeOnCancellation { handler.post { session.close() } }
                if (!handler.post { if (result.isActive) session.start(uri) else session.close() }) {
                    disposed.complete(Unit)
                    if (result.isActive) result.resumeWithException(VideoDecodeException(VideoFailure.DECODER_UNAVAILABLE))
                }
            }
        } finally {
            withContext(NonCancellable) { disposed.await() }
        }
    }
}

/** Media3 owns RTSP/RTP and MediaCodec; an offscreen EGL surface feeds Compose ImageBitmap frames. */
@androidx.annotation.OptIn(UnstableApi::class)
private class AndroidRtspSession(
    private val explicitContext: Context?,
    private val thread: HandlerThread,
    private val handler: Handler,
    private val result: CancellableContinuation<Unit>,
    private val onFrame: (ImageBitmap) -> Unit,
    private val onClosed: () -> Unit
) {
    private var player: ExoPlayer? = null
    private var output: AndroidVideoFrameSurface? = null
    private var closed = false

    fun start(uri: String) {
        try {
            val context = explicitContext ?: AndroidVideoContext.context
                ?: throw VideoDecodeException(VideoFailure.DECODER_UNAVAILABLE)
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            val target = AndroidVideoFrameSurface(handler, onFrame) { fail(VideoFailure.DECODE) }
            output = target
            target.initialize()
            val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(250, 1500, 100, 250).build()
            val next = ExoPlayer.Builder(context.applicationContext).setLooper(handler.looper)
                .setLoadControl(loadControl).build()
            player = next
            next.volume = 0f
            next.trackSelectionParameters = next.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            next.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (closed) return
                    try { target.resize(videoSize.width, videoSize.height) }
                    catch (_: Exception) { fail(VideoFailure.DECODE) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    val authentication = generateSequence<Throwable>(error) { it.cause }.take(8).any {
                        VideoPlaybackPolicy.authenticationRejected(it.message)
                    }
                    fail(when {
                        authentication -> VideoFailure.AUTHENTICATION
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> VideoFailure.DECODER_UNAVAILABLE
                        error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) -> VideoFailure.UNSUPPORTED_CODEC
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> VideoFailure.DECODE
                        else -> VideoFailure.NETWORK
                    })
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) fail(VideoFailure.NETWORK)
                }
            })
            next.setVideoSurface(target.surface)
            val source = RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .setTimeoutMs(VideoPlaybackPolicy.READ_TIMEOUT_MS)
                .createMediaSource(MediaItem.fromUri(uri))
            next.setMediaSource(source)
            next.prepare()
            next.playWhenReady = true
        } catch (error: VideoDecodeException) { fail(error.failure) }
          catch (_: LinkageError) { fail(VideoFailure.DECODER_UNAVAILABLE) }
          catch (_: Exception) { fail(VideoFailure.DECODE) }
    }

    private fun fail(failure: VideoFailure) {
        if (closed) return
        close()
        if (result.isActive) result.resumeWithException(VideoDecodeException(failure))
    }

    fun close() {
        if (closed) return
        closed = true
        try { player?.release() } catch (_: Exception) { }
        finally {
            player = null
            try { output?.close() } catch (_: Exception) { }
            finally { output = null; thread.quitSafely(); onClosed() }
        }
    }
}

/** All EGL and SurfaceTexture operations stay on the decoder's handler thread. */
private class AndroidVideoFrameSurface(
    private val handler: Handler,
    private val onFrame: (ImageBitmap) -> Unit,
    private val onFailure: () -> Unit
) {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var config: EGLConfig? = null
    private var textureId = 0
    private var program = 0
    private var texture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    val surface: Surface get() = requireNotNull(outputSurface)
    private var width = 0
    private var height = 0
    private var readback = ByteBuffer.allocateDirect(4)
    private var topDown = ByteBuffer.allocateDirect(4)
    private var row = ByteArray(4)
    private var lastFrameAt = Long.MIN_VALUE / 2
    private var closed = false
    private val transform = FloatArray(16)
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0))
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        makePbuffer(1, 1)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 position; attribute vec2 texCoord; uniform mat4 texMatrix; varying vec2 uv; void main(){gl_Position=position;uv=(texMatrix*vec4(texCoord,0.0,1.0)).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES video; varying vec2 uv; void main(){gl_FragColor=texture2D(video,uv);}")
        try {
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
            val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0); check(linked[0] != 0)
        } finally { GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment) }
        texture = SurfaceTexture(textureId).also { it.setOnFrameAvailableListener({ render() }, handler) }
        outputSurface = Surface(requireNotNull(texture))
    }

    fun resize(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val size = VideoPlaybackPolicy.outputSize(sourceWidth, sourceHeight)
        if (size == (width to height)) return
        makePbuffer(size.first, size.second)
        width = size.first; height = size.second
        readback = ByteBuffer.allocateDirect(width * height * 4)
        topDown = ByteBuffer.allocateDirect(width * height * 4)
        row = ByteArray(width * 4)
    }

    private fun makePbuffer(width: Int, height: Int) {
        val next = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        check(next != EGL14.EGL_NO_SURFACE)
        if (!EGL14.eglMakeCurrent(display, next, next, context)) {
            EGL14.eglDestroySurface(display, next)
            throw VideoDecodeException(VideoFailure.DECODE)
        }
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        pbuffer = next
    }

    private fun render() {
        if (closed) return
        try {
            val source = texture ?: return
            source.updateTexImage()
            val now = android.os.SystemClock.elapsedRealtime()
            if (width <= 0 || height <= 0 || now - lastFrameAt < VideoPlaybackPolicy.FRAME_INTERVAL_MS) return
            source.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, width, height); GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "texMatrix"), 1, false, transform, 0)
            val position = GLES20.glGetAttribLocation(program, "position")
            val uv = GLES20.glGetAttribLocation(program, "texCoord")
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
            vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices)
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            readback.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            topDown.clear()
            for (y in height - 1 downTo 0) { readback.position(y * row.size); readback.get(row); topDown.put(row) }
            topDown.flip()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(topDown)
            onFrame(bitmap.asImageBitmap())
            lastFrameAt = now
        } catch (_: Exception) { onFailure() }
    }

    private fun shader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code); GLES20.glCompileShader(shader)
        val compiled = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) { GLES20.glDeleteShader(shader); throw VideoDecodeException(VideoFailure.DECODE) }
        return shader
    }

    fun close() {
        if (closed) return
        closed = true
        texture?.setOnFrameAvailableListener(null)
        outputSurface?.release(); outputSurface = null
        texture?.release(); texture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }
}
