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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseControllerTest : SysuiTestCase() {
    @Mock private lateinit var settingsRepository: PulseSettingsRepository
    @Mock private lateinit var playbackRepository: PulsePlaybackRepository
    @Mock private lateinit var windowController: PulseWindowController
    @Mock private lateinit var deviceEntryInteractor: DeviceEntryInteractor
    @Mock private lateinit var powerInteractor: PowerInteractor
    @Mock private lateinit var batteryController: BatteryController

    private val settings = MutableStateFlow(PulseConfig(false, 0xFFFFFF, 48))
    private val playbackActive = MutableStateFlow(false)
    private val deviceEntered = MutableStateFlow(true)
    private val awake = MutableStateFlow(true)
    private val capture = FakePulseAudioCapture()
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private lateinit var underTest: PulseController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(settingsRepository.config).thenReturn(settings)
        whenever(playbackRepository.isPlaybackActive).thenReturn(playbackActive)
        whenever(deviceEntryInteractor.isDeviceEntered).thenReturn(deviceEntered)
        whenever(powerInteractor.isAwake).thenReturn(awake)
        whenever(batteryController.isPowerSave).thenReturn(false)
        whenever(windowController.show(any())).thenReturn(true)
        underTest =
            PulseController(
                settingsRepository,
                playbackRepository,
                capture,
                PulseSpectrumProcessor(),
                windowController,
                deviceEntryInteractor,
                powerInteractor,
                batteryController,
                testScope,
                UnconfinedTestDispatcher(testScope.testScheduler),
                Executor(Runnable::run),
            )
        underTest.init(isPrimaryDisplay = true)
        underTest.setNavigationWindowVisible(true)
        underTest.setAggregateVisible(true)
        underTest.setScreenPinningActive(false)
    }

    @Test
    fun allConditionsTrue_startsCaptureAndShowsWindow() {
        settings.value = PulseConfig(true, 0x123456, 64)
        playbackActive.value = true

        underTest.attach()
        testScope.runCurrent()

        assertThat(capture.startCount).isEqualTo(1)
        verify(windowController).setColorRgb(0x123456)
        verify(windowController).show(64)
    }

    @Test
    fun settingDisabled_doesNotStart() {
        playbackActive.value = true

        underTest.attach()
        testScope.runCurrent()

        assertThat(capture.startCount).isEqualTo(0)
        verify(windowController, never()).show(any())
    }

    @Test
    fun batterySaver_stopsAndHidesImmediately() {
        settings.value = PulseConfig(true, 0xFFFFFF, 48)
        playbackActive.value = true
        underTest.attach()
        testScope.runCurrent()
        val callback =
            argumentCaptor<BatteryController.BatteryStateChangeCallback>().run {
                verify(batteryController).addCallback(capture())
                firstValue
            }

        callback.onPowerSaveChanged(true)

        assertThat(capture.stopCount).isEqualTo(1)
        verify(windowController).hide()
    }

    @Test
    fun colorAndHeightChange_doesNotRestartCapture() {
        settings.value = PulseConfig(true, 0xFFFFFF, 48)
        playbackActive.value = true
        underTest.attach()
        testScope.runCurrent()

        settings.value = PulseConfig(true, 0x112233, 72)
        testScope.runCurrent()

        assertThat(capture.startCount).isEqualTo(1)
        verify(windowController).setColorRgb(0x112233)
        verify(windowController).updateHeight(72)
    }

    @Test
    fun captureFailure_latchesUntilEligibilityResets() {
        settings.value = PulseConfig(true, 0xFFFFFF, 48)
        playbackActive.value = true
        underTest.attach()
        testScope.runCurrent()

        capture.fail()
        testScope.runCurrent()
        underTest.setNavigationWindowVisible(true)
        assertThat(capture.startCount).isEqualTo(1)

        playbackActive.value = false
        playbackActive.value = true
        testScope.runCurrent()

        assertThat(capture.startCount).isEqualTo(2)
    }

    @Test
    fun secondaryDisplay_registersNothing() {
        underTest.destroy()
        underTest = underTestForSecondaryDisplay()

        underTest.attach()
        testScope.runCurrent()

        assertThat(capture.startCount).isEqualTo(0)
        verify(batteryController, never()).addCallback(any())
    }

    private fun underTestForSecondaryDisplay(): PulseController {
        return PulseController(
                settingsRepository,
                playbackRepository,
                capture,
                PulseSpectrumProcessor(),
                windowController,
                deviceEntryInteractor,
                powerInteractor,
                batteryController,
                testScope,
                UnconfinedTestDispatcher(testScope.testScheduler),
                Executor(Runnable::run),
            )
            .also { it.init(isPrimaryDisplay = false) }
    }

    private class FakePulseAudioCapture : PulseAudioCapture {
        var startCount = 0
        var stopCount = 0
        private var failure: (() -> Unit)? = null

        override fun start(onFftData: (ByteArray) -> Unit, onFailure: () -> Unit): Boolean {
            startCount++
            failure = onFailure
            return true
        }

        override fun stop() {
            stopCount++
        }

        fun fail() {
            failure?.invoke()
        }
    }
}
