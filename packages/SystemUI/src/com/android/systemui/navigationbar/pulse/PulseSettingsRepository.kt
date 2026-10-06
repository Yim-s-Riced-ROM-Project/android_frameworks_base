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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** User-selected Pulse rendering configuration. [color] is RGB only; [alpha] is 0-255. */
data class PulseConfig(
    val enabled: Boolean,
    val color: Int,
    val heightDp: Int,
    val alpha: Int = PulseSettingsRepository.DEFAULT_ALPHA,
) {
    /** [color] with [alpha] in the top byte, ready for [android.graphics.Paint.setColor]. */
    val argb: Int
        get() = (alpha shl 24) or (color and 0xFFFFFF)
}

/** Provides Pulse configuration for the current user. */
@SysUISingleton
class PulseSettingsRepository
@Inject
constructor(secureSettingsRepository: SecureSettingsRepository) {
    val config: Flow<PulseConfig> =
        combine(
                secureSettingsRepository.boolSetting(ENABLED_KEY, defaultValue = false),
                secureSettingsRepository.intSetting(COLOR_KEY, defaultValue = DEFAULT_COLOR).map {
                    it and RGB_MASK
                },
                secureSettingsRepository
                    .intSetting(HEIGHT_KEY, defaultValue = DEFAULT_HEIGHT_DP)
                    .map { it.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP) },
                secureSettingsRepository.intSetting(ALPHA_KEY, defaultValue = DEFAULT_ALPHA).map {
                    it.coerceIn(MIN_ALPHA, MAX_ALPHA)
                },
            ) { enabled, color, heightDp, alpha ->
                PulseConfig(enabled, color, heightDp, alpha)
            }
            .distinctUntilChanged()

    companion object {
        const val ENABLED_KEY = "lineage_pulse_enabled"
        const val COLOR_KEY = "lineage_pulse_color"
        const val HEIGHT_KEY = "lineage_pulse_height_dp"
        const val ALPHA_KEY = "lineage_pulse_alpha"

        /** About 85%: the fixed alpha Pulse used before the setting, so unset installs match. */
        const val DEFAULT_ALPHA = 0xD9

        private const val DEFAULT_COLOR = 0xFFFFFF
        private const val DEFAULT_HEIGHT_DP = 48
        private const val MIN_HEIGHT_DP = 8
        private const val MAX_HEIGHT_DP = 96
        private const val RGB_MASK = 0xFFFFFF
        /** About 10% opacity, so a stored value can never make Pulse invisible. */
        private const val MIN_ALPHA = 26
        private const val MAX_ALPHA = 255
    }
}
