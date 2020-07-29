package com.koflox.surfaceblender.encoder

import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.koflox.surfaceblender.debugLog
import java.io.File
import java.io.IOException

/**
 * This class wraps up the core components used for surface-input video encoding.
 *
 *
 * Once created, frames are fed to the input surface.  Remember to provide the presentation
 * time stamp, and always call drainEncoder() before swapBuffers() to ensure that the
 * producer side doesn't get backed up.
 *
 *
 * This class is not thread-safe, with one exception: it is valid to use the input surface
 * on one thread, and drain the output on a different thread.
 */
internal class VideoEncoderCore
/**
 * Configures encoder and muxer state, and prepares the input Surface.
 */
@Throws(IOException::class, IllegalArgumentException::class)
constructor(reusableSurface: Surface?, width: Int, height: Int, bitRate: Int, private val frameRate: Int, outputFile: File) {

    /**
     * Returns the encoder's input surface.
     */
    val inputSurface: Surface
    private var mMuxer: MediaMuxer? = null
    private var mEncoder: MediaCodec? = null
    private val mBufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
    private var mTrackIndex: Int = 0
    private var mMuxerStarted: Boolean = false
    private var encodedFrameIndex = 0

    init {
        val format = createMediaFormat(MIME_TYPE, width, height, bitRate, frameRate, I_FRAME_INTERVAL)
        debugLog(TAG, "format: $format")

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            reusableSurface?.also {
                this.setInputSurface(reusableSurface)
            }
            inputSurface = reusableSurface ?: createInputSurface()
            start()
        }

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        mMuxer = MediaMuxer(
            outputFile.toString(),
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        mTrackIndex = -1
        mMuxerStarted = false
    }

    fun stop() {
        debugLog(TAG, "stop encoder core")
        mEncoder?.stop()
        if (encodedFrameIndex != 0)
            mMuxer?.stop()
    }

    /**
     * Releases encoder resources.
     */
    fun release() {
        debugLog(TAG, "release encoder core")
        mEncoder?.release()
        mEncoder = null
        mMuxer?.release()
        mMuxer = null

        inputSurface.release()
    }

    /**
     * Extracts all pending data from the encoder and forwards it to the muxer.
     *
     *
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     *
     *
     * We're just using the muxer to get a .mp4 file (instead of a raw H.264 stream).  We're
     * not recording audio.
     */
    fun drainEncoder(endOfStream: Boolean): Int {
        val encoder = mEncoder ?: return encodedFrameIndex
        val muxer = mMuxer ?: return encodedFrameIndex
        debugLog(TAG, "drainEncoder($endOfStream)")
        val frameEncodingStart = System.currentTimeMillis()

        if (endOfStream) {
            debugLog(TAG, "sending EOS to encoder")
            encoder.signalEndOfInputStream()
            return encodedFrameIndex
        }
        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC.toLong())
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    debugLog(TAG, "MediaCodec.INFO_TRY_AGAIN_LATER")
                    break      // out of while
                } else {
                    debugLog(TAG, "no output available, spinning to await EOS")
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw RuntimeException("format changed twice")
                }
                val newFormat = encoder.outputFormat
                Log.d(TAG, "encoder output format changed: $newFormat")

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = muxer.addTrack(newFormat)
                muxer.start()
                mMuxerStarted = true
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                // let's ignore it
            } else {
                val encodedData = encoder.getOutputBuffer(encoderStatus) ?: continue

                if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    debugLog(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                    mBufferInfo.size = 0
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw RuntimeException("muxer hasn't started")
                    }

                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset)
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size)

                    mBufferInfo.presentationTimeUs = 132 + encodedFrameIndex++ * 1000000L / frameRate

                    muxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo)
                    debugLog(
                        TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                mBufferInfo.presentationTimeUs
                    )
                }

                encoder.releaseOutputBuffer(encoderStatus, false)
                if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (!endOfStream) {
                        debugLog(TAG, "reached end of stream unexpectedly")
                    } else {
                        debugLog(TAG, "end of stream reached")
                    }
                    break      // out of while
                }
            }
        }
        debugLog(TAG, "frame $encodedFrameIndex encoding took: ${System.currentTimeMillis() - frameEncodingStart}")
        return encodedFrameIndex
    }

    companion object {
        private const val TAG = "VideoEncoder"

        private const val TIMEOUT_USEC = 10000
        private const val MIME_TYPE = "video/avc"    // H.264 Advanced Video Coding
        private const val I_FRAME_INTERVAL = 10           // 5 seconds between I-frames
    }
}


