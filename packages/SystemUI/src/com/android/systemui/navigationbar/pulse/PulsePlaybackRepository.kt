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

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports whether local playback suitable for Pulse visualization is active. */
@SysUISingleton
class PulsePlaybackRepository @Inject constructor(private val audioManager: AudioManager) {
    val isPlaybackActive: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(
                            configs: List<AudioPlaybackConfiguration>
                        ) {
                            trySend(configs.hasVisualizablePlayback())
                        }
                    }

                audioManager.registerAudioPlaybackCallback(callback, null)
                trySend(audioManager.activePlaybackConfigurations.hasVisualizablePlayback())
                awaitClose { audioManager.unregisterAudioPlaybackCallback(callback) }
            }
            .distinctUntilChanged()

    private fun List<AudioPlaybackConfiguration>.hasVisualizablePlayback(): Boolean {
        return any { config ->
            config.isActive &&
                config.audioAttributes.usage in VISUALIZABLE_USAGES &&
                !config.isRemoteSubmixOnly()
        }
    }

    private fun AudioPlaybackConfiguration.isRemoteSubmixOnly(): Boolean {
        val devices = audioDeviceInfos
        return devices.isNotEmpty() &&
            devices.all { device -> device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
    }

    private companion object {
        val VISUALIZABLE_USAGES =
            setOf(
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
            )
    }
}
