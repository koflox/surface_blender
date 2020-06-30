package com.koflox.surfaceblender.blender

import android.opengl.*

/**
 * EGL™ is an interface between Khronos rendering APIs such as OpenGL ES or OpenVG
 * and the underlying native platform window system. It handles graphics context
 * management, surface/buffer binding, and rendering synchronization and enables
 * high-performance, accelerated, mixed-mode 2D and 3D rendering using other
 * Khronos APIs.
 *
 * Documentation: https://www.khronos.org/registry/EGL/sdk/docs/man/
 */
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

    init {
        // eglGetDisplay obtains the EGL display connection for the native display native_display.
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        // eglInitialize initialized the EGL display connection obtained with eglGetDisplay
        // EGL_FALSE is returned if eglInitialize fails, EGL_TRUE otherwise
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
    }

    fun makeCurrent(mode: Mode) {
        val eglBundle = eglBundles[mode]
        val eglSurface = eglBundle?.eglSurface
        val eglContext = eglBundle?.eglContext
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun swapBuffers(mode: Mode) {
        // post EGL surface color buffer to a native window
        val eglSurface = eglBundles[mode]?.eglSurface
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Finds a suitable EGLConfig.
     */
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
        // eglChooseConfig returns in configs a list of all EGL frame buffer configurations that match the attributes specified in attrib_list
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay, attrList, 0, configs, 0, configs.size,
            numConfigs, 0
        )
        return configs[0]
    }

    fun initEglBundle(mode: Mode, window: Any) {
        // eglCreateContext creates an EGL rendering context for the current rendering API
        val eglContext = createContext(eglDisplay, config)
        // eglCreateWindowSurface creates an on-screen EGL window surface
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, window, intArrayOf(EGL14.EGL_NONE), 0)

        check(eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            "GL Error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
        }
        // eglMakeCurrent binds context to the current rendering thread and to the draw and read surfaces.
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "GL Make current error: ${GLUtils.getEGLErrorString(EGL14.eglGetError())}"
        }

        eglBundles[mode] = EglBundle(window, eglContext, eglSurface)
    }

    fun releaseEglBundle(mode: Mode) {
        val eglBundle = eglBundles[mode]
        val eglSurface = eglBundle?.eglSurface
        val eglContext = eglBundle?.eglContext
        eglBundles.remove(mode)

        GLES32.glClearColor(releaseColorRed, releaseColorGreen, releaseColorBlue, releaseColorAlpha)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        // If the EGL surface surface is not current to any thread, eglDestroySurface destroys it immediately.
        // Otherwise, surface is destroyed when it becomes not current to any thread
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        // If the EGL rendering context context is not current to any thread, eglDestroyContext destroys it immediately.
        // Otherwise, context is destroyed when it becomes not current to any thread.
        EGL14.eglDestroyContext(eglDisplay, eglContext)
    }

    /**
     * eglTerminate releases resources associated with an EGL display connection
     */
    fun releaseEglDisplay() = EGL14.eglTerminate(eglDisplay)

    private fun createContext(eglDisplay: EGLDisplay?, eglConfig: EGLConfig?): EGLContext {
        val attribList = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, attribList, 0)
    }

}