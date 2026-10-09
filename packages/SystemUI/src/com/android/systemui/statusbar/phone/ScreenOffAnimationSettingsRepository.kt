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

package com.android.systemui.statusbar.phone

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

/** Screen-off effect chosen by the user. Declaration order matches the stored values 0–4. */
enum class ScreenOffAnimationSelection {
    STOCK,
    CRT,
    TEAR,
    CORRUPT,
    SIGNAL_LOSS,
}

/** Raw per-user setting value and the typed selection it resolves to. */
data class ScreenOffAnimationSetting(val rawValue: Int, val selection: ScreenOffAnimationSelection)

/**
 * Observes the private per-user [SETTING_KEY] secure setting: 0 Stock, 1 CRT, 2 Tear, 3 Corrupt,
 * 4 Signal loss. Missing, unknown, and unreadable values resolve to
 * [ScreenOffAnimationSelection.STOCK]. A failed read publishes Stock and is retried a bounded
 * number of times; if every retry fails, Stock stays until SystemUI restarts.
 */
@SysUISingleton
class ScreenOffAnimationSettingsRepository
@Inject
constructor(
    secureSettingsRepository: SecureSettingsRepository,
    @Background backgroundScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
) {
    val setting: StateFlow<ScreenOffAnimationSetting> =
        secureSettingsRepository
            .intSetting(SETTING_KEY, defaultValue = STOCK_VALUE)
            .map(::settingFromValue)
            .retryWhen { cause, attempt ->
                Log.w(TAG, "setting read failed exception=${cause.javaClass.simpleName}")
                emit(STOCK_SETTING)
                val retry = attempt < MAX_READ_RETRIES
                if (retry) delay(READ_RETRY_DELAY_MS)
                retry
            }
            // Retries exhausted: keep Stock rather than failing the background scope.
            .catch { Log.w(TAG, "setting read retries exhausted, using stock") }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(backgroundScope, SharingStarted.Eagerly, STOCK_SETTING)

    companion object {
        const val SETTING_KEY = "lineage_screen_off_animation"
        private const val TAG = "ScreenOffAnimationSettings"
        private const val MAX_READ_RETRIES = 3L
        private const val READ_RETRY_DELAY_MS = 1_000L
        private const val STOCK_VALUE = 0
        private val STOCK_SETTING =
            ScreenOffAnimationSetting(STOCK_VALUE, ScreenOffAnimationSelection.STOCK)

        private fun settingFromValue(value: Int): ScreenOffAnimationSetting =
            ScreenOffAnimationSetting(
                rawValue = value,
                selection =
                    ScreenOffAnimationSelection.entries.getOrNull(value)
                        ?: ScreenOffAnimationSelection.STOCK,
            )
    }
}
