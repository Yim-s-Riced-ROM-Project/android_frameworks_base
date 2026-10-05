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

import android.content.Context
import android.os.Looper
import android.util.Log
import android.view.Display
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayId
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the Pulse runtime (capture, frame gate, spectrum processing and overlay) for one display.
 *
 * Only the default display is active; other instances stay inert. [start] and [stop] may be called
 * from any thread. All runtime state below is confined to the main thread, except [captureEpoch]
 * and the frame mailbox, which are shared with the capture thread.
 */
@PerDisplaySingleton
class PulseController
@Inject
constructor(
    private val settingsRepository: PulseSettingsRepository,
    private val playbackRepository: PulsePlaybackRepository,
    @param:DisplayAware private val hostStateRepository: PulseHostStateRepository,
    @param:DisplayAware private val audioCapture: PulseAudioCapture,
    private val frameGate: PulseFrameGate,
    private val spectrumProcessor: PulseSpectrumProcessor,
    private val windowController: PulseWindowController,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val powerInteractor: PowerInteractor,
    private val batteryController: BatteryController,
    private val userTracker: UserTracker,
    private val systemClock: SystemClock,
    @param:DisplayId private val displayId: Int,
    @param:DisplayAware private val displayScope: CoroutineScope,
    @param:Main private val mainDispatcher: CoroutineDispatcher,
    @param:Main private val mainExecutor: Executor,
) : SystemUIDisplaySubcomponent.LifecycleListener {
    private val started = AtomicBoolean(false)
    @Volatile private var collectionJob: Job? = null

    // Eligibility inputs (main thread). Unknown inputs start in their fail-closed state.
    private var config = PulseConfig(enabled = false, color = DEFAULT_COLOR, heightDp = 48)
    private var hostState = PulseHostState()
    private var playbackTarget = PulsePlaybackTarget.INACTIVE
    private var deviceEntered = false
    private var awake = false
    private var powerSave = true
    private var lastInputs: EligibilityInputs? = null
    private var activationFailed = false

    // Runtime state (main thread).
    private var captureActive = false
    private var captureSessionId: Int? = null
    private var overlayShown = false
    private var watchdogJob: Job? = null

    /** Incremented whenever capture starts or stops; callbacks from older epochs are dropped. */
    private val captureEpoch = AtomicInteger()

    // Latest FFT frame handed from the capture thread to the main thread. Frames conflate, and the
    // buffers are reused so steady-state capture does not allocate per frame.
    private val frameLock = Any()
    private var pendingFrame = ByteArray(0)
    private var pendingEpoch = 0
    private var framePosted = false
    private var mainFrame = ByteArray(0)
    private val deliverFrame = Runnable { deliverPendingFrame() }

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onPowerSaveChanged(isPowerSave: Boolean) {
                // BatteryController reports synchronously on the registering thread.
                mainExecutor.execute {
                    powerSave = isPowerSave
                    recompute()
                }
            }
        }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                activationFailed = false
                stopRuntime()
                recompute()
            }
        }

    init {
        windowController.setWindowFailureListener { failEpoch(captureEpoch.get(), "window update") }
    }

    override fun start() {
        if (displayId != Display.DEFAULT_DISPLAY || !started.compareAndSet(false, true)) return
        batteryController.addCallback(batteryCallback)
        userTracker.addCallback(userCallback, mainExecutor)
        mainExecutor.execute {
            powerSave = batteryController.isPowerSave
            recompute()
        }
        collectionJob =
            displayScope.launch(mainDispatcher) {
                launch {
                    settingsRepository.config.collectLatest {
                        onConfigChanged(it)
                        recompute()
                    }
                }
                launch {
                    playbackRepository.target.collectLatest {
                        playbackTarget = it
                        recompute()
                    }
                }
                launch {
                    hostStateRepository.state.collectLatest {
                        hostState = it
                        recompute()
                    }
                }
                launch {
                    deviceEntryInteractor.isDeviceEntered.collectLatest {
                        deviceEntered = it
                        recompute()
                    }
                }
                launch {
                    powerInteractor.isAwake.collectLatest {
                        awake = it
                        recompute()
                    }
                }
            }
    }

    /**
     * Called after the display scope is cancelled, possibly on a background thread. Native capture
     * is released synchronously here; window and main-confined state follow on the main thread.
     */
    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        collectionJob?.cancel()
        collectionJob = null
        userTracker.removeCallback(userCallback)
        batteryController.removeCallback(batteryCallback)
        captureEpoch.incrementAndGet()
        audioCapture.stop()
        if (Looper.getMainLooper()?.isCurrentThread == true) {
            stopRuntime()
        } else {
            mainExecutor.execute(::stopRuntime)
        }
    }

    private fun onConfigChanged(next: PulseConfig) {
        val previous = config
        config = next
        if (!overlayShown) return
        if (next.color != previous.color) windowController.setColorRgb(next.color)
        if (next.heightDp != previous.heightDp && !windowController.updateHeight(next.heightDp)) {
            failEpoch(captureEpoch.get(), "window update")
        }
    }

    private fun recompute() {
        val inputs =
            EligibilityInputs(
                config.enabled,
                hostState,
                deviceEntered,
                awake,
                powerSave,
                playbackTarget,
            )
        if (inputs != lastInputs) {
            // Any relevant input change clears an activation or silence failure latch.
            lastInputs = inputs
            activationFailed = false
        }

        if (!isEligible(inputs)) {
            stopRuntime()
            return
        }
        if (captureActive && captureSessionId != playbackTarget.sessionId) {
            // Capture ignores a new session while running: release before replacing it.
            stopRuntime()
        }
        if (!captureActive) startRuntime(playbackTarget.sessionId)
    }

    private fun isEligible(inputs: EligibilityInputs): Boolean =
        started.get() &&
            displayId == Display.DEFAULT_DISPLAY &&
            inputs.enabled &&
            inputs.hostState.activeHost != PulseHost.NONE &&
            inputs.hostState.navigationVisible &&
            !inputs.hostState.screenPinningActive &&
            inputs.deviceEntered &&
            inputs.awake &&
            !inputs.powerSave &&
            inputs.playbackTarget.active &&
            !activationFailed

    private fun startRuntime(sessionId: Int?) {
        val epoch = captureEpoch.incrementAndGet()
        captureActive = true
        captureSessionId = sessionId
        frameGate.reset()
        spectrumProcessor.reset()
        val captureStarted =
            try {
                audioCapture.start(
                    requestedSessionId = sessionId,
                    onFftData = { fft -> onCaptureFrame(epoch, fft) },
                    onFailure = { mainExecutor.execute { failEpoch(epoch, "capture") } },
                )
            } catch (_: RuntimeException) {
                false
            }
        if (!captureStarted) {
            failEpoch(epoch, "capture start")
            return
        }
        frameGate.start(systemClock.elapsedRealtime())
        startWatchdog(epoch)
    }

    private fun startWatchdog(epoch: Int) {
        watchdogJob =
            displayScope.launch(mainDispatcher) {
                while (captureActive && epoch == captureEpoch.get()) {
                    delay(WATCHDOG_INTERVAL_MS)
                    when (frameGate.checkTimeout(systemClock.elapsedRealtime())) {
                        PulseFrameResult.STARTUP_TIMED_OUT -> failEpoch(epoch, "startup timeout")
                        PulseFrameResult.SILENCE_TIMED_OUT -> failEpoch(epoch, "silence timeout")
                        else -> Unit
                    }
                }
            }
    }

    /** Capture thread: stores the frame and posts at most one delivery to the main thread. */
    private fun onCaptureFrame(epoch: Int, fft: ByteArray) {
        synchronized(frameLock) {
            if (epoch != captureEpoch.get()) return
            if (pendingFrame.size != fft.size) pendingFrame = ByteArray(fft.size)
            fft.copyInto(pendingFrame)
            pendingEpoch = epoch
            if (framePosted) return
            framePosted = true
        }
        mainExecutor.execute(deliverFrame)
    }

    private fun deliverPendingFrame() {
        val epoch: Int
        synchronized(frameLock) {
            framePosted = false
            epoch = pendingEpoch
            if (!captureActive || epoch != captureEpoch.get()) return
            if (mainFrame.size != pendingFrame.size) mainFrame = ByteArray(pendingFrame.size)
            pendingFrame.copyInto(mainFrame)
        }
        onFrame(epoch, mainFrame)
    }

    private fun onFrame(epoch: Int, fft: ByteArray) {
        when (frameGate.onFrame(fft, systemClock.elapsedRealtime())) {
            PulseFrameResult.STARTUP_TIMED_OUT -> failEpoch(epoch, "startup timeout")
            PulseFrameResult.SILENCE_TIMED_OUT -> failEpoch(epoch, "silence timeout")
            PulseFrameResult.BECAME_READY -> showOverlay(epoch, spectrumProcessor.process(fft))
            PulseFrameResult.VALID,
            PulseFrameResult.INVALID ->
                if (overlayShown) windowController.setLevels(spectrumProcessor.process(fft))
        }
    }

    private fun showOverlay(epoch: Int, levels: FloatArray) {
        windowController.setColorRgb(config.color)
        windowController.setLevels(levels)
        if (!windowController.show(config.heightDp)) {
            failEpoch(epoch, "window add")
            return
        }
        overlayShown = true
    }

    /** Tears down [epoch] and latches until an eligibility input changes; stale epochs no-op. */
    private fun failEpoch(epoch: Int, stage: String) {
        if (!captureActive || epoch != captureEpoch.get()) return
        // Stage only: never session ids, FFT data or media identity.
        Log.w(TAG, "Pulse stopped: $stage")
        activationFailed = true
        stopRuntime()
    }

    /** Synchronous and idempotent; safe whether or not the overlay was ever shown. */
    private fun stopRuntime() {
        captureEpoch.incrementAndGet()
        captureActive = false
        captureSessionId = null
        overlayShown = false
        watchdogJob?.cancel()
        watchdogJob = null
        audioCapture.stop()
        frameGate.reset()
        spectrumProcessor.reset()
        windowController.hide()
    }

    private data class EligibilityInputs(
        val enabled: Boolean,
        val hostState: PulseHostState,
        val deviceEntered: Boolean,
        val awake: Boolean,
        val powerSave: Boolean,
        val playbackTarget: PulsePlaybackTarget,
    )

    private companion object {
        const val TAG = "PulseController"
        const val DEFAULT_COLOR = 0xFFFFFF
        const val WATCHDOG_INTERVAL_MS = 250L
    }
}
