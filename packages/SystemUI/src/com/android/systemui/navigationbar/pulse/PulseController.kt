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

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.navigationbar.NavigationBarComponent.NavigationBarScope
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.policy.BatteryController
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@NavigationBarScope
class PulseController
@Inject
constructor(
    private val settingsRepository: PulseSettingsRepository,
    private val playbackRepository: PulsePlaybackRepository,
    private val audioCapture: PulseAudioCapture,
    private val spectrumProcessor: PulseSpectrumProcessor,
    private val windowController: PulseWindowController,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val powerInteractor: PowerInteractor,
    private val batteryController: BatteryController,
    @param:Application private val applicationScope: CoroutineScope,
    @param:Main private val mainDispatcher: CoroutineDispatcher,
    @param:Main private val mainExecutor: Executor,
) {
    private var collectionJob: Job? = null
    private var config = PulseConfig(enabled = false, color = DEFAULT_COLOR, heightDp = 48)
    private var primaryDisplay = false
    private var attached = false
    private var destroyed = false
    private var navigationWindowVisible = false
    private var aggregateVisible = false
    private var deviceEntered = false
    private var awake = false
    private var playbackActive = false
    private var powerSave = false
    private var screenPinningActive = false
    private var running = false
    private var activationFailed = false
    private var batteryCallbackRegistered = false

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onPowerSaveChanged(isPowerSave: Boolean) {
                powerSave = isPowerSave
                recompute()
            }
        }

    fun init(isPrimaryDisplay: Boolean) {
        primaryDisplay = isPrimaryDisplay
    }

    fun attach() {
        if (attached || destroyed) return
        attached = true
        if (!primaryDisplay) return

        powerSave = batteryController.isPowerSave
        batteryController.addCallback(batteryCallback)
        batteryCallbackRegistered = true
        collectionJob =
            applicationScope.launch(mainDispatcher) {
                launch {
                    settingsRepository.config.collectLatest { nextConfig ->
                        val oldConfig = config
                        config = nextConfig
                        if (running && nextConfig.color != oldConfig.color) {
                            windowController.setColorRgb(nextConfig.color)
                        }
                        if (running && nextConfig.heightDp != oldConfig.heightDp) {
                            if (!windowController.updateHeight(nextConfig.heightDp)) {
                                handleActivationFailure()
                                return@collectLatest
                            }
                        }
                        recompute()
                    }
                }
                launch {
                    playbackRepository.target.collectLatest {
                        playbackActive = it.active
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
        recompute()
    }

    fun detach() {
        if (!attached) return
        attached = false
        collectionJob?.cancel()
        collectionJob = null
        if (batteryCallbackRegistered) {
            batteryController.removeCallback(batteryCallback)
            batteryCallbackRegistered = false
        }
        recompute()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        detach()
        stopRuntime()
        windowController.destroy()
    }

    fun setNavigationWindowVisible(visible: Boolean) {
        navigationWindowVisible = visible
        recompute()
    }

    fun setAggregateVisible(visible: Boolean) {
        aggregateVisible = visible
        recompute()
    }

    fun setScreenPinningActive(active: Boolean) {
        screenPinningActive = active
        recompute()
    }

    fun onConfigurationChanged() {
        if (running && !windowController.onConfigurationChanged()) {
            handleActivationFailure()
        }
    }

    private fun recompute() {
        val eligible =
            attached &&
                primaryDisplay &&
                config.enabled &&
                navigationWindowVisible &&
                aggregateVisible &&
                deviceEntered &&
                awake &&
                playbackActive &&
                !powerSave &&
                !screenPinningActive &&
                !destroyed

        if (!eligible) {
            activationFailed = false
            stopRuntime()
            return
        }
        if (activationFailed || running) return
        startRuntime()
    }

    private fun startRuntime() {
        windowController.setColorRgb(config.color)
        if (!windowController.show(config.heightDp)) {
            handleActivationFailure()
            return
        }

        val started =
            audioCapture.start(
                onFftData = { fft ->
                    val levels = synchronized(spectrumProcessor) { spectrumProcessor.process(fft) }
                    windowController.setLevels(levels)
                },
                onFailure = { mainExecutor.execute(::handleActivationFailure) },
            )
        if (!started || activationFailed) {
            handleActivationFailure()
            return
        }
        running = true
    }

    private fun handleActivationFailure() {
        activationFailed = true
        stopRuntime()
        synchronized(spectrumProcessor) { spectrumProcessor.reset() }
        windowController.hide()
    }

    private fun stopRuntime() {
        if (!running) return
        running = false
        audioCapture.stop()
        synchronized(spectrumProcessor) { spectrumProcessor.reset() }
        windowController.hide()
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFFFFFF
    }
}
