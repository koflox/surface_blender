package com.koflox.surfaceblender.blender

import android.opengl.GLES32

internal class TextureGLES(var handle: Int = -1, var target: Int = GLES32.GL_TEXTURE_2D, val name: String)