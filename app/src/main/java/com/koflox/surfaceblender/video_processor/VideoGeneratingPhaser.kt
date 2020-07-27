package com.koflox.surfaceblender.video_processor

import java.util.concurrent.atomic.AtomicInteger

internal class VideoGeneratingPhaser {

    private enum class Phase(val value: Int) {
        PHASE_READY_TO_DECODE(0),
        PHASE_READY_TO_RENDER(1),
        PHASE_READY_TO_ENCODE(2)
    }

    private val phase = AtomicInteger(0)

    fun nextPhase() = phase.getAndIncrement()

    fun isReadyToDecode() = phase.get() % Phase.values().size == Phase.PHASE_READY_TO_DECODE.value

    fun isReadyToRender() = phase.get() % Phase.values().size == Phase.PHASE_READY_TO_RENDER.value

    fun isReadyToEncode() = phase.get() % Phase.values().size == Phase.PHASE_READY_TO_ENCODE.value

    fun isNotReadyToDecode() = !isReadyToDecode()

    fun isNotReadyToRender() = !isReadyToRender()

    fun isNotReadyToEncode() = !isReadyToEncode()

    fun waitDecodingAllowed() {
        while (isNotReadyToDecode()) Unit
    }

    fun waitRenderingAllowed() {
        while (isNotReadyToRender()) Unit
    }

    fun waitEncodingAllowed() {
        while (isNotReadyToEncode()) Unit
    }

    fun waitEncodingFinished() = waitDecodingAllowed()

}