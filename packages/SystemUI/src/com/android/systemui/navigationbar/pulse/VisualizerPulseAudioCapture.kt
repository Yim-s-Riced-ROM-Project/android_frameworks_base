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
import com.android.systemui.navigationbar.NavigationBarComponent.NavigationBarScope
import javax.inject.Inject
import kotlin.math.min

@NavigationBarScope
class VisualizerPulseAudioCapture constructor(private val factory: VisualizerFactory) :
    PulseAudioCapture {
    @Inject constructor(factory: PlatformVisualizerFactory) : this(factory as VisualizerFactory)

    private var visualizer: VisualizerHandle? = null

    @Synchronized
    override fun start(onFftData: (ByteArray) -> Unit, onFailure: () -> Unit): Boolean {
        if (visualizer != null) return true

        var candidate: VisualizerHandle? = null
        return try {
            candidate = factory.create(OUTPUT_MIX_SESSION)
            requireSuccess(candidate.setEnabled(false), "disable")
            requireSuccess(candidate.setCaptureSize(CAPTURE_SIZE), "capture size")
            try {
                requireSuccess(
                    candidate.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED),
                    "scaling mode",
                )
            } catch (_: NoSuchMethodError) {
                // Some vendor implementations omit this optional method.
            }
            requireSuccess(
                candidate.setMeasurementMode(Visualizer.MEASUREMENT_MODE_NONE),
                "measurement mode",
            )
            val captureRate = min(CAPTURE_RATE_MHZ, factory.maxCaptureRate)
            requireSuccess(
                candidate.setFftCaptureListener(captureRate) { fft ->
                    handleFft(candidate, fft, onFftData, onFailure)
                },
                "capture listener",
            )
            visualizer = candidate
            requireSuccess(candidate.setEnabled(true), "enable")
            true
        } catch (_: Exception) {
            visualizer = null
            candidate?.cleanup()
            onFailure()
            false
        } catch (_: LinkageError) {
            visualizer = null
            candidate?.cleanup()
            onFailure()
            false
        }
    }

    override fun stop() {
        val current =
            synchronized(this) {
                val result = visualizer
                visualizer = null
                result
            }
        current?.cleanup()
    }

    private fun handleFft(
        source: VisualizerHandle?,
        fft: ByteArray,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ) {
        if (synchronized(this) { visualizer !== source }) return

        try {
            onFftData(fft)
        } catch (_: RuntimeException) {
            val current =
                synchronized(this) {
                    if (visualizer !== source) return
                    visualizer = null
                    source
                }
            current?.cleanup()
            onFailure()
        }
    }

    private fun requireSuccess(status: Int, operation: String) {
        check(status == Visualizer.SUCCESS) { "$operation failed with status $status" }
    }

    private fun VisualizerHandle.cleanup() {
        runCatching { clearCaptureListener() }
        runCatching { setEnabled(false) }
        runCatching { release() }
    }

    private companion object {
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
