package com.koflox.surfaceblender.blender

import android.opengl.EGLContext
import android.opengl.EGLSurface


class EglBundle(
    var eglWindow: Any? = null,
    var eglContext: EGLContext? = null,
    var eglSurface: EGLSurface? = null
)