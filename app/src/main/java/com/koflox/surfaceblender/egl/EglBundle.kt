package com.koflox.surfaceblender.egl

import android.opengl.EGLContext
import android.opengl.EGLSurface

internal class EglBundle(
    var eglWindow: Any? = null,
    var eglContext: EGLContext? = null,
    var eglSurface: EGLSurface? = null
)