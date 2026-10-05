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

/** The playback Pulse should visualize. [sessionId] is null when no positive session is known. */
data class PulsePlaybackTarget(val active: Boolean, val sessionId: Int?) {
    companion object {
        val INACTIVE = PulsePlaybackTarget(active = false, sessionId = null)
    }
}

/** Reports the local playback suitable for Pulse visualization, keeping selection stable. */
@SysUISingleton
class PulsePlaybackRepository @Inject constructor(private val audioManager: AudioManager) {
    /**
     * Cold flow: every collector gets its own [TargetSelector], so selection state is never shared
     * between collectors.
     */
    val target: Flow<PulsePlaybackTarget> =
        conflatedCallbackFlow {
                val selector = TargetSelector()
                val callback =
                    object : AudioManager.AudioPlaybackCallback() {
                        override fun onPlaybackConfigChanged(
                            configs: List<AudioPlaybackConfiguration>
                        ) {
                            trySend(selector.select(configs))
                        }
                    }

                audioManager.registerAudioPlaybackCallback(callback, null)
                trySend(selector.select(audioManager.activePlaybackConfigurations))
                awaitClose { audioManager.unregisterAudioPlaybackCallback(callback) }
            }
            .distinctUntilChanged()

    private data class SelectedPlayer(val playerInterfaceId: Int, val sessionId: Int?)

    /** Selection state for one collection. Player ids are only used here, never logged. */
    private class TargetSelector {
        private var selectedPlayer: SelectedPlayer? = null
        private var previousEligiblePlayerIds: Set<Int> = emptySet()

        @Synchronized
        fun select(configurations: List<AudioPlaybackConfiguration>): PulsePlaybackTarget {
            val eligible = configurations.filter { it.isEligibleForPulse() }
            val retained =
                selectedPlayer?.let { selected ->
                    eligible.firstOrNull { it.playerInterfaceId == selected.playerInterfaceId }
                }
            val chosen =
                retained
                    ?: eligible.firstOrNull {
                        it.playerInterfaceId !in previousEligiblePlayerIds && it.sessionId > 0
                    }
                    ?: eligible.firstOrNull { it.sessionId > 0 }
                    ?: eligible.firstOrNull()
            previousEligiblePlayerIds = eligible.mapTo(mutableSetOf()) { it.playerInterfaceId }
            selectedPlayer =
                chosen?.let {
                    SelectedPlayer(it.playerInterfaceId, it.sessionId.takeIf { id -> id > 0 })
                }
            return selectedPlayer?.let {
                PulsePlaybackTarget(active = true, sessionId = it.sessionId)
            } ?: PulsePlaybackTarget.INACTIVE
        }

        private fun AudioPlaybackConfiguration.isEligibleForPulse(): Boolean =
            isActive && audioAttributes.usage in VISUALIZABLE_USAGES && !isRemoteSubmixOnly()

        private fun AudioPlaybackConfiguration.isRemoteSubmixOnly(): Boolean {
            val devices = audioDeviceInfos
            return devices.isNotEmpty() &&
                devices.all { device -> device.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
        }
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
