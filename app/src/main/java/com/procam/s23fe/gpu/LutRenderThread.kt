package com.procam.s23fe.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.view.Surface

/**
 * Owns the EGL context + a [LutEngine] and dispatches frames to both the
 * preview surface (SurfaceView) and, when recording, the MediaRecorder surface.
 *
 * Every buffer that comes off the camera's OES SurfaceTexture is drawn once per
 * output surface using the same LUT, ensuring what-you-see-is-what-you-record.
 */
class LutRenderThread(
    private val engine: LutEngine,
) : HandlerThread("LutRenderThread"), SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "LutRenderThread"
        private const val MSG_INIT = 1
        private const val MSG_FRAME = 2
        private const val MSG_SET_PREVIEW = 3
        private const val MSG_SET_RECORD = 4
        private const val MSG_RELEASE = 5
    }

    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var previewSurface: Surface? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewW = 0; private var previewH = 0

    private var recordSurface: Surface? = null
    private var recordEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var recordW = 0; private var recordH = 0

    private var cameraTex: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)

    fun startRender() {
        start()
        handler = Handler(looper, ::handle)
        handler!!.sendEmptyMessage(MSG_INIT)
    }

    /** After initGl, use this to create the camera OES SurfaceTexture. Safe to call from any thread. */
    fun cameraSurfaceTexture(): SurfaceTexture {
        // Must be created on render thread, so we block-post
        val result = arrayOfNulls<SurfaceTexture>(1)
        val lock = Object()
        handler?.post {
            if (cameraTex == null) {
                cameraTex = engine.newCameraSurfaceTexture().apply {
                    setOnFrameAvailableListener(this@LutRenderThread)
                }
            }
            synchronized(lock) { result[0] = cameraTex; lock.notify() }
        } ?: error("startRender() first")
        synchronized(lock) { while (result[0] == null) lock.wait() }
        return result[0]!!
    }

    fun setPreviewSurface(s: Surface?, w: Int, h: Int) {
        handler?.obtainMessage(MSG_SET_PREVIEW, w, h, s)?.sendToTarget()
    }

    fun setRecordSurface(s: Surface?, w: Int, h: Int) {
        handler?.obtainMessage(MSG_SET_RECORD, w, h, s)?.sendToTarget()
    }

    fun release() { handler?.sendEmptyMessage(MSG_RELEASE) }

    override fun onFrameAvailable(st: SurfaceTexture) {
        handler?.sendEmptyMessage(MSG_FRAME)
    }

    // region handler

    private fun handle(msg: Message): Boolean {
        when (msg.what) {
            MSG_INIT -> initEgl()
            MSG_SET_PREVIEW -> onSetPreview(msg.obj as? Surface, msg.arg1, msg.arg2)
            MSG_SET_RECORD  -> onSetRecord(msg.obj as? Surface, msg.arg1, msg.arg2)
            MSG_FRAME -> drawFrame()
            MSG_RELEASE -> doRelease()
        }
        return true
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(eglDisplay, v, 0, v, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x40, // include ES3
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, n, 0)
        eglConfig = configs[0]

        val ctxAttrs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0
        )

        // Pbuffer to make context current before any real surface exists
        val pb = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pb, pb, eglContext)
        engine.initGl()
    }

    private fun onSetPreview(s: Surface?, w: Int, h: Int) {
        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previewEgl)
            previewEgl = EGL14.EGL_NO_SURFACE
        }
        previewSurface = s; previewW = w; previewH = h
        if (s != null) {
            previewEgl = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, s, intArrayOf(EGL14.EGL_NONE), 0
            )
        }
    }

    private fun onSetRecord(s: Surface?, w: Int, h: Int) {
        if (recordEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, recordEgl)
            recordEgl = EGL14.EGL_NO_SURFACE
        }
        recordSurface = s; recordW = w; recordH = h
        if (s != null) {
            recordEgl = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, s, intArrayOf(EGL14.EGL_NONE), 0
            )
        }
    }

    private fun drawFrame() {
        val st = cameraTex ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        engine.updateTexMatrix(texMatrix)

        if (previewEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, previewEgl, previewEgl, eglContext)
            engine.drawFrame(previewW, previewH)
            EGL14.eglSwapBuffers(eglDisplay, previewEgl)
        }
        if (recordEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, recordEgl, recordEgl, eglContext)
            engine.drawFrame(recordW, recordH)
            EGL14.eglSwapBuffers(eglDisplay, recordEgl)
        }
    }

    private fun doRelease() {
        try { cameraTex?.release() } catch (_: Exception) {}
        engine.release()
        if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, previewEgl)
        if (recordEgl  != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, recordEgl)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
        quitSafely()
    }

    // endregion
}
