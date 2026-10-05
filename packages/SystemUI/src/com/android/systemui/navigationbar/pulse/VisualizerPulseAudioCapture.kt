/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.navigationbar.pulse

import android.media.audiofx.Visualizer
import android.util.Log
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import javax.inject.Inject
import kotlin.math.min

@PerDisplaySingleton
class VisualizerPulseAudioCapture
constructor(
    private val factory: VisualizerFactory,
    private val logWarning: (String) -> Unit = { Log.w(TAG, it) },
) : PulseAudioCapture {
    @Inject constructor(factory: PlatformVisualizerFactory) : this(factory as VisualizerFactory)

    private val lock = Any()
    private var visualizer: VisualizerHandle? = null

    override fun start(
        requestedSessionId: Int?,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ): Boolean {
        val started =
            synchronized(lock) {
                visualizer != null || startFirstAvailable(requestedSessionId, onFftData, onFailure)
            }
        // The terminal callback runs outside the lock so a client cannot re-enter or block capture.
        if (!started) onFailure()
        return started
    }

    private fun startFirstAvailable(
        requestedSessionId: Int?,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ): Boolean {
        val sessions =
            if (requestedSessionId != null && requestedSessionId > 0) {
                intArrayOf(requestedSessionId, OUTPUT_MIX_SESSION)
            } else {
                intArrayOf(OUTPUT_MIX_SESSION)
            }
        for (sessionId in sessions) {
            if (startOne(sessionId, onFftData, onFailure)) return true
        }
        return false
    }

    /** Starts one candidate session; on failure it is fully cleaned up and no callback is made. */
    private fun startOne(
        sessionId: Int,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ): Boolean {
        var stage = "create"
        var candidate: VisualizerHandle? = null
        return try {
            val handle = factory.create(sessionId)
            candidate = handle
            stage = "disable"
            requireSuccess(handle.setEnabled(false))
            stage = "capture size"
            requireSuccess(handle.setCaptureSize(CAPTURE_SIZE))
            stage = "scaling mode"
            try {
                requireSuccess(handle.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED))
            } catch (_: NoSuchMethodError) {
                // Some vendor implementations omit this optional method.
            }
            stage = "measurement mode"
            requireSuccess(handle.setMeasurementMode(Visualizer.MEASUREMENT_MODE_NONE))
            stage = "capture listener"
            val captureRate = min(CAPTURE_RATE_MHZ, factory.maxCaptureRate)
            requireSuccess(
                handle.setFftCaptureListener(captureRate) { fft ->
                    handleFft(handle, fft, onFftData, onFailure)
                }
            )
            visualizer = handle
            stage = "enable"
            requireSuccess(handle.setEnabled(true))
            true
        } catch (e: Exception) {
            abandon(candidate, stage, e)
            false
        } catch (e: LinkageError) {
            abandon(candidate, stage, e)
            false
        }
    }

    private fun abandon(candidate: VisualizerHandle?, stage: String, error: Throwable) {
        // Log the failing stage and exception class only; never session ids or messages.
        logWarning("Pulse capture setup failed at $stage: ${error.javaClass.simpleName}")
        visualizer = null
        candidate?.cleanup()
    }

    override fun stop() {
        val current =
            synchronized(lock) {
                val result = visualizer
                visualizer = null
                result
            }
        current?.cleanup()
    }

    private fun handleFft(
        source: VisualizerHandle,
        fft: ByteArray,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ) {
        if (synchronized(lock) { visualizer !== source }) return

        try {
            onFftData(fft)
        } catch (_: RuntimeException) {
            val current =
                synchronized(lock) {
                    if (visualizer !== source) return
                    visualizer = null
                    source
                }
            current?.cleanup()
            onFailure()
        }
    }

    private fun requireSuccess(status: Int) {
        if (status != Visualizer.SUCCESS) throw SetupStageException()
    }

    private fun VisualizerHandle.cleanup() {
        runCatching { clearCaptureListener() }
        runCatching { setEnabled(false) }
        runCatching { release() }
    }

    private class SetupStageException : IllegalStateException()

    private companion object {
        const val TAG = "PulseCapture"
        const val OUTPUT_MIX_SESSION = 0
        const val CAPTURE_SIZE = 512
        const val CAPTURE_RATE_MHZ = 25_000
    }
}

interface VisualizerFactory {
    val maxCaptureRate: Int

    fun create(audioSessionId: Int): VisualizerHandle
}

interface VisualizerHandle {
    fun setEnabled(enabled: Boolean): Int

    fun setCaptureSize(size: Int): Int

    fun setScalingMode(mode: Int): Int

    fun setMeasurementMode(mode: Int): Int

    fun setFftCaptureListener(rate: Int, callback: (ByteArray) -> Unit): Int

    fun clearCaptureListener(): Int

    fun release()
}

class PlatformVisualizerFactory @Inject constructor() : VisualizerFactory {
    override val maxCaptureRate: Int
        get() = Visualizer.getMaxCaptureRate()

    override fun create(audioSessionId: Int): VisualizerHandle {
        return PlatformVisualizerHandle(Visualizer(audioSessionId))
    }
}

private class PlatformVisualizerHandle(private val visualizer: Visualizer) : VisualizerHandle {
    private var captureRate = Visualizer.getMaxCaptureRate()

    override fun setEnabled(enabled: Boolean): Int = visualizer.setEnabled(enabled)

    override fun setCaptureSize(size: Int): Int = visualizer.setCaptureSize(size)

    override fun setScalingMode(mode: Int): Int = visualizer.setScalingMode(mode)

    override fun setMeasurementMode(mode: Int): Int = visualizer.setMeasurementMode(mode)

    override fun setFftCaptureListener(rate: Int, callback: (ByteArray) -> Unit): Int {
        captureRate = rate
        return visualizer.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer,
                    waveform: ByteArray,
                    samplingRate: Int,
                ) = Unit

                override fun onFftDataCapture(
                    visualizer: Visualizer,
                    fft: ByteArray,
                    samplingRate: Int,
                ) {
                    callback(fft)
                }
            },
            rate,
            false,
            true,
        )
    }

    override fun clearCaptureListener(): Int {
        return visualizer.setDataCaptureListener(null, captureRate, false, false)
    }

    override fun release() {
        visualizer.release()
    }
}
