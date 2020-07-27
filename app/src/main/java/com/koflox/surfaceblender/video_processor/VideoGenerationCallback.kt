package com.koflox.surfaceblender.video_processor

interface VideoGenerationCallback {
    fun onStarted()
    fun onProgress(progress: Float)
    fun onFinished(path: String)

    /**
     * May be called if failed to open the file for write
     * Or file doesn't contain any video
     */
    fun onFailed()
}