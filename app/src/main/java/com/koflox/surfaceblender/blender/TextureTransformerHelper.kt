package com.koflox.surfaceblender.blender

internal const val TEXTURE_SOURCE_NAME = "sTexture"
internal const val TEXTURE_FOREGROUND_NAME = "sForeground"
internal const val TEXTURE_MASK_NAME = "sMask"

internal const val VERTEX_POSITION = "vPosition"
internal const val VERTEX_TEXTURE_COORDINATE = "vTexCoordinate"
internal const val VERTEX_COORDINATE_MATRIX = "uSTMatrix"
internal const val SOURCE_COORDINATE_MATRIX = "sourceCoordinateMatrix"

internal const val VERTEX_SHADER_CODE =
    "#version 320 es\n" +
            "in vec2 $VERTEX_POSITION;" +
            "in vec2 $VERTEX_TEXTURE_COORDINATE;" +
            "out vec2 texCoord;" +
            "out vec2 croppedTexCoord;" +
            "uniform mat4 $VERTEX_COORDINATE_MATRIX;" +
            "uniform mat4 $SOURCE_COORDINATE_MATRIX;" +
            "void main() {" +
            "texCoord = ($VERTEX_COORDINATE_MATRIX * vec4($VERTEX_TEXTURE_COORDINATE, 0, 1)).xy;" +
            "croppedTexCoord = ($SOURCE_COORDINATE_MATRIX * vec4($VERTEX_TEXTURE_COORDINATE, 0, 1)).xy;" +
            "gl_Position = vec4($VERTEX_POSITION, 0, 1);" +
            "}"

internal const val FRAGMENT_SHADER_CODE =
    "#version 320 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "" +
            "precision mediump float; " +
            "uniform samplerExternalOES $TEXTURE_SOURCE_NAME; " +
            "uniform sampler2D $TEXTURE_FOREGROUND_NAME; " +
            "uniform sampler2D $TEXTURE_MASK_NAME; " +
            "in vec2 texCoord; " +
            "in vec2 croppedTexCoord; " +
            "out vec4 colorOut; " +
            "" +
            "void main() { " +
            "vec4 mask = texture($TEXTURE_MASK_NAME, texCoord); " +
            "vec4 foregroundColor = (1. - mask.a) * texture($TEXTURE_FOREGROUND_NAME, texCoord); " +
            "vec4 backgroundColor = mask.a  * texture($TEXTURE_SOURCE_NAME, croppedTexCoord); " +
            "colorOut = vec4((foregroundColor + backgroundColor).rgb, 1.); " +
            "}"

internal const val SQUARE_SIZE = 1.0f
internal val SQUARE_COORDINATES = floatArrayOf(
    -SQUARE_SIZE, SQUARE_SIZE, 0.0f,
    -SQUARE_SIZE, -SQUARE_SIZE, 0.0f,
    SQUARE_SIZE, -SQUARE_SIZE, 0.0f,
    SQUARE_SIZE, SQUARE_SIZE, 0.0f
)
internal val DRAW_ORDER = shortArrayOf(0, 1, 2, 0, 2, 3)
internal val TEXTURE_COORDINATES = floatArrayOf(
    0.0f, 1.0f, 0.0f, 1.0f,
    0.0f, 0.0f, 0.0f, 1.0f,
    1.0f, 0.0f, 0.0f, 1.0f,
    1.0f, 1.0f, 0.0f, 1.0f
)