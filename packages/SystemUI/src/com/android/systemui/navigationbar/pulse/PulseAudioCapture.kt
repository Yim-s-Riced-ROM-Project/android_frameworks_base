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

/** Owns FFT capture for Pulse without exposing the platform audio effect. */
interface PulseAudioCapture {
    /**
     * Starts capture on [requestedSessionId] when positive, falling back once to the output mix.
     *
     * Returns true when capture is running after the call, and false when every candidate failed.
     * While capture is already running this returns true and ignores [requestedSessionId]; to
     * change session, call [stop] and then start again.
     *
     * [onFailure] is invoked at most once per start: synchronously, before this method returns
     * false, when every candidate fails to start, or later from the Visualizer callback thread if
     * capture fails while running. It must be cheap and must not block.
     */
    fun start(
        requestedSessionId: Int?,
        onFftData: (ByteArray) -> Unit,
        onFailure: () -> Unit,
    ): Boolean

    /** Stops capture and releases the platform resources; safe to call when not running. */
    fun stop()
}
