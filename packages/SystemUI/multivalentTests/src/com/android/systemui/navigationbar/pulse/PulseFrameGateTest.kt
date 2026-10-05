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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PulseFrameGateTest {
    private val underTest = PulseFrameGate()

    @Test
    fun thirdNonconsecutiveValidFrame_becomesReady() {
        underTest.start(0)
        assertThat(underTest.onFrame(validFft(), 100)).isEqualTo(PulseFrameResult.VALID)
        assertThat(underTest.onFrame(ByteArray(66), 200)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.onFrame(validFft(), 300)).isEqualTo(PulseFrameResult.VALID)
        assertThat(underTest.onFrame(noiseOnlyFft(), 400)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.onFrame(validFft(), 500)).isEqualTo(PulseFrameResult.BECAME_READY)
        assertThat(underTest.ready).isTrue()
    }

    @Test
    fun startupWithoutThreeValidFrames_timesOutAtOneSecond() {
        underTest.start(100)
        underTest.onFrame(validFft(), 200)
        underTest.onFrame(validFft(), 300)
        assertThat(underTest.checkTimeout(1_099)).isNull()
        assertThat(underTest.checkTimeout(1_100)).isEqualTo(PulseFrameResult.STARTUP_TIMED_OUT)
        assertThat(underTest.ready).isFalse()
    }

    @Test
    fun validRunningFrame_refreshesTwoSecondDeadline() {
        makeReadyAt(300)
        underTest.onFrame(validFft(), 2_000)
        assertThat(underTest.checkTimeout(3_999)).isNull()
        assertThat(underTest.checkTimeout(4_000)).isEqualTo(PulseFrameResult.SILENCE_TIMED_OUT)
    }

    @Test
    fun invalidRunningFrame_doesNotRefreshSilenceDeadline() {
        makeReadyAt(300)
        assertThat(underTest.onFrame(ByteArray(66), 2_000)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.onFrame(noiseOnlyFft(), 2_100)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.checkTimeout(2_299)).isNull()
        assertThat(underTest.checkTimeout(2_300)).isEqualTo(PulseFrameResult.SILENCE_TIMED_OUT)
    }

    @Test
    fun readyTransition_setsSilenceDeadlineFromReadyFrame() {
        makeReadyAt(900)
        assertThat(underTest.checkTimeout(2_899)).isNull()
        assertThat(underTest.checkTimeout(2_900)).isEqualTo(PulseFrameResult.SILENCE_TIMED_OUT)
    }

    @Test
    fun startupDeadline_isIgnoredOnceReady() {
        makeReadyAt(900)
        assertThat(underTest.checkTimeout(1_500)).isNull()
    }

    @Test
    fun frameAtStartupDeadline_reportsTimeoutInsteadOfCounting() {
        underTest.start(0)
        underTest.onFrame(validFft(), 100)
        underTest.onFrame(validFft(), 200)
        assertThat(underTest.onFrame(validFft(), 1_000))
            .isEqualTo(PulseFrameResult.STARTUP_TIMED_OUT)
        assertThat(underTest.ready).isFalse()
    }

    @Test
    fun frameAtSilenceDeadline_reportsTimeoutInsteadOfRefreshing() {
        makeReadyAt(0)
        assertThat(underTest.onFrame(validFft(), 2_000))
            .isEqualTo(PulseFrameResult.SILENCE_TIMED_OUT)
    }

    @Test
    fun shortFrame_isInvalid() {
        underTest.start(0)
        assertThat(underTest.onFrame(ByteArray(64) { 100 }, 10)).isEqualTo(PulseFrameResult.INVALID)
    }

    @Test
    fun oddLengthFrame_isInvalid() {
        underTest.start(0)
        assertThat(underTest.onFrame(ByteArray(67) { 100 }, 10)).isEqualTo(PulseFrameResult.INVALID)
    }

    @Test
    fun allZeroFrame_isInvalid() {
        underTest.start(0)
        assertThat(underTest.onFrame(ByteArray(1024), 10)).isEqualTo(PulseFrameResult.INVALID)
    }

    @Test
    fun dcAndNyquistOnlyFrame_isInvalid() {
        val fft = ByteArray(66)
        fft[0] = 127
        fft[1] = 127
        underTest.start(0)
        assertThat(underTest.onFrame(fft, 10)).isEqualTo(PulseFrameResult.INVALID)
    }

    @Test
    fun magnitudeAtNoiseFloor_isInvalidAndJustAboveIsValid() {
        // Normalized magnitude = |re| / (sqrt(2) * 128): 3 -> 0.0166, 4 -> 0.0221.
        underTest.start(0)
        assertThat(underTest.onFrame(singleBinFft(3), 10)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.onFrame(singleBinFft(4), 20)).isEqualTo(PulseFrameResult.VALID)
    }

    @Test
    fun frameBeforeStart_isInvalidAndTimeoutIsNull() {
        assertThat(underTest.onFrame(validFft(), 10)).isEqualTo(PulseFrameResult.INVALID)
        assertThat(underTest.checkTimeout(100_000)).isNull()
        assertThat(underTest.ready).isFalse()
    }

    @Test
    fun reset_clearsReadinessAndDeadlines() {
        makeReadyAt(0)
        underTest.reset()
        assertThat(underTest.ready).isFalse()
        assertThat(underTest.checkTimeout(100_000)).isNull()
        assertThat(underTest.onFrame(validFft(), 10)).isEqualTo(PulseFrameResult.INVALID)
    }

    @Test
    fun restart_requiresThreeFreshValidFrames() {
        makeReadyAt(0)
        underTest.start(10_000)
        assertThat(underTest.ready).isFalse()
        assertThat(underTest.onFrame(validFft(), 10_100)).isEqualTo(PulseFrameResult.VALID)
        assertThat(underTest.onFrame(validFft(), 10_200)).isEqualTo(PulseFrameResult.VALID)
        assertThat(underTest.onFrame(validFft(), 10_300)).isEqualTo(PulseFrameResult.BECAME_READY)
    }

    private fun makeReadyAt(nowMs: Long) {
        underTest.start(nowMs - 50)
        underTest.onFrame(validFft(), nowMs - 20)
        underTest.onFrame(validFft(), nowMs - 10)
        assertThat(underTest.onFrame(validFft(), nowMs)).isEqualTo(PulseFrameResult.BECAME_READY)
    }

    private fun validFft(): ByteArray =
        ByteArray(66).also {
            it[10] = 60
            it[11] = 60
        }

    private fun noiseOnlyFft(): ByteArray = singleBinFft(1)

    private fun singleBinFft(real: Int): ByteArray = ByteArray(66).also { it[10] = real.toByte() }
}