/**
 * Encode a movie from frames rendered from an external texture image.
 *
 *
 * The object wraps an encoder running partly on two different threads.  An external thread
 * is sending data to the encoder's input surface, and we (the encoder thread) are pulling
 * the encoded data out and feeding it into a MediaMuxer.
 *
 *
 * We could block forever waiting for the encoder, but because of the thread decomposition
 * that turns out to be a little awkward (we want to call signalEndOfInputStream() from the
 * encoder thread to avoid thread-safety issues, but we can't do that if we're blocked on
 * the encoder).  If we don't pull from the encoder often enough, the producer side can back up.
 *
 *
 * The solution is to have the producer trigger drainEncoder() on every frame, before it
 * submits the new frame.  drainEncoder() might run before or after the frame is submitted,
 * but it doesn't matter -- either it runs early and prevents blockage, or it runs late
 * and un-blocks the encoder.
 *
 * Tells the video recorder to start recording.  (Call from non-encoder thread.)
 *
 *
 * Creates a new thread, which will own the provided VideoEncoderCore.  When the
 * thread exits, the VideoEncoderCore will be released.
 *
 *
 * Returns after the recorder thread has started and is ready to accept Messages.
 */
internal class Encoder(
    private val reusableSurface: Surface?,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val outputFile: File,
    private val bitRate: Int = 20_000_000,
    private val callback: VideoEncoderCallback
) {

    companion object {
        private const val TAG = "VideoEncoder"
    }

    interface VideoEncoderCallback {
        fun onEncoderSurfaceReady(surface: Surface)
        fun onReadyEncodeFrame()
        fun onFrameEncoded(encodedFrameIndex: Int)
        fun onEncodingFinished()
        fun onEncodingFailed() //failed to open the file for write
    }

    private var encoderCore: VideoEncoderCore? = null

    private val encoderThread = HandlerThread("EncoderThread").apply {
        start()
    }
    private val handler = Handler(encoderThread.looper)

    init {
        handler.post {
            try {
                encoderCore = VideoEncoderCore(
                    reusableSurface,
                    width,
                    height,
                    bitRate,
                    frameRate,
                    outputFile
                ).apply {
                    callback.onEncoderSurfaceReady(inputSurface)
                }
            } catch (ex: Exception) {
                debugLog(TAG, "Encoder cannot be configured", ex)
                callback.onEncodingFailed()
                release()
            }
        }
    }

    fun stopRecording() {
        handler.post {
            handleStopRecording()
        }
    }

    fun frameAvailableSoon() {
        handler.post {
            handleFrameAvailable()
        }
    }

    private fun handleFrameAvailable() {
        val encoderCore = encoderCore ?: return
        try {
            debugLog(TAG, "handleFrameAvailable")
            callback.onReadyEncodeFrame()
            val encodedFrameIndex = encoderCore.drainEncoder(false)
            callback.onFrameEncoded(encodedFrameIndex)
        } catch (ex: Exception) {
            debugLog(TAG, "Handle frame available", ex)
        }
    }

    /**
     * Handles a request to stop encoding.
     */
    private fun handleStopRecording() {
        val encoderCore = encoderCore ?: return
        try {
            debugLog(TAG, "handleStopRecording")
            val encodedFrameIndex = encoderCore.drainEncoder(true)
            encoderCore.stop()
            when (encodedFrameIndex) {
                0 -> callback.onEncodingFailed()
                else -> callback.onEncodingFinished()
            }
        } catch (ex: Exception) {
            callback.onEncodingFailed()
        } finally {
            release()
        }
    }

    private fun release() {
        debugLog(TAG, "release encoder thread")
        encoderCore?.release()
        encoderCore = null
        encoderThread.quitSafely()
    }

}