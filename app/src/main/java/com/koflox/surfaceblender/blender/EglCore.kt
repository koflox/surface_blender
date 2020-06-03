package com.koflox.surfaceblender.blender

import android.opengl.*
import android.util.Log
import kotlin.concurrent.thread

abstract class EglCore(
    private val texture: Any
) : Runnable {

    companion object {
        const val TAG = "EglCore"
        private const val DEFAULT_RELEASE_COLOR_RED = 0F
        private const val DEFAULT_RELEASE_COLOR_GREEN = 0F
        private const val DEFAULT_RELEASE_COLOR_BLUE = 0F
        private const val DEFAULT_RELEASE_COLOR_ALPHA = 1F
    }

    var releaseColorRed = DEFAULT_RELEASE_COLOR_RED
    var releaseColorGreen = DEFAULT_RELEASE_COLOR_GREEN
    var releaseColorBlue = DEFAULT_RELEASE_COLOR_BLUE
    var releaseColorAlpha = DEFAULT_RELEASE_COLOR_ALPHA

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    @Volatile
    private var isRunning = true
    private var lastFpsOutput: Long = 0
    private var fpsCounter = 0

    init {
        thread {
            synchronized(EglCore::class.java) {
                run()
            }
        }
    }

    private val config: EGLConfig? by lazy(LazyThreadSafetyMode.NONE) { getEglConfig() }

    protected abstract fun initGLES()

    protected abstract fun releaseGLES()

    protected abstract fun onDraw(): Boolean

    override fun run() {
        initEglCore()
        initGLES()
        while (isRunning) {
            val loopStart = System.currentTimeMillis()
            pingFps()
            if (onDraw()) {
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
            val waitDelta = 16 - (System.currentTimeMillis() - loopStart)
            if (waitDelta > 0) {
                try {
                    Thread.sleep(waitDelta)
                } catch (e: InterruptedException) {
                }
            }
        }
        releaseGLES()
        releaseEglCore()
    }

    fun release() {
        isRunning = false
    }

    private fun getEglConfig(): EGLConfig? {
        val renderType = EGL14.EGL_OPENGL_ES2_BIT or EGLExt.EGL_OPENGL_ES3_BIT_KHR
        val attrList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderType,
            EGL14.EGL_NONE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay, attrList, 0, configs, 0, configs.size,
            numConfigs, 0
        )
        return configs[0]
    }

    private fun pingFps() {
        if (lastFpsOutput == 0L) lastFpsOutput = System.currentTimeMillis()
        fpsCounter++
        if (System.currentTimeMillis() - lastFpsOutput > 1000) {
            Log.d(TAG, "FPS: $fpsCounter")
            lastFpsOutput = System.currentTimeMillis()
            fpsCounter = 0
        }
    }

    private fun initEglCore() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        eglContext = createContext(eglDisplay, config)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, texture, intArrayOf(EGL14.EGL_NONE), 0)

        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "GL Error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
        }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "GL Make current error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
        }
    }

    private fun releaseEglCore() {
        clearTexture()
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    private fun clearTexture() {
        GLES32.glClearColor(releaseColorRed, releaseColorGreen, releaseColorBlue, releaseColorAlpha)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun createContext(eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext {
        val attribList = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
    }

    protected fun finalize() {
        isRunning = false
    }

}