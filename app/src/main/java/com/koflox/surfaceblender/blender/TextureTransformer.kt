package com.koflox.surfaceblender.blender

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES32
import android.opengl.GLUtils
import android.renderscript.Matrix4f
import android.util.Log
import com.koflox.surfaceblender.debugLog
import com.koflox.surfaceblender.egl.EglCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Displays a foreground bitmap on video stream using supplied mask cropping video frame.
 *
 * @param mode defines whether drawing is done as fast as possible (RECORDING) or keeps 60 fps (LIVE)
 * @param window Surface, SurfaceView, SurfaceTexture or SurfaceHolder
 * @param data video params, foreground, mask etc.
 * @param viewportWidth viewport width for GL
 * @param viewportHeight viewport height for GL
 */
class TextureTransformer(
    mode: EglCore.Mode,
    window: Any,
    private val data: TransformerData,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val callback: TextureTransformerCallback
) {

    companion object {
        val TAG = TextureTransformer::class.java.simpleName
        private val lock = ReentrantLock()
    }

    interface TextureTransformerCallback {
        fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture)

        @JvmDefault
        fun onReadyRenderFrame() = Unit

        @JvmDefault
        fun onFrameRendered() = Unit
    }

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
    internal var isRunning = true
        private set

    init {
        thread(name = "TextureTransformerThread") {
            EglCore.initEglBundle(mode, window)
            initGLES()
            while (isRunning) {
                if (isDrawn()) {
                    callback.onReadyRenderFrame()
                    lock.withLock {
                        EglCore.makeCurrent(mode)
                        EglCore.swapBuffers(mode)
                    }
                    callback.onFrameRendered()
                }
                when (mode) {
                    EglCore.Mode.LIVE -> Thread.sleep(16)
                    EglCore.Mode.RECORDING -> Unit
                }
            }
            releaseGLES()
            EglCore.releaseEglBundle(mode)
            debugLog(TAG, "Release TextureTransformer")
        }
    }

    fun release() {
        isRunning = false
    }

    protected fun finalize() = release()

    private fun initGLES() {
        setupVertexBuffer()
        setupTexture()
        textures.add(TextureGLES(loadTexture(data.foreground), name = TEXTURE_FOREGROUND_NAME))
        textures.add(TextureGLES(loadTexture(data.mask), name = TEXTURE_MASK_NAME))
        loadShaders()
        callback.onSurfaceTextureReady(videoTexture)
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
            val videoAspectRatio = data.videoWidth.toFloat() / data.videoHeight
            val skyHeight = viewportHeight * (1 - data.bottomPadding)
            val roiAspectRatio = viewportWidth.toFloat() / skyHeight
            when {
                videoAspectRatio < roiAspectRatio -> { // cut height
                    val videoDesiredHeight = viewportWidth * (data.videoHeight.toFloat() / data.videoWidth)
                    val sy = viewportHeight / videoDesiredHeight
                    val bottomPadding = data.bottomPadding * viewportHeight
                    val dy = -bottomPadding / videoDesiredHeight
                    translate(0F, dy, 0F)
                    scale(1F, sy, 1F)
                }
                else -> { // cut width
                    val videoDesiredWidth = skyHeight * videoAspectRatio
                    val sy = viewportHeight / skyHeight
                    val dx = viewportWidth / videoDesiredWidth
                    val dy = -data.bottomPadding * sy
                    translate((1 - dx) / 2, dy, 0F)
                    scale(dx, sy, 1F)
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
            setOnFrameAvailableListener {
                synchronized(this@TextureTransformer) {
                    frameAvailable = true
                }
            }
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