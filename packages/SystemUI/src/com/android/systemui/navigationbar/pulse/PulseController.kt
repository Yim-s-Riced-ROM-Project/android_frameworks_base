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
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayId
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.PulseLog
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
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
 * and the frame mailbox, which are shared with whichever thread delivers FFT callbacks. In practice
 * that is the main thread: Visualizer binds its event handler to the Looper of the thread that
 * registers the listener, and [start] runs on main. The mailbox stays so the controller remains
 * correct if callbacks ever arrive off-main; it is harmless otherwise.
 *
 * Every eligibility decision is observable without a debuggable build: [dump] prints each gate and
 * the first false one, and [logBuffer] records transitions (never per-frame work). Read both with
 * `adb shell dumpsys activity service com.android.systemui/.SystemUIService PulseController
 * PulseLog`.
 */
@PerDisplaySingleton
class PulseController
@Inject
constructor(
    private val settingsRepository: PulseSettingsRepository,
    private val themeRepository: PulseThemeRepository,
    private val playbackRepository: PulsePlaybackRepository,
    @param:DisplayAware private val hostStateRepository: PulseHostStateRepository,
    @param:DisplayAware private val audioCapture: PulseAudioCapture,
    private val frameGate: PulseFrameGate,
    private val spectrumProcessor: PulseSpectrumProcessor,
    private val windowController: PulseWindowController,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val powerInteractor: PowerInteractor,
    private val batteryController: BatteryController,
    private val userTracker: UserTracker,
    private val systemClock: SystemClock,
    private val dumpManager: DumpManager,
    @param:PulseLog private val logBuffer: LogBuffer,
    @param:DisplayId private val displayId: Int,
    @param:DisplayAware private val displayScope: CoroutineScope,
    @param:Main private val mainDispatcher: CoroutineDispatcher,
    @param:Main private val mainExecutor: Executor,
) : SystemUIDisplaySubcomponent.LifecycleListener, Dumpable {
    private val started = AtomicBoolean(false)
    private var dumpableRegistered = false
    @Volatile private var collectionJob: Job? = null

    // Eligibility inputs (main thread). Unknown inputs start in their fail-closed state.
    private var config = PulseConfig(enabled = false, color = DEFAULT_COLOR, heightDp = 48)
    // Appearance only, never an eligibility input: a theme change recolors without a restart.
    private var nightMode = false
    private var hostState = PulseHostState()
    private var playbackTarget = PulsePlaybackTarget.INACTIVE
    private var keyguardGone = false
    private var awake = false
    private var powerSave = true
    private var lastInputs: EligibilityInputs? = null
    private var activationFailed = false
    private var lastBlockedBy: String? = UNKNOWN_GATE

    // Runtime state (main thread).
    private var captureActive = false
    private var captureSessionId: Int? = null
    private var overlayShown = false
    private var watchdogJob: Job? = null

    /** Incremented whenever capture starts or stops; callbacks from older epochs are dropped. */
    private val captureEpoch = AtomicInteger()

    // Latest FFT frame handed from the FFT callback thread (normally main, see class doc) to the
    // main thread. Frames conflate, and the buffers are reused so steady-state capture does not
    // allocate per frame.
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
                logBuffer.log(TAG, LogLevel.DEBUG, {}, { "user switched" })
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
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "controller started" })
        registerDumpable()
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
                launch { themeRepository.nightMode.collectLatest { onNightModeChanged(it) } }
                launch {
                    playbackRepository.target.collectLatest {
                        playbackTarget = it
                        recompute()
                    }
                }
                launch {
                    hostStateRepository.state.collectLatest {
                        if (it.activeHost != hostState.activeHost) logHostChanged(it.activeHost)
                        hostState = it
                        recompute()
                    }
                }
                launch {
                    // DeviceEntryInteractor.isDeviceEntered only emits with the scene container;
                    // the keyguard transition state covers both. Lockscreen, AOD and
                    // keyguard-occluding activities are not GONE.
                    keyguardTransitionInteractor
                        .isFinishedIn(Scenes.Gone, KeyguardState.GONE)
                        .collectLatest {
                            keyguardGone = it
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
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "controller stopped" })
        unregisterDumpable()
        collectionJob?.cancel()
        collectionJob = null
        userTracker.removeCallback(userCallback)
        batteryController.removeCallback(batteryCallback)
        captureEpoch.incrementAndGet()
        audioCapture.stop()
        if (Looper.getMainLooper()?.isCurrentThread == true) {
            teardown()
        } else {
            mainExecutor.execute(::teardown)
        }
    }

    /** Display lifecycle teardown: also clears the failure latch and the input snapshot. */
    private fun teardown() {
        activationFailed = false
        lastInputs = null
        lastBlockedBy = UNKNOWN_GATE
        stopRuntime()
    }

    /** Registration can race a not-yet-stopped previous instance; never crash SystemUI on it. */
    private fun registerDumpable() {
        try {
            dumpManager.registerNormalDumpable(TAG, this)
            dumpableRegistered = true
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Dumpable already registered")
        }
    }

    private fun unregisterDumpable() {
        if (!dumpableRegistered) return
        dumpableRegistered = false
        dumpManager.unregisterDumpable(TAG)
    }

    private fun onConfigChanged(next: PulseConfig) {
        val previous = config
        config = next
        if (next.colorMode != previous.colorMode) {
            logBuffer.log(
                TAG,
                LogLevel.DEBUG,
                { str1 = next.colorMode.name },
                { "colorMode=$str1" },
            )
        }
        val barLayoutChanged =
            next.barCount != previous.barCount || next.barGapPercent != previous.barGapPercent
        if (barLayoutChanged) {
            logBuffer.log(
                TAG,
                LogLevel.DEBUG,
                {
                    int1 = next.barCount
                    int2 = next.barGapPercent
                },
                { "barLayout count=$int1 gap=$int2" },
            )
        }
        if (!overlayShown) return
        if (barLayoutChanged) windowController.setBarLayout(next.barCount, next.barGapPercent)
        if (next.colorMode != previous.colorMode) windowController.setColorMode(next.colorMode)
        val argb = next.argb(nightMode)
        if (argb != previous.argb(nightMode)) windowController.setColor(argb)
        if (next.boost != previous.boost) windowController.setBoost(next.boost)
        if (next.heightDp != previous.heightDp && !windowController.updateHeight(next.heightDp)) {
            failEpoch(captureEpoch.get(), "window update")
        }
    }

    private fun onNightModeChanged(next: Boolean) {
        if (next == nightMode) return
        val previousArgb = config.argb(nightMode)
        nightMode = next
        logBuffer.log(TAG, LogLevel.DEBUG, { bool1 = next }, { "nightMode=$bool1" })
        val argb = config.argb(nightMode)
        if (overlayShown && argb != previousArgb) windowController.setColor(argb)
    }

    private fun currentInputs() =
        EligibilityInputs(config.enabled, hostState, keyguardGone, awake, powerSave, playbackTarget)

    private fun recompute() {
        val inputs = currentInputs()
        if (inputs != lastInputs) {
            // Any relevant input change clears an activation or silence failure latch.
            lastInputs = inputs
            if (activationFailed) logBuffer.log(TAG, LogLevel.DEBUG, {}, { "latch cleared" })
            activationFailed = false
        }

        val blockedBy = blockedBy(inputs)
        if (blockedBy != lastBlockedBy) {
            lastBlockedBy = blockedBy
            logBuffer.log(
                TAG,
                LogLevel.DEBUG,
                {
                    bool1 = blockedBy == null
                    str1 = blockedBy ?: NO_GATE
                },
                { "eligible=$bool1 blockedBy=$str1" },
            )
        }
        if (blockedBy != null) {
            stopRuntime()
            return
        }
        if (captureActive && captureSessionId != playbackTarget.sessionId) {
            // Capture ignores a new session while running: release before replacing it.
            stopRuntime()
        }
        if (!captureActive) startRuntime(playbackTarget.sessionId)
    }

    /**
     * The eligibility predicate: the name of the first false gate, or null when Pulse may run. The
     * names match the [dump] fields so `blockedBy=` points at the line to read.
     */
    private fun blockedBy(inputs: EligibilityInputs): String? =
        when {
            !started.get() -> "started"
            displayId != Display.DEFAULT_DISPLAY -> "displayId"
            !inputs.enabled -> "enabled"
            inputs.hostState.activeHost == PulseHost.NONE -> "activeHost"
            !inputs.hostState.navigationVisible -> "navigationVisible"
            inputs.hostState.screenPinningActive -> "screenPinningActive"
            !inputs.keyguardGone -> "keyguardGone"
            !inputs.awake -> "awake"
            inputs.powerSave -> "powerSave"
            !inputs.playbackTarget.active -> "playbackActive"
            activationFailed -> "activationFailed"
            else -> null
        }

    /**
     * Called on the dump thread; reads main-confined state without synchronization, so values may
     * be momentarily inconsistent. Never prints session ids, only whether one is selected.
     * `barGapPx` is the gap as last laid out, so it trails a gap change made while hidden.
     */
    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val inputs = currentInputs()
        val blockedBy = blockedBy(inputs)
        pw.println("started=${started.get()}")
        pw.println("displayId=$displayId")
        pw.println("enabled=${inputs.enabled}")
        pw.println("activeHost=${inputs.hostState.activeHost}")
        pw.println("navigationVisible=${inputs.hostState.navigationVisible}")
        pw.println("screenPinningActive=${inputs.hostState.screenPinningActive}")
        pw.println("keyguardGone=${inputs.keyguardGone}")
        pw.println("awake=${inputs.awake}")
        pw.println("powerSave=${inputs.powerSave}")
        pw.println("playbackActive=${inputs.playbackTarget.active}")
        pw.println("sessionSelected=${inputs.playbackTarget.sessionId != null}")
        pw.println("activationFailed=$activationFailed")
        pw.println("eligible=${blockedBy == null}")
        pw.println("blockedBy=${blockedBy ?: NO_GATE}")
        pw.println("captureActive=$captureActive")
        pw.println("captureEpoch=${captureEpoch.get()}")
        pw.println("frameGateReady=${frameGate.ready}")
        pw.println("overlayShown=$overlayShown")
        pw.println("windowAttached=${windowController.isAttached}")
        pw.println("alpha=${config.alpha}")
        pw.println("colorMode=${config.colorMode}")
        pw.println("nightMode=$nightMode")
        pw.println("effectiveColor=#%08X".format(config.argb(nightMode)))
        // True only while the overlay is shown in an animated mode: the view redraws every frame.
        pw.println("colorAnimating=${overlayShown && config.colorMode.animated}")
        pw.println("boost=${config.boost}")
        pw.println("barCount=${config.barCount}")
        pw.println("barGapPercent=${config.barGapPercent}")
        pw.println("barGapPx=${windowController.effectiveBarGapPx}")
    }

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
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = sessionId != null },
            { "capture started selected=$bool1 fallback=${!bool1}" },
        )
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

    /** FFT callback thread: stores the frame and posts at most one delivery to the main thread. */
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
        windowController.setColor(config.argb(nightMode))
        windowController.setColorMode(config.colorMode)
        windowController.setBoost(config.boost)
        windowController.setBarLayout(config.barCount, config.barGapPercent)
        windowController.setLevels(levels)
        if (!windowController.show(config.heightDp)) {
            failEpoch(epoch, "window add")
            return
        }
        overlayShown = true
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "ready, overlay shown" })
    }

    /** Tears down [epoch] and latches until an eligibility input changes; stale epochs no-op. */
    private fun failEpoch(epoch: Int, stage: String) {
        if (!captureActive || epoch != captureEpoch.get()) return
        // Stage only: never session ids, FFT data or media identity.
        Log.w(TAG, "Pulse stopped: $stage")
        logBuffer.log(TAG, LogLevel.WARNING, { str1 = stage }, { "failed stage=$str1" })
        activationFailed = true
        stopRuntime()
    }

    /** Synchronous and idempotent; safe whether or not the overlay was ever shown. */
    private fun stopRuntime() {
        if (captureActive) logBuffer.log(TAG, LogLevel.DEBUG, {}, { "capture stopped" })
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
        val keyguardGone: Boolean,
        val awake: Boolean,
        val powerSave: Boolean,
        val playbackTarget: PulsePlaybackTarget,
    )

    private fun logHostChanged(host: PulseHost) {
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = host.name }, { "host=$str1" })
    }

    private companion object {
        const val TAG = "PulseController"
        const val NO_GATE = "none"
        /** Sentinel so the first evaluation after start is always logged. */
        const val UNKNOWN_GATE = "unknown"
        const val DEFAULT_COLOR = 0xFFFFFF
        const val WATCHDOG_INTERVAL_MS = 250L
    }
}
