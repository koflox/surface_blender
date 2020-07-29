package com.koflox.surfaceblender.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Size
import kotlin.math.min

@Throws(IllegalArgumentException::class)
internal fun createMediaFormat(
    mimeType: String,
    desiredWidth: Int,
    desiredHeight: Int,
    desiredBitrate: Int,
    desiredFrameRate: Int,
    iFrameInterval: Int
): MediaFormat {
    val codecInfo = selectCodec(mimeType)
        ?: throw IllegalArgumentException("No codec was found for $mimeType")
    val videoCapabilities = codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
    val (size, frameRate, bitrate) = videoCapabilities.getMediaFormatData(
        desiredWidth, desiredHeight, desiredBitrate, desiredFrameRate
    )
    return MediaFormat.createVideoFormat(
        mimeType,
        size.width,
        size.height
    ).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
    }
}

internal fun selectCodec(mimeType: String): MediaCodecInfo? {
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach { codec ->
        if (codec.isEncoder && codec.supportedTypes?.contains(mimeType) == true) {
            return codec
        }
    }
    return null
}

@Throws(IllegalArgumentException::class)
internal fun MediaCodecInfo.VideoCapabilities.getMediaFormatData(
    desiredWidth: Int,
    desiredHeight: Int,
    desiredBitrate: Int,
    desiredFrameRate: Int
): Triple<Size, Int, Int> {
    val supportedSize = getSupportedSize(desiredWidth, desiredHeight)
        ?: throw IllegalArgumentException("Cannot find supported size for ($desiredWidth:$desiredHeight)")
    val supportedFrameRate = getSupportedFrameRateForSize(supportedSize.width, supportedSize.height, desiredFrameRate)
        ?: throw IllegalArgumentException("Cannot find supported frame rate for (${supportedSize.width}:${supportedSize.height}) $desiredFrameRate FPS")
    val supportedBitrate = getSupportedBitrate(desiredBitrate)
    return Triple(supportedSize, supportedFrameRate, supportedBitrate)
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedSize(w: Int, h: Int): Size? {
    val supportedW = supportedWidths
    val supportedH = supportedHeights
    var width: Int = -1
    var height: Int = -1
    when {
        isSizeSupported(w, h) -> {
            width = w
            height = h
        }
        else -> {
            val aspectRatio = w.toFloat() / h
            val maxCodecWidth = supportedW.upper
            val maxCodecHeight = supportedH.upper
            val codecAspectRatio = maxCodecWidth.toFloat() / maxCodecHeight
            when {
                codecAspectRatio >= aspectRatio -> {
                    val probableWidth = getSupportedWidthsFor(min(maxCodecHeight, h)).upper
                    getSupportedSizeByWidth(probableWidth, aspectRatio)?.let { newSize ->
                        width = newSize.width
                        height = newSize.height
                    }
                }
                else -> {
                    val probableHeight = getSupportedHeightsFor(maxCodecWidth).upper
                    val probableWidth = getSupportedWidth((probableHeight * aspectRatio).toInt())
                    getSupportedSizeByWidth(probableWidth, aspectRatio)?.let { newSize ->
                        width = newSize.width
                        height = newSize.height
                    }
                }
            }
        }
    }
    return when {
        width == -1 || height == -1 -> null
        else -> Size(width, height)
    }
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedSizeByWidth(width: Int, aspectRatio: Float): Size? {
    for (testW in width downTo 0 step 2) {
        val testH = getSupportedHeight((testW.toFloat() / aspectRatio).toInt())
        if (isSizeSupported(testW, testH)) {
            return Size(testW, testH)
        }
    }
    return null
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedWidth(width: Int) = when {
    width % widthAlignment == 0 -> width
    else -> width / widthAlignment * widthAlignment
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedHeight(height: Int) = when {
    height % widthAlignment == 0 -> height
    else -> height / widthAlignment * widthAlignment
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedFrameRateForSize(w: Int, h: Int, desiredFrameRate: Int): Int? = when {
    areSizeAndRateSupported(w, h, desiredFrameRate.toDouble()) -> desiredFrameRate
    else -> {
        var rate: Int? = null
        for (supportedRate in supportedFrameRates.upper downTo supportedFrameRates.lower) {
            if (areSizeAndRateSupported(w, h, supportedRate.toDouble())) {
                rate = supportedRate
            }
        }
        rate
    }
}

internal fun MediaCodecInfo.VideoCapabilities.getSupportedBitrate(desiredBitrate: Int): Int {
    return min(desiredBitrate, bitrateRange.upper)
}