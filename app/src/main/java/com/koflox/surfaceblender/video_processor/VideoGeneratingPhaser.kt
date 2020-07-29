package com.koflox.surfaceblender.video_processor

import java.util.concurrent.atomic.AtomicInteger

internal class VideoGeneratingPhaser {

    private enum class Phase(val value: Int) {
        PHASE_READY_TO_DECODE(0),
        PHASE_READY_TO_ENCODE(1)
    }

    private val phase = AtomicInteger(0)

    fun nextPhase() {
        phase.getAndIncrement()
    }

    fun waitDecodingAllowed() {
        while (isNotReadyToDecode()) Unit
    }

    fun waitEncodingAllowed() {
        while (isNotReadyToEncode()) Unit
    }

    fun waitEncodingFinished() = waitDecodingAllowed()

    private fun isReadyToDecode() = phase.get() % Phase.values().size == Phase.PHASE_READY_TO_DECODE.value

    private fun isReadyToEncode() = phase.get() % Phase.values().size == Phase.PHASE_READY_TO_ENCODE.value

    private fun isNotReadyToDecode() = !isReadyToDecode()

    private fun isNotReadyToEncode() = !isReadyToEncode()

}