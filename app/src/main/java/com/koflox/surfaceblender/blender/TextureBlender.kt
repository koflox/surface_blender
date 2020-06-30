package com.koflox.surfaceblender.blender

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.GLES11Ext
import android.opengl.GLES32
import android.opengl.GLUtils
import android.renderscript.Matrix4f
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.concurrent.thread
import kotlin.math.max

class TextureBlender(
    mode: EglCore.Mode,
    window: Any,
    private val videoWidth: Int, private val videoHeight: Int,
    private val viewportWidth: Int, private val viewportHeight: Int,
    private val foreground: Bitmap, private val mask: Bitmap,
    private val onInitialized: (SurfaceTexture) -> Unit
) : OnFrameAvailableListener {

    private val textures = mutableListOf<TextureGLES>()

    private var textureBuffer: FloatBuffer? = null
    private var shaderProgram = 0
    private var vertexBuffer: FloatBuffer? = null
    private var drawListBuffer: ShortBuffer? = null
    private lateinit var videoTexture: SurfaceTexture
    private val videoTextureTransform: FloatArray = FloatArray(16)
    private val backgroundTextureTransformationMatrix = Matrix4f()
    private var frameAvailable = false
    private var vpWidth = viewportWidth
    private var vpHeight = viewportHeight
    private var adjustViewport = false

    @Volatile
    private var isRunning = true

    init {
        thread {
            EglCore.initEglBundle(mode, window)
            initGLES()
            while (isRunning) {
                EglCore.makeCurrent(mode)
                val startTime = System.currentTimeMillis()
                if (isDrawn()) EglCore.swapBuffers(mode)
                when (mode) {
                    EglCore.Mode.LIVE -> {
                        val sleepTime = max(0, 16 - (System.currentTimeMillis() - startTime))
                        Thread.sleep(sleepTime)
                    }
                    else -> {
                    }
                }
            }
            releaseGLES()
            EglCore.releaseEglBundle(mode)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) = synchronized(this) {
        frameAvailable = true
    }

    protected fun finalize() = release()

    @Suppress("unused")
    fun setViewport(width: Int, height: Int) {
        vpWidth = width
        vpHeight = height
        adjustViewport = true
    }

    fun release() {
        isRunning = false
    }

    private fun initGLES() {
        setupVertexBuffer()
        setupTexture()
        textures.add(TextureGLES(loadTexture(foreground), name = TEXTURE_FOREGROUND_NAME))
        textures.add(TextureGLES(loadTexture(mask), name = TEXTURE_MASK_NAME))
        loadShaders()
        onInitialized.invoke(videoTexture)
    }

    private fun releaseGLES() {
        GLES32.glDeleteTextures(textures.size, textures.map(TextureGLES::handle).toIntArray(), 0)
        GLES32.glDeleteProgram(shaderProgram)
        videoTexture.setOnFrameAvailableListener(null)
        videoTexture.release()
    }

    private fun isDrawn(): Boolean {
        synchronized(this) {
            frameAvailable = when {
                frameAvailable -> {
                    videoTexture.updateTexImage()
                    videoTexture.getTransformMatrix(videoTextureTransform)
                    adjustBackgroundTexture()
                    false
                }
                else -> return false
            }
        }
        if (adjustViewport) adjustViewport()

        GLES32.glUseProgram(shaderProgram)

        val textureCoordinateHandle =
            GLES32.glGetAttribLocation(shaderProgram, VERTEX_TEXTURE_COORDINATE)
        val positionHandle = GLES32.glGetAttribLocation(shaderProgram, VERTEX_POSITION)
        val textureTranformHandle =
            GLES32.glGetUniformLocation(shaderProgram, VERTEX_COORDINATE_MATRIX)
        val backgroundTextureTransformHandle =
            GLES32.glGetUniformLocation(shaderProgram, SOURCE_COORDINATE_MATRIX)

        GLES32.glEnableVertexAttribArray(positionHandle)
        GLES32.glVertexAttribPointer(positionHandle, 3, GLES32.GL_FLOAT, false, 4 * 3, vertexBuffer)

        textures.forEachIndexed { index, textureGLES ->
            GLES32.glActiveTexture(GLES32.GL_TEXTURE0 + index)
            GLES32.glBindTexture(textureGLES.target, textureGLES.handle)
            GLES32.glUniform1i(GLES32.glGetUniformLocation(shaderProgram, textureGLES.name), index)
        }

        GLES32.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES32.glVertexAttribPointer(
            textureCoordinateHandle,
            4,
            GLES32.GL_FLOAT,
            false,
            0,
            textureBuffer
        )
        GLES32.glUniformMatrix4fv(textureTranformHandle, 1, false, videoTextureTransform, 0)
        GLES32.glUniformMatrix4fv(
            backgroundTextureTransformHandle,
            1,
            false,
            backgroundTextureTransformationMatrix.array,
            0
        )

        GLES32.glDrawElements(
            GLES32.GL_TRIANGLES,
            DRAW_ORDER.size,
            GLES32.GL_UNSIGNED_SHORT,
            drawListBuffer
        )
        GLES32.glDisableVertexAttribArray(positionHandle)
        GLES32.glDisableVertexAttribArray(textureCoordinateHandle)
        return true
    }

    private fun adjustBackgroundTexture() {
        System.arraycopy(
            videoTextureTransform,
            0,
            backgroundTextureTransformationMatrix.array,
            0,
            backgroundTextureTransformationMatrix.array.size
        )
        backgroundTextureTransformationMatrix.apply {
            val videoAspectRatio = videoWidth.toFloat() / videoHeight
            val viewportAspectRatio = viewportWidth.toFloat() / viewportHeight
            when {
                videoAspectRatio > viewportAspectRatio -> {
                    val videoDesiredWidth = viewportHeight * videoAspectRatio
                    val dx = viewportWidth / videoDesiredWidth
                    translate((1 - dx) / 2, 0F, 0F)
                    scale(dx, 1F, 1F)
                }
                videoAspectRatio < viewportAspectRatio -> {
                    val videoDesiredHeight = viewportWidth * (videoHeight.toFloat() / videoWidth)
                    val dy = viewportHeight / videoDesiredHeight
                    translate(0F, (1 - dy) / 2, 0F)
                    scale(1F, dy, 1F)
                }
            }
        }
    }

    private fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = kotlin.run {
            val textureHandle = IntArray(1)
            GLES32.glGenTextures(1, textureHandle, 0)
            textureHandle[0].apply {
                require(this != 0) { "Error loading texture." }
            }
        }
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureHandle)
        GLES32.glTexParameteri(
            GLES32.GL_TEXTURE_2D,
            GLES32.GL_TEXTURE_MIN_FILTER,
            GLES32.GL_NEAREST
        )
        GLES32.glTexParameteri(
            GLES32.GL_TEXTURE_2D,
            GLES32.GL_TEXTURE_MAG_FILTER,
            GLES32.GL_NEAREST
        )
        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return textureHandle
    }

    private fun loadShaders() {
        val vertexShaderHandle = GLES32.glCreateShader(GLES32.GL_VERTEX_SHADER)
        GLES32.glShaderSource(vertexShaderHandle, VERTEX_SHADER_CODE)
        GLES32.glCompileShader(vertexShaderHandle)
        checkGlError("compile vertex shader")

        val fragmentShaderHandle = GLES32.glCreateShader(GLES32.GL_FRAGMENT_SHADER)
        GLES32.glShaderSource(fragmentShaderHandle, FRAGMENT_SHADER_CODE)
        GLES32.glCompileShader(fragmentShaderHandle)
        checkGlError("compile pixel shader")

        shaderProgram = GLES32.glCreateProgram()
        GLES32.glAttachShader(shaderProgram, vertexShaderHandle)
        GLES32.glAttachShader(shaderProgram, fragmentShaderHandle)
        GLES32.glLinkProgram(shaderProgram)
        checkGlError("compile shader program")

        val status = IntArray(1)
        GLES32.glGetProgramiv(shaderProgram, GLES32.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES32.GL_TRUE) {
            val error = GLES32.glGetProgramInfoLog(shaderProgram)
            Log.e("SurfaceTest", "Error while linking program:\n$error")
        }
    }

    private fun setupVertexBuffer() {
        drawListBuffer = ByteBuffer.allocateDirect(DRAW_ORDER.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(DRAW_ORDER)
                position(0)
            }
        }
        vertexBuffer = ByteBuffer.allocateDirect(SQUARE_COORDINATES.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(SQUARE_COORDINATES)
                position(0)
            }
        }
    }

    private fun setupTexture() {
        textureBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDINATES.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(TEXTURE_COORDINATES)
                position(0)
            }
        }

        textures.add(
            TextureGLES(
                target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                name = TEXTURE_SOURCE_NAME
            )
        )
        val sourceTexture = textures.first()

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glGenTextures(1, intArrayOf(sourceTexture.handle), 0)
        checkGlError("generate source texture")

        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTexture.handle)
        checkGlError("bind source texture")

        videoTexture = SurfaceTexture(sourceTexture.handle).apply {
            setOnFrameAvailableListener(this@TextureBlender)
        }
    }

    private fun adjustViewport() {
        GLES32.glViewport(0, 0, vpWidth, vpHeight)
        adjustViewport = false
    }

    private fun checkGlError(operation: String) {
        GLES32.glGetError().let { error ->
            if (error != GLES32.GL_NO_ERROR) {
                Log.e(EglCore.TAG, "glError while $operation: ${GLUtils.getEGLErrorString(error)}")
            }
        }
    }

}