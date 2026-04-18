package com.procam.s23fe.gpu

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-side renderer that draws the camera OES texture into one or more output surfaces,
 * applying a 3D colour LUT on the way.
 *
 * Pipeline:
 *   Camera Frame (OES) ──► fragment shader ──► LUT(3D tex) ──► Preview SurfaceTexture
 *                                                        └──► MediaRecorder Surface
 *
 * This renderer does NOT own EGL / threading — that lives in [LutRenderThread] so the
 * class stays pure and easy to test.
 */
class LutEngine(private val context: Context) {

    companion object { private const val TAG = "LutEngine" }

    // region Shader

    private val vertexShader = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShader = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        uniform samplerExternalOES uCameraTex;
        uniform sampler3D uLutTex;
        uniform float uLutIntensity; // 0.0 .. 1.0
        uniform float uLutSize;      // e.g. 33.0
        in vec2 vTexCoord;
        out vec4 fragColor;

        void main() {
            vec3 src = texture(uCameraTex, vTexCoord).rgb;

            // Clamp + scale to 3D-LUT sample coordinates, with half-texel centering.
            float s = (uLutSize - 1.0) / uLutSize;
            float o = 1.0 / (2.0 * uLutSize);
            vec3 coord = clamp(src, 0.0, 1.0) * s + o;

            vec3 graded = texture(uLutTex, coord).rgb;
            fragColor = vec4(mix(src, graded, clamp(uLutIntensity, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()

    // endregion

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCameraTex = 0
    private var uLutTex = 0
    private var uLutIntensity = 0
    private var uLutSize = 0

    private var cameraTexId = 0
    private var lutTexId = 0
    private var lutSize = 33

    private var intensity: Float = 1.0f

    private val vertexBuffer: FloatBuffer
    private val texBuffer: FloatBuffer

    // Fullscreen quad
    private val quadVerts = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    // Matches preview orientation — can be transformed by uTexMatrix
    private val quadTex = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    init {
        vertexBuffer = ByteBuffer.allocateDirect(quadVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVerts); position(0) }
        texBuffer = ByteBuffer.allocateDirect(quadTex.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadTex); position(0) }
    }

    /** Must be called on the render thread with a current EGL context. */
    fun initGl() {
        program = linkProgram(vertexShader, fragmentShader)
        aPosition    = GLES30.glGetAttribLocation(program, "aPosition")
        aTexCoord    = GLES30.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix   = GLES30.glGetUniformLocation(program, "uTexMatrix")
        uCameraTex   = GLES30.glGetUniformLocation(program, "uCameraTex")
        uLutTex      = GLES30.glGetUniformLocation(program, "uLutTex")
        uLutIntensity= GLES30.glGetUniformLocation(program, "uLutIntensity")
        uLutSize     = GLES30.glGetUniformLocation(program, "uLutSize")

        cameraTexId = createOesTexture()
        lutTexId    = createLut3D(LutCatalog.identity(lutSize), lutSize)
    }

    /** Create a [SurfaceTexture] backed by our OES texture id (feed it to Camera2). */
    fun newCameraSurfaceTexture(): SurfaceTexture = SurfaceTexture(cameraTexId)

    fun setLut(lut: FloatArray, size: Int) {
        lutSize = size
        GLES30.glDeleteTextures(1, intArrayOf(lutTexId), 0)
        lutTexId = createLut3D(lut, size)
    }

    fun setIntensity(v: Float) { intensity = v.coerceIn(0f, 1f) }

    fun updateTexMatrix(matrix: FloatArray) { System.arraycopy(matrix, 0, texMatrix, 0, 16) }

    /** Draw one frame to the currently bound EGL surface. */
    fun drawFrame(viewportW: Int, viewportH: Int) {
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES30.glUniform1i(uCameraTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
        GLES30.glUniform1i(uLutTex, 1)

        GLES30.glUniform1f(uLutIntensity, intensity)
        GLES30.glUniform1f(uLutSize, lutSize.toFloat())
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES30.glEnableVertexAttribArray(aPosition)
        GLES30.glVertexAttribPointer(aPosition, 2, GLES30.GL_FLOAT, false, 0, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexCoord)
        GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 0, texBuffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosition)
        GLES30.glDisableVertexAttribArray(aTexCoord)
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        if (cameraTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(cameraTexId), 0)
        if (lutTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexId), 0)
        program = 0; cameraTexId = 0; lutTexId = 0
    }

    // region GL helpers

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S,     GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T,     GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createLut3D(lutRgb: FloatArray, size: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S,     GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T,     GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R,     GLES30.GL_CLAMP_TO_EDGE)

        val buf = ByteBuffer.allocateDirect(lutRgb.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(lutRgb); position(0) }
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F,
            size, size, size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buf
        )
        return ids[0]
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw RuntimeException("Program link failed: $log")
        }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, src); GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(id)
            GLES30.glDeleteShader(id)
            throw RuntimeException("Shader compile failed: $log\n$src")
        }
        return id
    }

    // endregion
}
