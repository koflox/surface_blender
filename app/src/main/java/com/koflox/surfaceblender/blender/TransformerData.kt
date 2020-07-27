package com.koflox.surfaceblender.blender

import android.graphics.Bitmap

/**
 * DTO for texture TextureTransformer
 *
 * @param videoWidth video width
 * @param videoHeight video height
 * @param foreground image to be used as foreground on video playback
 * @param mask alpha bitmap which used to discard the previous unnecessary objects on foreground
 * @param bottomPadding padding percentage from the bottom
 */
class TransformerData @JvmOverloads constructor(
    val videoWidth: Int,
    val videoHeight: Int,
    val foreground: Bitmap,
    val mask: Bitmap,
    val bottomPadding: Float = 0F
)