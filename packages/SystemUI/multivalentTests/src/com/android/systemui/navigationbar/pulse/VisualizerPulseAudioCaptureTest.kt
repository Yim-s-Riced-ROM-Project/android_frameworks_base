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
    /** Ordered log across the factory and all handles: "create:<id>" and "h<n>:<event>". */
    private val globalLog = mutableListOf<String>()
    private var handleCount = 0
    private val warnings = mutableListOf<String>()
    private lateinit var factory: FakeVisualizerFactory
    private lateinit var visualizer: FakeVisualizerHandle
    private lateinit var underTest: VisualizerPulseAudioCapture

    @Before
    fun setUp() {
        visualizer = FakeVisualizerHandle()
        factory = FakeVisualizerFactory(visualizer)
        underTest = VisualizerPulseAudioCapture(factory) { warnings += it }
    }

    @Test
    fun start_configuresOutputMixFftBeforeEnabling() {
        val received = mutableListOf<ByteArray>()

        assertThat(underTest.start(null, received::add) {}).isTrue()

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

        assertThat(underTest.start(null, {}) {}).isTrue()

        assertThat(visualizer.events).contains("listener:18000:false:true")
    }

    @Test
    fun start_whenAlreadyRunning_doesNotCreateSecondVisualizer() {
        assertThat(underTest.start(null, {}) {}).isTrue()

        assertThat(underTest.start(null, {}) {}).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(0)
    }

    @Test
    fun start_nullSession_createsOnlyOutputMix() {
        assertThat(underTest.start(null, {}, {})).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(0)
    }

    @Test
    fun start_selectedSessionSucceeds_doesNotCreateOutputMix() {
        assertThat(underTest.start(42, {}, {})).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(42)
    }

    @Test
    fun start_selectedSessionFails_releasesBeforeOutputMixFallback() {
        factory.enqueue(
            FakeVisualizerHandle(captureSizeResult = Visualizer.ERROR_BAD_VALUE),
            FakeVisualizerHandle(),
        )
        var failures = 0

        assertThat(underTest.start(42, {}, { failures++ })).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(42, 0).inOrder()
        assertThat(factory.handles[0].events).contains("release")
        assertThat(failures).isEqualTo(0)
    }

    @Test
    fun start_selectedSessionFails_fullyCleansCandidateBeforeFallbackCreation() {
        val failedHandle = FakeVisualizerHandle(enableResult = Visualizer.ERROR_INVALID_OPERATION)
        factory.enqueue(failedHandle, FakeVisualizerHandle())
        val failed = failedHandle.id

        assertThat(underTest.start(42, {}, {})).isTrue()

        val cleanup = listOf("h$failed:listener:null", "h$failed:enabled:false", "h$failed:release")
        assertThat(globalLog).containsAtLeastElementsIn(cleanup).inOrder()
        assertThat(globalLog.indexOf("h$failed:enabled:true"))
            .isLessThan(globalLog.indexOf("h$failed:listener:null"))
        assertThat(globalLog.indexOf("h$failed:release")).isLessThan(globalLog.indexOf("create:0"))
    }

    @Test
    fun start_bothAttemptsFail_reportsOneTerminalFailure() {
        factory.enqueue(
            FakeVisualizerHandle(captureSizeResult = Visualizer.ERROR_BAD_VALUE),
            FakeVisualizerHandle(enableResult = Visualizer.ERROR_INVALID_OPERATION),
        )
        var failures = 0

        assertThat(underTest.start(42, {}, { failures++ })).isFalse()

        assertThat(failures).isEqualTo(1)
        assertThat(factory.createdSessionIds).containsExactly(42, 0).inOrder()
        assertThat(factory.handles.map { it.events.last() }).containsExactly("release", "release")
        assertThat(warnings).hasSize(2)
    }

    @Test
    fun start_selectedSessionCreateThrows_fallsBackWithoutLeakOrFailure() {
        factory.enqueueCreateFailure(UnsupportedOperationException("no session"))
        var failures = 0

        assertThat(underTest.start(42, {}, { failures++ })).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(42, 0).inOrder()
        assertThat(factory.handles).hasSize(1)
        assertThat(factory.handles.single().events).doesNotContain("release")
        assertThat(failures).isEqualTo(0)
        assertThat(warnings)
            .containsExactly("Pulse capture setup failed at create: UnsupportedOperationException")
    }

    @Test
    fun start_setupCallThrows_cleansCandidateAndFallsBackWithAccurateStage() {
        val throwing = FakeVisualizerHandle()
        throwing.captureSizeError = IllegalStateException("bad state 4242")
        factory.enqueue(throwing, FakeVisualizerHandle())
        var failures = 0

        assertThat(underTest.start(4242, {}, { failures++ })).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(4242, 0).inOrder()
        assertThat(throwing.events.takeLast(3))
            .containsExactly("listener:null", "enabled:false", "release")
            .inOrder()
        assertThat(failures).isEqualTo(0)
        assertThat(warnings)
            .containsExactly("Pulse capture setup failed at capture size: IllegalStateException")
    }

    @Test
    fun start_lateFftFromAbandonedHandle_isIgnored() {
        val abandoned = FakeVisualizerHandle(enableResult = Visualizer.ERROR_INVALID_OPERATION)
        factory.enqueue(abandoned, FakeVisualizerHandle())
        val received = mutableListOf<ByteArray>()
        var failures = 0
        assertThat(underTest.start(42, received::add, { failures++ })).isTrue()
        val late = checkNotNull(abandoned.lastRegisteredCallback)

        late.invoke(byteArrayOf(9, 9))

        assertThat(received).isEmpty()
        assertThat(failures).isEqualTo(0)
        val fft = byteArrayOf(1)
        factory.handles[1].fftCallback!!.invoke(fft)
        assertThat(received).containsExactly(fft)
    }

    @Test
    fun start_failure_logsStageAndClassWithoutSessionOrMessage() {
        factory.enqueue(FakeVisualizerHandle(captureSizeResult = Visualizer.ERROR_BAD_VALUE))

        underTest.start(4242, {}, {})

        assertThat(warnings.first())
            .isEqualTo("Pulse capture setup failed at capture size: SetupStageException")
        assertThat(warnings.joinToString()).doesNotContain("4242")
    }

    @Test
    fun start_nonPositiveSession_createsOnlyOutputMix() {
        assertThat(underTest.start(-1, {}, {})).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(0)
    }

    @Test
    fun start_selectedSessionActive_doesNotDuplicateCapture() {
        assertThat(underTest.start(42, {}, {})).isTrue()

        assertThat(underTest.start(43, {}, {})).isTrue()

        assertThat(factory.createdSessionIds).containsExactly(42)
    }

    @Test
    fun stop_clearsListenerDisablesThenReleasesExactlyOnce() {
        underTest.start(null, {}) {}
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

        assertThat(underTest.start(null, {}, { failures++ })).isFalse()

        assertThat(visualizer.events).contains("release")
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun start_whenEnableFails_releasesAndReportsFailure() {
        var failures = 0
        visualizer.enableResult = Visualizer.ERROR_INVALID_OPERATION

        assertThat(underTest.start(null, {}, { failures++ })).isFalse()

        assertThat(visualizer.events).contains("release")
        assertThat(failures).isEqualTo(1)
    }

    @Test
    fun fftConsumerFailure_releasesAndReportsFailureOnce() {
        var failures = 0
        underTest.start(null, { error("consumer failed") }, { failures++ })
        visualizer.events.clear()

        visualizer.fftCallback!!.invoke(byteArrayOf(1, 2))
        visualizer.fftCallback?.invoke(byteArrayOf(3, 4))

        assertThat(visualizer.events)
            .containsExactly("listener:null", "enabled:false", "release")
            .inOrder()
        assertThat(failures).isEqualTo(1)
    }

    private inner class FakeVisualizerFactory(
        private val defaultHandle: FakeVisualizerHandle,
        override var maxCaptureRate: Int = 48_000,
    ) : VisualizerFactory {
        val createdSessionIds = mutableListOf<Int>()
        val handles = mutableListOf<FakeVisualizerHandle>()
        private val queued = ArrayDeque<FakeVisualizerHandle>()
        private val createFailures = ArrayDeque<Throwable>()

        fun enqueueCreateFailure(error: Throwable) {
            createFailures.addLast(error)
        }

        fun enqueue(vararg handles: FakeVisualizerHandle) {
            queued.addAll(handles)
        }

        override fun create(audioSessionId: Int): VisualizerHandle {
            // Creation is logged in the same shared log the handles write to.
            createdSessionIds += audioSessionId
            globalLog += "create:$audioSessionId"
            createFailures.removeFirstOrNull()?.let { throw it }
            val handle = queued.removeFirstOrNull() ?: defaultHandle
            handles += handle
            return handle
        }
    }

    private inner class FakeVisualizerHandle(
        var captureSizeResult: Int = Visualizer.SUCCESS,
        var enableResult: Int = Visualizer.SUCCESS,
    ) : VisualizerHandle {
        val events = mutableListOf<String>()
        val id = ++handleCount

        private fun record(event: String) {
            events += event
            globalLog += "h$id:$event"
        }

        var fftCallback: ((ByteArray) -> Unit)? = null
        var lastRegisteredCallback: ((ByteArray) -> Unit)? = null
        var captureSizeError: Throwable? = null

        override fun setEnabled(enabled: Boolean): Int {
            record("enabled:$enabled")
            return if (enabled) enableResult else Visualizer.SUCCESS
        }

        override fun setCaptureSize(size: Int): Int {
            record("captureSize:$size")
            captureSizeError?.let { throw it }
            return captureSizeResult
        }

        override fun setScalingMode(mode: Int): Int {
            record("scalingMode:$mode")
            return Visualizer.SUCCESS
        }

        override fun setMeasurementMode(mode: Int): Int {
            record("measurementMode:$mode")
            return Visualizer.SUCCESS
        }

        override fun setFftCaptureListener(rate: Int, callback: (ByteArray) -> Unit): Int {
            record("listener:$rate:false:true")
            fftCallback = callback
            lastRegisteredCallback = callback
            return Visualizer.SUCCESS
        }

        override fun clearCaptureListener(): Int {
            record("listener:null")
            fftCallback = null
            return Visualizer.SUCCESS
        }

        override fun release() {
            record("release")
        }
    }
}
