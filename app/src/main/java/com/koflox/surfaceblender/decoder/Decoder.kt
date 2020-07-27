package com.koflox.surfaceblender.decoder

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.koflox.surfaceblender.debugLog

internal class Decoder {

    companion object {
        val TAG = Decoder::class.java.simpleName
        const val TIMEOUT_US = 1_000_000L
    }

    interface VideoDecoderCallback {
        fun onReadyDecodeFrame()
        fun onFrameDecoded(decodedFrameIndex: Int)
        fun onDecodingFinished()
        fun onDecodingFailed()
    }

    private val decoderThread = HandlerThread("DecoderThread").apply {
        start()
    }
    private val handler = Handler(decoderThread.looper)

    private var callback: VideoDecoderCallback? = null
    private var mediaExtractor: MediaExtractor? = null
    private var mediaDecoder: MediaCodec? = null

    fun setDataSource(videoAfd: AssetFileDescriptor) {
        handler.post {
            mediaExtractor = MediaExtractor().apply {
                setDataSource(videoAfd.fileDescriptor, videoAfd.startOffset, videoAfd.length)
            }
        }
    }

    fun setDataSource(videoPath: String) {
        handler.post {
            mediaExtractor = MediaExtractor().apply {
                setDataSource(videoPath)
            }
        }
    }

    fun start(surface: Surface, callback: VideoDecoderCallback? = null) {
        handler.post {
            this.callback = callback
            val (mimeType, mediaFormat) = getVideoInfo() ?: kotlin.run {
                callback?.onDecodingFailed()
                release()
                return@post
            }
            setupMediaDecoder(surface, mimeType, mediaFormat)
            decode()
        }
    }

    fun release() {
        handler.post {
            debugLog(TAG, "Release Decoder")
            mediaDecoder?.stop()
            mediaDecoder?.release()
            mediaDecoder = null

            mediaExtractor?.release()

            decoderThread.quitSafely()
        }
    }

    private fun getVideoInfo(): Pair<String, MediaFormat>? {
        val mediaExtractor = mediaExtractor ?: return null
        for (i in 0 until mediaExtractor.trackCount) {
            val mediaFormat = mediaExtractor.getTrackFormat(i)
            mediaFormat.getString(MediaFormat.KEY_MIME)?.let { mimeType ->
                if (mimeType.startsWith("video/")) {
                    mediaExtractor.selectTrack(i)
                    return Pair(mimeType, mediaFormat)
                }
            }
        }
        return null
    }

    private fun setupMediaDecoder(surface: Surface? = null, mimeType: String, mediaFormat: MediaFormat) {
        mediaDecoder = MediaCodec.createDecoderByType(mimeType).apply {
            configure(mediaFormat, surface, null, 0)
            start()
        }
    }

    private fun decode() {
        debugLog(TAG, "decodeEncode")
        val mediaExtractor = requireNotNull(mediaExtractor)
        val mediaDecoder = requireNotNull(mediaDecoder)
        val info = MediaCodec.BufferInfo()
        var framesDecoded = 0
        while (true) {
            val inputBufferIndex = mediaDecoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = mediaDecoder.getInputBuffer(inputBufferIndex) ?: continue
                val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                when {
                    sampleSize > 0 -> { // Video data is valid, send input buffer to MediaCodec for decode
                        mediaDecoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, mediaExtractor.sampleTime, 0)
                        mediaExtractor.advance()
                    }
                    else -> { // End-Of-Stream (EOS)
                        debugLog(TAG, "decoder end of stream")
                        mediaDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        callback?.onDecodingFinished()
                        release()
                        return
                    }
                }
            }
            val outputBufferIndex = mediaDecoder.dequeueOutputBuffer(
                info,
                TIMEOUT_US
            )
            if (outputBufferIndex >= 0) {
                callback?.onReadyDecodeFrame()
                mediaDecoder.releaseOutputBuffer(outputBufferIndex, true)
                callback?.onFrameDecoded(++framesDecoded)
            }
        }
    }

}