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
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class VisualizerPulseAudioCaptureTest {
    private lateinit var factory: FakeVisualizerFactory
    private lateinit var visualizer: FakeVisualizerHandle
    private lateinit var underTest: VisualizerPulseAudioCapture

    @Before
    fun setUp() {
        visualizer = FakeVisualizerHandle()
        factory = FakeVisualizerFactory(visualizer)
        underTest = VisualizerPulseAudioCapture(factory)
    }

    @Test
    fun start_configuresOutputMixFftBeforeEnabling() {
        val received = mutableListOf<ByteArray>()

        assertThat(underTest.start(received::add) {}).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(0)
        assertThat(visualizer.events)
            .containsExactly(
                "enabled:false",
                "captureSize:512",
                "scalingMode:${Visualizer.SCALING_MODE_NORMALIZED}",
                "measurementMode:${Visualizer.MEASUREMENT_MODE_NONE}",
                "listener:25000:false:true",
                "enabled:true",
            )
            .inOrder()

        val fft = byteArrayOf(1, 2, 3, 4)
        visualizer.fftCallback!!.invoke(fft)
        assertThat(received).containsExactly(fft)
    }

    @Test
    fun start_capsRateToPlatformMaximum() {
        factory.maxCaptureRate = 18_000

        assertThat(underTest.start({}) {}).isTrue()

        assertThat(visualizer.events).contains("listener:18000:false:true")
    }

    @Test
    fun start_whenAlreadyRunning_doesNotCreateSecondVisualizer() {
        assertThat(underTest.start({}) {}).isTrue()

        assertThat(underTest.start({}) {}).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(0)
    }

    @Test
    fun stop_clearsListenerDisablesThenReleasesExactlyOnce() {
        underTest.start({}) {}
        visualizer.events.clear()

        underTest.stop()
        underTest.stop()

        assertThat(visualizer.events)
            .containsExactly("listener:null", "enabled:false", "release")
            .inOrder()
    }

    @Test
    fun start_whenConfigurationFails_releasesAndReportsFailure() {
        var failures = 0
        visualizer.captureSizeResult = Visualizer.ERROR_BAD_VALUE

        assertThat(underTest.start({}, { failures++ })).isFalse()

        assertThat(visualizer.events).contains("release")
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun start_whenEnableFails_releasesAndReportsFailure() {
        var failures = 0
        visualizer.enableResult = Visualizer.ERROR_INVALID_OPERATION

        assertThat(underTest.start({}, { failures++ })).isFalse()

        assertThat(visualizer.events).contains("release")
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun fftConsumerFailure_releasesAndReportsFailureOnce() {
        var failures = 0
        underTest.start({ error("consumer failed") }, { failures++ })
        visualizer.events.clear()

        visualizer.fftCallback!!.invoke(byteArrayOf(1, 2))
        visualizer.fftCallback?.invoke(byteArrayOf(3, 4))

        assertThat(visualizer.events)
            .containsExactly("listener:null", "enabled:false", "release")
            .inOrder()
        assertThat(failures).isEqualTo(1)
    }

    private class FakeVisualizerFactory(
        private val visualizer: FakeVisualizerHandle,
        override var maxCaptureRate: Int = 48_000,
    ) : VisualizerFactory {
        val createdSessionIds = mutableListOf<Int>()

        override fun create(audioSessionId: Int): VisualizerHandle {
            createdSessionIds += audioSessionId
            return visualizer
        }
    }

    private class FakeVisualizerHandle : VisualizerHandle {
        val events = mutableListOf<String>()
        var captureSizeResult = Visualizer.SUCCESS
        var enableResult = Visualizer.SUCCESS
        var fftCallback: ((ByteArray) -> Unit)? = null

        override fun setEnabled(enabled: Boolean): Int {
            events += "enabled:$enabled"
            return if (enabled) enableResult else Visualizer.SUCCESS
        }

        override fun setCaptureSize(size: Int): Int {
            events += "captureSize:$size"
            return captureSizeResult
        }

        override fun setScalingMode(mode: Int): Int {
            events += "scalingMode:$mode"
            return Visualizer.SUCCESS
        }

        override fun setMeasurementMode(mode: Int): Int {
            events += "measurementMode:$mode"
            return Visualizer.SUCCESS
        }

        override fun setFftCaptureListener(rate: Int, callback: (ByteArray) -> Unit): Int {
            events += "listener:$rate:false:true"
            fftCallback = callback
            return Visualizer.SUCCESS
        }

        override fun clearCaptureListener(): Int {
            events += "listener:null"
            fftCallback = null
            return Visualizer.SUCCESS
        }

        override fun release() {
            events += "release"
        }
    }
}
