package com.yegorkapomidorka.headswap.aa

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Surface -> OpenGL -> Surface: декодер AA рисует в SurfaceTexture, GL-нить копирует кадр на Surface входа
 * энкодера (копия на GPU, без ByteBuffer) и сама перерисовывает последний кадр, если новых нет idleRedrawMs.
 * Нужна потому, что встроенный KEY_REPEAT_PREVIOUS_FRAME_AFTER в Android повторяет кадр всего ~10 раз
 * (kRepeatLastFrameCount) и умолкает: на статичном экране AA через секунду ГУ остался бы без кадров.
 */
class GlRelay(
    private val outSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val idleRedrawMs: Long = 100,
    private val log: (String) -> Unit = {},
) {
    private val thread = HandlerThread("aa-gl-relay")
    private lateinit var handler: Handler
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var st: SurfaceTexture? = null
    private var aPos = 0
    private var aTex = 0
    private var uMatrix = 0
    private val tm = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }
    @Volatile private var haveFrame = false
    @Volatile private var released = false
    private var lastDrawNs = 0L
    @Volatile private var initError: Throwable? = null

    /** Сюда декодер выводит кадры. */
    lateinit var inputSurface: Surface
        private set

    val fresh = AtomicLong()
    val redraws = AtomicLong()

    fun start() {
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                init()
            } catch (t: Throwable) {
                initError = t
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("GL-нить не поднялась")
        initError?.let { throw IllegalStateException("инициализация GL: ${it.message}", it) }
        handler.postDelayed(tick, 20)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (haveFrame && SystemClock.elapsedRealtimeNanos() - lastDrawNs >= idleRedrawMs * 1_000_000) {
                draw()
                redraws.incrementAndGet()
            }
            handler.postDelayed(this, 20)
        }
    }

    /** Новая AA-сессия: старый кадр больше не повторять до первого нового. */
    fun clear() {
        haveFrame = false
    }

    fun stop() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(tick)
                st?.release()
                inputSurface.release()
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (t: Throwable) {
                log("GlRelay.stop: ${t.message}")
            }
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val cfgAttrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val s = SurfaceTexture(texId)
        s.setDefaultBufferSize(width, height)
        s.setOnFrameAvailableListener({ onFrame() }, handler)
        st = s
        inputSurface = Surface(s)
        android.opengl.Matrix.setIdentityM(tm, 0)
    }

    private fun onFrame() {
        if (released) return
        try {
            st?.updateTexImage()
            st?.getTransformMatrix(tm)
            haveFrame = true
            fresh.incrementAndGet()
            draw()
        } catch (t: Throwable) {
            log("GlRelay.onFrame: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun draw() {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, tm, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        lastDrawNs = SystemClock.elapsedRealtimeNanos()
        // timestamp кадра для энкодера: монотонное время, как у surface-источников
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "шейдер: ${GLES20.glGetShaderInfoLog(sh)}" }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "линковка: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VERTEX = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
