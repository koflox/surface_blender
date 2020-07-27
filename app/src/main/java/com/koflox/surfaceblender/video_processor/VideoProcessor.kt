package com.koflox.surfaceblender.video_processor

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import android.view.Surface
import com.koflox.surfaceblender.blender.TextureTransformer
import com.koflox.surfaceblender.blender.TransformerData
import com.koflox.surfaceblender.debugLog
import com.koflox.surfaceblender.decoder.Decoder
import com.koflox.surfaceblender.egl.EglCore
import com.koflox.surfaceblender.encoder.Encoder
import java.io.File

internal class VideoProcessor private constructor(
    private val bitmapFiltered: Bitmap,
    private val bitmapMask: Bitmap,
    private val bottomPadding: Float
) {

    companion object {
        val TAG = VideoProcessor::class.java.simpleName
        const val DEFAULT_FRAME_RATE = 30
        const val FRAME_COUNT_DATA_IS_ABSENT = -1
    }

    internal interface VideoProcessorCallback {
        fun onProgress(progress: Float)
        fun onFinished(path: String)
        fun onFailed()
    }

    private val phaser = VideoGeneratingPhaser()

    private val videoDecoderCallback = object :
        Decoder.VideoDecoderCallback {
        override fun onReadyDecodeFrame() {
            phaser.waitDecodingAllowed()
        }

        override fun onFrameDecoded(decodedFrameIndex: Int) {
            debugLog(TAG, "onFrameDecoded: $decodedFrameIndex/$frameCount")
            phaser.nextPhase()
        }

        override fun onDecodingFinished() {
            phaser.waitEncodingFinished()
            debugLog(TAG, "onDecodingFinished")
            encoder?.stopRecording()
        }

        override fun onDecodingFailed() {
            callback?.onFailed()
            releaseAllModules()
        }

    }

    private val transformerCallback = object : TextureTransformer.TextureTransformerCallback {
        override fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
            decoder.start(Surface(surfaceTexture), videoDecoderCallback)
        }

        override fun onReadyRenderFrame() {
            when (textureTransformer?.isRunning) {
                false -> Unit
                else -> phaser.waitRenderingAllowed()
            }
        }

        override fun onFrameRendered() {
            debugLog(TAG, "onFrameRendered")
            phaser.nextPhase()
            encoder?.frameAvailableSoon()
        }
    }

    private val videoEncoderCallback = object : Encoder.VideoEncoderCallback {
        override fun onEncoderSurfaceReady(surface: Surface) {
            generateWithTransformer(videoWidth, videoHeight, surface)
        }

        override fun onReadyEncodeFrame() {
            phaser.waitEncodingAllowed()
        }

        override fun onFrameEncoded(encodedFrameIndex: Int) {
            callback?.onProgress(encodedFrameIndex.toFloat() / frameCount)
            debugLog(TAG, "onFrameEncoded: $encodedFrameIndex/$frameCount")
            phaser.nextPhase()
        }

        override fun onEncodingFinished() {
            debugLog(TAG, "video generation took: ${System.currentTimeMillis() - initialTime} ms")
            debugLog(TAG, "video path: ${outputFile.absolutePath}")
            callback?.onProgress(1F)
            callback?.onFinished(outputFile.absolutePath)
            releaseAllModules()
        }

        override fun onEncodingFailed() {
            debugLog(TAG, "onEncodingFailed")
            callback?.onFailed()
            releaseAllModules()
        }
    }

    private val surface: Surface = MediaCodec.createPersistentInputSurface()
    private var encoder: Encoder? = null
    private var textureTransformer: TextureTransformer? = null
    private var decoder: Decoder =
        Decoder()

    private val mmr: MediaMetadataRetriever = MediaMetadataRetriever()

    private lateinit var outputFile: File
    private var callback: VideoProcessorCallback? = null

    private var frameCount: Int = -1
    private var frameRate: Int =
        DEFAULT_FRAME_RATE

    private var videoWidth: Int = -1
    private var videoHeight: Int = -1

    private var initialTime = 0L

    constructor(
        videoAfd: AssetFileDescriptor,
        bitmapFiltered: Bitmap,
        bitmapMask: Bitmap,
        bottomPadding: Float
    ) : this(bitmapFiltered, bitmapMask, bottomPadding) {
        mmr.setDataSource(videoAfd.fileDescriptor, videoAfd.startOffset, videoAfd.length)
        decoder.setDataSource(videoAfd)
    }

    constructor(
        videoPath: String,
        bitmapFiltered: Bitmap,
        bitmapMask: Bitmap,
        bottomPadding: Float
    ) : this(bitmapFiltered, bitmapMask, bottomPadding) {
        mmr.setDataSource(videoPath)
        decoder.setDataSource(videoPath)
    }

    fun generate(
        outputFile: File,
        callback: VideoProcessorCallback?
    ) {
        initialTime = System.currentTimeMillis()

        this.outputFile = outputFile
        this.callback = callback

        generateVideo()
    }

    private fun releaseAllModules() {
        debugLog(TAG, "releaseAllModules")

        decoder.release()

        textureTransformer?.release()
        textureTransformer = null

        encoder?.stopRecording()
        encoder = null

        surface.release()

        callback = null
        encoder = null
    }

    @Throws(IllegalArgumentException::class)
    private fun generateVideo() {
        // Retrieve video metadata
        videoWidth = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        videoHeight = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val videoDuration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toInt()
        frameCount = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: FRAME_COUNT_DATA_IS_ABSENT
            }
            else -> FRAME_COUNT_DATA_IS_ABSENT
        }
        mmr.release()
        frameRate = when {
            frameCount != FRAME_COUNT_DATA_IS_ABSENT -> 1_000 / (videoDuration / frameCount)
            else -> DEFAULT_FRAME_RATE
        }
        debugLog(
            TAG, "generateVideo, video size: ${Size(videoWidth, videoHeight)}, " +
                    "videoDuration: $videoDuration, " +
                    "frameCount: $frameCount, " +
                    "frameRate: $frameRate"
        )
        encoder = Encoder(
            surface,
            bitmapFiltered.width, bitmapFiltered.height,
            frameRate, outputFile,
            callback = videoEncoderCallback
        )
    }

    private fun generateWithTransformer(videoWidth: Int, videoHeight: Int, encoderSurface: Surface) {
        val data = TransformerData(videoWidth, videoHeight, bitmapFiltered, bitmapMask, bottomPadding)
        textureTransformer = TextureTransformer(
            EglCore.Mode.RECORDING, encoderSurface,
            data,
            data.foreground.width, data.foreground.height,
            transformerCallback
        )
    }

}