package com.koflox.surfaceblender.egl

import android.opengl.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object EglCore {

    enum class Mode {
        LIVE,
        RECORDING
    }

    const val TAG = "EglCore"

    private const val DEFAULT_RELEASE_COLOR_RED = 0F
    private const val DEFAULT_RELEASE_COLOR_GREEN = 0F
    private const val DEFAULT_RELEASE_COLOR_BLUE = 0F
    private const val DEFAULT_RELEASE_COLOR_ALPHA = 1F

    var releaseColorRed = DEFAULT_RELEASE_COLOR_RED
    var releaseColorGreen = DEFAULT_RELEASE_COLOR_GREEN
    var releaseColorBlue = DEFAULT_RELEASE_COLOR_BLUE
    var releaseColorAlpha = DEFAULT_RELEASE_COLOR_ALPHA

    private val eglBundles = HashMap<Mode, EglBundle>()

    private var eglDisplay: EGLDisplay? = null

    private val config: EGLConfig? by lazy(LazyThreadSafetyMode.NONE) { getEglConfig() }

    private val lock = ReentrantLock()

    internal fun makeCurrent(mode: Mode) {
        val eglBundle = eglBundles[mode]
        val eglSurface = eglBundle?.eglSurface
        val eglContext = eglBundle?.eglContext
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    internal fun swapBuffers(mode: Mode) {
        val eglSurface = eglBundles[mode]?.eglSurface
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    internal fun initEglBundle(mode: Mode, window: Any) {
        lock.withLock {
            initEglDisplay()

            val eglContext = createContext(
                eglDisplay,
                config
            )
            val eglSurface =
                EGL14.eglCreateWindowSurface(
                    eglDisplay,
                    config, window, intArrayOf(EGL14.EGL_NONE), 0
                )

            check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                "GL Error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
            }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "GL Make current error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
            }

            eglBundles[mode] =
                EglBundle(window, eglContext, eglSurface)
        }
    }

    internal fun releaseEglBundle(mode: Mode) {
        lock.withLock {
            val eglBundle = eglBundles[mode]
            val eglSurface = eglBundle?.eglSurface
            val eglContext = eglBundle?.eglContext
            eglBundles.remove(mode)

            GLES32.glClearColor(
                releaseColorRed,
                releaseColorGreen,
                releaseColorBlue,
                releaseColorAlpha
            )
            GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)

            releaseEglDisplay()
        }
    }

    private fun initEglDisplay() {
        if (eglBundles.isEmpty()) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        }
    }

    private fun releaseEglDisplay() {
        if (eglBundles.isEmpty())
            EGL14.eglTerminate(eglDisplay)
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

    private fun createContext(eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext {
        val attribList = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
    }

}