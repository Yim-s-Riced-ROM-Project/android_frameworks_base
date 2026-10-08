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
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogMessageImpl
import com.android.systemui.log.core.LogMessage
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.util.time.SystemClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Plain JVM tests: the display scope and main dispatcher share one virtual-time scheduler, and the
 * main executor is a manually drained queue so off-main capture callbacks can be held in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PulseControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)
    private val displayScope = CoroutineScope(mainDispatcher + Job())
    private val mainExecutor = QueueExecutor()

    private val settings =
        MutableStateFlow(PulseConfig(enabled = true, color = COLOR, heightDp = 64))
    private val playback = MutableStateFlow(PulsePlaybackTarget(active = true, sessionId = SESSION))
    private val keyguardGone = MutableStateFlow(true)
    private val awake = MutableStateFlow(true)
    private val nightMode = MutableStateFlow(false)
    private val hostState = PulseHostStateRepository()
    private val capture = FakePulseAudioCapture()

    private val settingsRepository = mock<PulseSettingsRepository>()
    private val themeRepository = mock<PulseThemeRepository>()
    private val playbackRepository = mock<PulsePlaybackRepository>()
    private val windowController = mock<PulseWindowController>()
    private val keyguardTransitionInteractor = mock<KeyguardTransitionInteractor>()
    private val powerInteractor = mock<PowerInteractor>()
    private val batteryController = mock<BatteryController>()
    private val userTracker = mock<UserTracker>()
    private val systemClock = mock<SystemClock>()
    private val dumpManager = mock<DumpManager>()
    private val logBuffer = mock<LogBuffer>()
    private val loggedMessages = mutableListOf<String>()

    private lateinit var underTest: PulseController

    @Before
    fun setUp() {
        whenever(settingsRepository.config).thenReturn(settings)
        whenever(themeRepository.nightMode).thenReturn(nightMode)
        whenever(playbackRepository.target).thenReturn(playback)
        whenever(keyguardTransitionInteractor.isFinishedIn(Scenes.Gone, KeyguardState.GONE))
            .thenReturn(keyguardGone)
        whenever(powerInteractor.isAwake).thenReturn(awake)
        whenever(batteryController.isPowerSave).thenReturn(false)
        whenever(systemClock.elapsedRealtime()).thenAnswer { scheduler.currentTime }
        whenever(windowController.show(any())).thenReturn(true)
        whenever(windowController.updateHeight(any())).thenReturn(true)
        // LogBuffer.dump needs android.icu (unavailable on the plain JVM): record printed messages.
        whenever(logBuffer.obtain(any(), any(), any(), anyOrNull())).thenAnswer {
            LogMessageImpl.create().apply {
                reset(
                    it.getArgument(0),
                    it.getArgument(1),
                    0L,
                    it.getArgument(2),
                    it.getArgument(3),
                )
            }
        }
        doAnswer {
                val message = it.getArgument<LogMessage>(0)
                loggedMessages += message.messagePrinter(message)
            }
            .whenever(logBuffer)
            .commit(any())
        underTest = createController(displayId = 0)
    }

    @After
    fun tearDown() {
        displayScope.cancel()
    }

    @Test
    fun secondaryDisplay_startIsInert() {
        val secondary = createController(displayId = 1)
        activateHost(PulseHost.NAVIGATION_BAR)

        secondary.start()
        runMain()

        assertThat(capture.requestedSessions).isEmpty()
        verify(batteryController, never()).addCallback(any())
        verify(userTracker, never()).addCallback(any(), any())
        verify(windowController, never()).show(any())
    }

    @Test
    fun navigationBarHost_startsRequestedSessionCapture() {
        activateHost(PulseHost.NAVIGATION_BAR)

        startController()

        assertThat(capture.requestedSessions).containsExactly(SESSION)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun taskbarHost_startsRequestedSessionCapture() {
        activateHost(PulseHost.TASKBAR)

        startController()

        assertThat(capture.requestedSessions).containsExactly(SESSION)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun keyguardGone_startsCapture() {
        // Eligibility follows the keyguard transition state (mocked here), not
        // DeviceEntryInteractor.isDeviceEntered, which only emits with the scene container.
        keyguardGone.value = false
        activateHost(PulseHost.TASKBAR)
        startController()
        assertThat(capture.requestedSessions).isEmpty()

        keyguardGone.value = true
        runMain()

        assertThat(capture.requestedSessions).containsExactly(SESSION)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun missingSession_requestsOutputMixFallback() {
        playback.value = PulsePlaybackTarget(active = true, sessionId = null)
        activateHost(PulseHost.NAVIGATION_BAR)

        startController()

        assertThat(capture.requestedSessions).containsExactly(null)
    }

    @Test
    fun noActiveHost_doesNotCapture() {
        startController()

        assertThat(capture.requestedSessions).isEmpty()
    }

    @Test
    fun overlayAbsent_forFirstTwoValidFrames() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        emitFrame(validFft())
        emitFrame(validFft())

        verify(windowController, never()).show(any())
        verify(windowController, never()).setLevels(any())
        verify(windowController, never()).setColor(any())
    }

    @Test
    fun thirdValidFrame_showsOverlay() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        emitFrame(validFft())
        emitFrame(ByteArray(FFT_SIZE))
        emitFrame(validFft())
        verify(windowController, never()).show(any())
        emitFrame(validFft())

        inOrder(windowController) {
            verify(windowController).setColor(DEFAULT_ARGB)
            verify(windowController).setLevels(any())
            verify(windowController).show(64)
        }
    }

    @Test
    fun runningFrames_renderLevels() {
        showOverlay()
        clearInvocations(windowController)

        emitFrame(validFft())

        verify(windowController).setLevels(any())
    }

    @Test
    fun sessionChange_stopsBeforeReplacementStart() {
        showOverlay()

        playback.value = PulsePlaybackTarget(active = true, sessionId = SESSION + 1)
        runMain()

        assertThat(capture.requestedSessions).containsExactly(SESSION, SESSION + 1).inOrder()
        assertThat(capture.events).containsAtLeast("start", "stop", "start").inOrder()
        assertThat(capture.maxActiveCount).isEqualTo(1)
        assertThat(capture.activeCount).isEqualTo(1)
        verify(windowController, atLeastOnce()).hide()
    }

    @Test
    fun hostNone_stopsBeforeReplacementActivation() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        hostState.deactivate()
        runMain()
        assertThat(capture.activeCount).isEqualTo(0)

        activateHost(PulseHost.TASKBAR)
        runMain()

        assertThat(capture.requestedSessions).containsExactly(SESSION, SESSION)
        assertThat(capture.activeCount).isEqualTo(1)
        assertThat(capture.maxActiveCount).isEqualTo(1)
    }

    @Test
    fun startupTimeout_hidesAndLatches() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        emitFrame(validFft())
        emitFrame(validFft())

        advanceTime(1_100)

        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, never()).show(any())
        verify(windowController, atLeastOnce()).hide()

        advanceTime(10_000)
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun silenceTimeout_hidesAndLatches() {
        showOverlay()
        clearInvocations(windowController)

        advanceTime(2_100)

        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, atLeastOnce()).hide()

        advanceTime(10_000)
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun invalidFramesWhileRunning_doNotRefreshSilenceDeadline() {
        showOverlay()

        repeat(10) {
            advanceTime(200)
            emitFrame(ByteArray(FFT_SIZE))
        }

        assertThat(capture.activeCount).isEqualTo(0)
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun queuedFrameAfterTimeoutTeardown_isDropped() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        emitFrame(validFft())
        emitFrame(validFft())
        // The third (readiness) frame is captured but still queued for the main thread.
        capture.emit(validFft())

        scheduler.advanceTimeBy(1_100)
        scheduler.runCurrent()
        assertThat(capture.activeCount).isEqualTo(0)
        clearInvocations(windowController)
        runMain()

        verify(windowController, never()).show(any())
        verify(windowController, never()).setLevels(any())
        verify(windowController, never()).hide()
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun queuedFrameFromOldEpoch_doesNotCountTowardsRestartedCapture() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        emitFrame(validFft())
        emitFrame(validFft())
        capture.emit(validFft())
        // Time out and restart capture while the old frame is still queued for the main thread.
        scheduler.advanceTimeBy(1_100)
        scheduler.runCurrent()
        awake.value = false
        scheduler.runCurrent()
        awake.value = true
        scheduler.runCurrent()
        assertThat(capture.requestedSessions).hasSize(2)
        runMain()

        emitFrame(validFft())
        emitFrame(validFft())

        verify(windowController, never()).show(any())
    }

    @Test
    fun relevantEligibilityChange_clearsLatch() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        advanceTime(1_100)
        assertThat(capture.activeCount).isEqualTo(0)

        awake.value = false
        runMain()
        assertThat(capture.requestedSessions).hasSize(1)
        awake.value = true
        runMain()

        assertThat(capture.requestedSessions).hasSize(2)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun playbackTargetChange_clearsLatch() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        advanceTime(1_100)

        playback.value = PulsePlaybackTarget(active = true, sessionId = SESSION + 1)
        runMain()

        assertThat(capture.requestedSessions).containsExactly(SESSION, SESSION + 1).inOrder()
    }

    @Test
    fun colorOrHeightChange_doesNotClearLatch() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        advanceTime(1_100)

        settings.value = PulseConfig(enabled = true, color = 0x112233, heightDp = 72)
        runMain()

        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun colorAndHeight_doNotRestartCapture() {
        showOverlay()

        settings.value = PulseConfig(enabled = true, color = 0x112233, heightDp = 72)
        runMain()

        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.stopCount).isEqualTo(0)
        verify(windowController).setColor(0xD9112233.toInt())
        verify(windowController).updateHeight(72)
    }

    @Test
    fun configuredAlpha_isComposedIntoOverlayColor() {
        settings.value = settings.value.copy(alpha = 0x80)

        showOverlay()

        verify(windowController).setColor(0x80123456.toInt())
    }

    @Test
    fun alphaChange_updatesOverlayColorWithoutRestart() {
        showOverlay()

        settings.value = settings.value.copy(alpha = 0x40)
        runMain()

        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.stopCount).isEqualTo(0)
        verify(windowController).setColor(0x40123456)
        verify(windowController, never()).hide()
        verify(windowController, times(1)).show(any())
    }

    @Test
    fun overlayShow_appliesConfiguredBoost() {
        settings.value = settings.value.copy(boost = 0)

        showOverlay()

        inOrder(windowController) {
            verify(windowController).setBoost(0)
            verify(windowController).show(any())
        }
    }

    @Test
    fun boostChange_updatesCurveWithoutRestart() {
        showOverlay()
        val epochBefore = dumpValue("captureEpoch")

        settings.value = settings.value.copy(boost = 100)
        runMain()

        assertThat(dumpValue("captureEpoch")).isEqualTo(epochBefore)
        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.stopCount).isEqualTo(0)
        verify(windowController).setBoost(100)
        verify(windowController, never()).hide()
        verify(windowController, times(1)).show(any())
    }

    @Test
    fun heightUpdateFailure_tearsDownAndLatches() {
        showOverlay()
        whenever(windowController.updateHeight(any())).thenReturn(false)

        settings.value = PulseConfig(enabled = true, color = COLOR, heightDp = 72)
        runMain()

        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, atLeastOnce()).hide()
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun everyFalseEligibilityGate_tearsDown() {
        showOverlay()
        val batteryCallback = captureBatteryCallback()

        val gates: List<Pair<String, (Boolean) -> Unit>> =
            listOf(
                "disabled" to { on -> settings.value = settings.value.copy(enabled = !on) },
                "no host" to
                    { on ->
                        if (on) hostState.deactivate() else activateHost(PulseHost.NAVIGATION_BAR)
                    },
                "navigation hidden" to
                    { on ->
                        hostState.updateNavigationVisible(PulseHost.NAVIGATION_BAR, !on)
                    },
                "screen pinning" to
                    { on ->
                        hostState.updateScreenPinning(PulseHost.NAVIGATION_BAR, on)
                    },
                "keyguard not gone" to { on -> keyguardGone.value = !on },
                "asleep" to { on -> awake.value = !on },
                "battery saver" to { on -> batteryCallback.onPowerSaveChanged(on) },
                "playback stopped" to
                    { on ->
                        playback.value = PulsePlaybackTarget(active = !on, sessionId = SESSION)
                    },
            )

        for ((name, applyGate) in gates) {
            clearInvocations(windowController)
            applyGate(true)
            runMain()
            assertWithMessage("$name active").that(capture.activeCount).isEqualTo(0)
            verify(windowController, atLeastOnce()).hide()

            applyGate(false)
            runMain()
            assertWithMessage("$name restarted").that(capture.activeCount).isEqualTo(1)
            makeReady()
        }
        assertThat(capture.maxActiveCount).isEqualTo(1)
    }

    @Test
    fun userChange_tearsDownBeforeRestart() {
        showOverlay()
        val userCallback =
            argumentCaptor<UserTracker.Callback>().run {
                verify(userTracker).addCallback(capture(), any())
                firstValue
            }
        clearInvocations(windowController)

        userCallback.onUserChanged(10, mock<Context>())
        runMain()

        assertThat(capture.events).containsAtLeast("start", "stop", "start").inOrder()
        assertThat(capture.maxActiveCount).isEqualTo(1)
        verify(windowController, atLeastOnce()).hide()
        verify(windowController, never()).show(any())
    }

    @Test
    fun captureStartFailure_cleansUpAndLatches() {
        capture.failNextStart = true
        activateHost(PulseHost.NAVIGATION_BAR)

        startController()
        advanceTime(5_000)

        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, never()).show(any())
    }

    @Test
    fun runtimeCaptureFailure_cleansUpAndLatches() {
        showOverlay()
        clearInvocations(windowController)

        capture.fail()
        runMain()

        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, atLeastOnce()).hide()
        advanceTime(5_000)
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun windowShowFailure_cleansUpAndLatches() {
        whenever(windowController.show(any())).thenReturn(false)
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        makeReady()

        assertThat(capture.activeCount).isEqualTo(0)
        verify(windowController, atLeastOnce()).hide()
        advanceTime(5_000)
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun stop_releasesCaptureSynchronously() {
        showOverlay()
        val batteryCallback = captureBatteryCallback()
        clearInvocations(windowController)

        // Production order: the display scope is cancelled before lifecycle listeners stop.
        displayScope.cancel()
        underTest.stop()

        assertThat(capture.activeCount).isEqualTo(0)
        verify(batteryController).removeCallback(batteryCallback)
        verify(userTracker).removeCallback(any())
        runMain()
        verify(windowController, atLeastOnce()).hide()
        assertThat(capture.requestedSessions).hasSize(1)
    }

    @Test
    fun stop_isIdempotent() {
        showOverlay()

        underTest.stop()
        underTest.stop()
        runMain()

        assertThat(capture.activeCount).isEqualTo(0)
        verify(batteryController).removeCallback(any())
    }

    @Test
    fun stop_clearsFailureLatchSoRestartCanActivate() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        advanceTime(1_100)
        assertThat(capture.requestedSessions).hasSize(1)

        underTest.stop()
        runMain()
        underTest.start()
        runMain()

        assertThat(capture.requestedSessions).hasSize(2)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun maxActiveVisualizers_remainsOne() {
        showOverlay()

        playback.value = PulsePlaybackTarget(active = true, sessionId = SESSION + 1)
        runMain()
        hostState.deactivate()
        activateHost(PulseHost.TASKBAR)
        runMain()
        playback.value = PulsePlaybackTarget(active = true, sessionId = null)
        runMain()
        settings.value = settings.value.copy(heightDp = 20)
        runMain()

        assertThat(capture.maxActiveCount).isEqualTo(1)
        assertThat(capture.activeCount).isEqualTo(1)
    }

    @Test
    fun start_registersDumpable_stopUnregisters() {
        startController()
        verify(dumpManager).registerNormalDumpable("PulseController", underTest)

        underTest.stop()

        verify(dumpManager).unregisterDumpable("PulseController")
    }

    @Test
    fun secondaryDisplay_doesNotRegisterDumpable() {
        createController(displayId = 1).start()
        runMain()

        verify(dumpManager, never()).registerNormalDumpable(any(), any())
    }

    @Test
    fun duplicateDumpableName_doesNotBreakStartOrUnregisterOwner() {
        doThrow(IllegalArgumentException("'PulseController' is already registered"))
            .whenever(dumpManager)
            .registerNormalDumpable(eq("PulseController"), any())
        activateHost(PulseHost.NAVIGATION_BAR)

        startController()
        underTest.stop()

        assertThat(capture.requestedSessions).containsExactly(SESSION)
        verify(dumpManager, never()).unregisterDumpable(any())
    }

    @Test
    fun dump_listsEveryGateAndRuntimeState() {
        showOverlay()

        val dump = dump()

        for (name in DUMP_FIELDS) {
            assertWithMessage(name).that(dump).containsMatch("(?m)^$name=")
        }
        assertThat(dump).contains("eligible=true\n")
        assertThat(dump).contains("blockedBy=none\n")
        assertThat(dump).contains("captureActive=true\n")
        assertThat(dump).contains("frameGateReady=true\n")
        assertThat(dump).contains("overlayShown=true\n")
    }

    @Test
    fun dump_reportsCurrentAlpha() {
        settings.value = settings.value.copy(alpha = 128)
        showOverlay()

        assertThat(dump()).contains("alpha=128\n")
    }

    @Test
    fun dump_reportsCurrentBoost() {
        settings.value = settings.value.copy(boost = 100)
        showOverlay()

        assertThat(dump()).contains("boost=100\n")
    }

    @Test
    fun dump_reportsFirstFalseGate() {
        playback.value = PulsePlaybackTarget(active = false, sessionId = SESSION)
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        val dump = dump()

        assertThat(dump).contains("playbackActive=false\n")
        assertThat(dump).contains("eligible=false\n")
        assertThat(dump).contains("blockedBy=playbackActive\n")
        assertThat(dump).contains("captureActive=false\n")
    }

    @Test
    fun dump_reportsKeyguardGoneGate() {
        keyguardGone.value = false
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        assertThat(dump()).contains("blockedBy=keyguardGone\n")
    }

    @Test
    fun dump_reportsFailureLatch() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        advanceTime(1_100)

        val dump = dump()

        assertThat(dump).contains("activationFailed=true\n")
        assertThat(dump).contains("blockedBy=activationFailed\n")
    }

    @Test
    fun dump_neverContainsSessionId() {
        showOverlay()

        val dump = dump()

        assertThat(dump).contains("sessionSelected=true\n")
        assertThat(dump).doesNotContain(SESSION.toString())
    }

    @Test
    fun logBuffer_recordsTransitionsWithoutSessionId() {
        playback.value = PulsePlaybackTarget(active = false, sessionId = SESSION)
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        playback.value = PulsePlaybackTarget(active = true, sessionId = SESSION)
        runMain()
        makeReady()
        advanceTime(2_100)

        val messages = logMessages()
        val log = messages.joinToString("\n")

        assertThat(log).contains("eligible=false blockedBy=playbackActive")
        assertThat(log).contains("eligible=true blockedBy=none")
        assertThat(log).contains("capture started selected=true")
        assertThat(log).contains("overlay shown")
        assertThat(log).contains("failed stage=silence timeout")
        assertThat(log).contains("capture stopped")
        for (message in messages) assertThat(message).doesNotContain(SESSION.toString())
    }

    @Test
    fun logBuffer_doesNotLogPerFrame() {
        showOverlay()
        val before = logMessages().size

        repeat(20) { emitFrame(validFft()) }

        assertThat(logMessages()).hasSize(before)
    }

    @Test
    fun matchTheme_drawsBlackBarsWithLightTheme() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        nightMode.value = false

        showOverlay()

        verify(windowController).setColor(0xD9000000.toInt())
    }

    @Test
    fun matchTheme_drawsWhiteBarsWithDarkTheme() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        nightMode.value = true

        showOverlay()

        verify(windowController).setColor(0xD9FFFFFF.toInt())
    }

    @Test
    fun matchTheme_appliesConfiguredAlpha() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME, alpha = 0x40)
        nightMode.value = true

        showOverlay()

        verify(windowController).setColor(0x40FFFFFF)
    }

    @Test
    fun matchTheme_themeToggleRecolorsLiveWithoutRestartingCapture() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        showOverlay()
        val epochBefore = dumpValue("captureEpoch")

        nightMode.value = true
        runMain()
        nightMode.value = false
        runMain()

        inOrder(windowController) {
            verify(windowController).setColor(0xD9000000.toInt())
            verify(windowController).setColor(0xD9FFFFFF.toInt())
            verify(windowController).setColor(0xD9000000.toInt())
        }
        assertThat(dumpValue("captureEpoch")).isEqualTo(epochBefore)
        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.stopCount).isEqualTo(0)
        verify(windowController, never()).hide()
        verify(windowController, times(1)).show(any())
    }

    @Test
    fun solid_themeToggleDoesNotRecolor() {
        showOverlay()

        nightMode.value = true
        runMain()

        verify(windowController, times(1)).setColor(any())
    }

    @Test
    fun colorModeChange_recolorsWithoutRestart() {
        nightMode.value = true
        showOverlay()

        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        runMain()

        verify(windowController).setColor(0xD9FFFFFF.toInt())
        assertThat(capture.requestedSessions).hasSize(1)
        assertThat(capture.stopCount).isEqualTo(0)
    }

    @Test
    fun themeToggleWhileHidden_appliesOnNextShow() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        playback.value = PulsePlaybackTarget(active = false, sessionId = SESSION)
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()

        nightMode.value = true
        runMain()
        verify(windowController, never()).setColor(any())

        playback.value = PulsePlaybackTarget(active = true, sessionId = SESSION)
        runMain()
        makeReady()

        verify(windowController).setColor(0xD9FFFFFF.toInt())
    }

    @Test
    fun dump_reportsColorModeNightModeAndEffectiveColor() {
        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        nightMode.value = true
        showOverlay()

        val dump = dump()

        assertThat(dump).contains("colorMode=MATCH_THEME\n")
        assertThat(dump).contains("nightMode=true\n")
        assertThat(dump).contains("effectiveColor=#D9FFFFFF\n")
    }

    @Test
    fun logBuffer_recordsThemeAndColorModeTransitionsOnce() {
        showOverlay()
        val before = logMessages().size

        settings.value = settings.value.copy(colorMode = PulseColorMode.MATCH_THEME)
        runMain()
        nightMode.value = true
        runMain()
        repeat(5) { emitFrame(validFft()) }

        val added = logMessages().drop(before)
        assertThat(added).containsExactly("colorMode=MATCH_THEME", "nightMode=true").inOrder()
    }

    private fun dumpValue(name: String): String =
        dump().lineSequence().first { it.startsWith("$name=") }.substringAfter('=')

    private fun dump(): String =
        StringWriter().also { underTest.dump(PrintWriter(it), emptyArray()) }.toString()

    private fun dumpValue(name: String): String =
        Regex("(?m)^$name=(.*)$").find(dump())?.groupValues?.get(1)
            ?: error("no $name in dump")

    private fun logMessages(): List<String> = loggedMessages.toList()

    private fun createController(displayId: Int) =
        PulseController(
            settingsRepository,
            themeRepository,
            playbackRepository,
            hostState,
            capture,
            PulseFrameGate(),
            PulseSpectrumProcessor(),
            windowController,
            keyguardTransitionInteractor,
            powerInteractor,
            batteryController,
            userTracker,
            systemClock,
            dumpManager,
            logBuffer,
            displayId,
            displayScope,
            mainDispatcher,
            mainExecutor,
        )

    private fun startController() {
        underTest.start()
        runMain()
    }

    private fun showOverlay() {
        activateHost(PulseHost.NAVIGATION_BAR)
        startController()
        makeReady()
        verify(windowController).show(any())
    }

    private fun makeReady() = repeat(3) { emitFrame(validFft()) }

    private fun activateHost(host: PulseHost) {
        hostState.activate(host)
        hostState.updateNavigationVisible(host, true)
    }

    private fun captureBatteryCallback(): BatteryController.BatteryStateChangeCallback =
        argumentCaptor<BatteryController.BatteryStateChangeCallback>().run {
            verify(batteryController).addCallback(capture())
            firstValue
        }

    private fun emitFrame(fft: ByteArray) {
        capture.emit(fft)
        runMain()
    }

    private fun advanceTime(ms: Long) {
        scheduler.advanceTimeBy(ms)
        runMain()
    }

    /** Drains the main executor and the main dispatcher until both are idle. */
    private fun runMain() {
        do {
            scheduler.runCurrent()
        } while (mainExecutor.runAll())
    }

    private fun validFft() =
        ByteArray(FFT_SIZE).apply {
            this[2] = 100
            this[3] = 100
        }

    private class QueueExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queue.addLast(command)
        }

        /** Returns true when at least one task ran. */
        fun runAll(): Boolean {
            var ran = false
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
                ran = true
            }
            return ran
        }
    }

    private class FakePulseAudioCapture : PulseAudioCapture {
        val requestedSessions = mutableListOf<Int?>()
        val events = mutableListOf<String>()
        var activeCount = 0
        var maxActiveCount = 0
        var stopCount = 0
        var failNextStart = false
        private var fftConsumer: ((ByteArray) -> Unit)? = null
        private var failureConsumer: (() -> Unit)? = null

        override fun start(
            requestedSessionId: Int?,
            onFftData: (ByteArray) -> Unit,
            onFailure: () -> Unit,
        ): Boolean {
            requestedSessions += requestedSessionId
            if (failNextStart) {
                failNextStart = false
                events += "failed start"
                onFailure()
                return false
            }
            events += "start"
            activeCount++
            maxActiveCount = maxOf(maxActiveCount, activeCount)
            fftConsumer = onFftData
            failureConsumer = onFailure
            return true
        }

        override fun stop() {
            if (activeCount > 0) {
                activeCount--
                events += "stop"
                stopCount++
            }
            fftConsumer = null
            failureConsumer = null
        }

        fun emit(fft: ByteArray) = fftConsumer?.invoke(fft)

        fun fail() = failureConsumer?.invoke()
    }

    private companion object {
        const val COLOR = 0x123456
        const val DEFAULT_ARGB = 0xD9123456.toInt()
        const val SESSION = 42
        const val FFT_SIZE = 512
        val DUMP_FIELDS =
            listOf(
                "started",
                "displayId",
                "enabled",
                "activeHost",
                "navigationVisible",
                "screenPinningActive",
                "keyguardGone",
                "awake",
                "powerSave",
                "playbackActive",
                "sessionSelected",
                "activationFailed",
                "eligible",
                "blockedBy",
                "captureActive",
                "captureEpoch",
                "frameGateReady",
                "overlayShown",
                "windowAttached",
                "alpha",
                "colorMode",
                "nightMode",
                "effectiveColor",
                "boost",
            )
    }
}
