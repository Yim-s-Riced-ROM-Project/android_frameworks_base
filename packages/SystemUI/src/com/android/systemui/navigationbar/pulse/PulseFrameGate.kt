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

import javax.inject.Inject

/** Outcome of feeding a frame or a clock reading to [PulseFrameGate]. */
enum class PulseFrameResult {
    INVALID,
    VALID,
    BECAME_READY,
    STARTUP_TIMED_OUT,
    SILENCE_TIMED_OUT,
}

/**
 * Tracks whether Visualizer FFT frames are usable, independently of rendering.
 *
 * A frame is valid when it is structurally sound (at least [MIN_FFT_BYTES] bytes, even length) and
 * at least one (real, imaginary) bin, excluding the DC and Nyquist bytes, has a normalized
 * magnitude above [PulseFft.NOISE_FLOOR]. Normalization is identical to [PulseSpectrumProcessor]:
 * sqrt(re^2 + im^2) divided by the largest possible pair magnitude. All-zero and noise-only frames
 * are therefore invalid.
 *
 * After [start], [REQUIRED_VALID_FRAMES] valid frames (not necessarily consecutive) must arrive
 * within [STARTUP_TIMEOUT_MS], making the gate ready. Once ready, every valid frame pushes the
 * silence deadline [SILENCE_TIMEOUT_MS] ahead; invalid frames do not. Timeouts are reported
 * repeatedly until [reset] or [start]; the caller owns latching.
 *
 * Time is supplied by the caller in milliseconds from any monotonic clock. This class is NOT
 * thread-safe: callers must confine it to a single thread (the main thread, in the Pulse
 * controller).
 */
class PulseFrameGate @Inject constructor() {
    var ready = false
        private set

    private var started = false
    private var validFrames = 0
    private var startupDeadlineMs = 0L
    private var silenceDeadlineMs = 0L

    fun start(nowMs: Long) {
        reset()
        started = true
        startupDeadlineMs = nowMs + STARTUP_TIMEOUT_MS
    }

    fun onFrame(fft: ByteArray, nowMs: Long): PulseFrameResult {
        if (!started) return PulseFrameResult.INVALID
        checkTimeout(nowMs)?.let {
            return it
        }
        if (!isValid(fft)) return PulseFrameResult.INVALID
        if (!ready) {
            validFrames++
            if (validFrames == REQUIRED_VALID_FRAMES) {
                ready = true
                silenceDeadlineMs = nowMs + SILENCE_TIMEOUT_MS
                return PulseFrameResult.BECAME_READY
            }
        } else {
            silenceDeadlineMs = nowMs + SILENCE_TIMEOUT_MS
        }
        return PulseFrameResult.VALID
    }

    fun checkTimeout(nowMs: Long): PulseFrameResult? {
        if (!started) return null
        if (!ready && nowMs >= startupDeadlineMs) return PulseFrameResult.STARTUP_TIMED_OUT
        if (ready && nowMs >= silenceDeadlineMs) return PulseFrameResult.SILENCE_TIMED_OUT
        return null
    }

    fun reset() {
        started = false
        ready = false
        validFrames = 0
        startupDeadlineMs = 0L
        silenceDeadlineMs = 0L
    }

    private fun isValid(fft: ByteArray): Boolean {
        if (fft.size < MIN_FFT_BYTES || fft.size % 2 != 0) return false
        for (bin in 1 until fft.size / 2) {
            if (PulseFft.normalizedMagnitude(fft, bin) > PulseFft.NOISE_FLOOR) return true
        }
        return false
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 1_000L
        const val SILENCE_TIMEOUT_MS = 2_000L
        const val REQUIRED_VALID_FRAMES = 3
        const val MIN_FFT_BYTES = 66
    }
}
