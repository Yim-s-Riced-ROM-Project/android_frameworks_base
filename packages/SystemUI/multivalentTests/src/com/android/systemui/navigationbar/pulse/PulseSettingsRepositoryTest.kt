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
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseSettingsRepositoryTest : SysuiTestCase() {
    private val secureSettingsRepository = FakeSecureSettingsRepository()
    private val underTest = PulseSettingsRepository(secureSettingsRepository)

    @Test
    fun config_defaultsToDisabledWhiteAnd48Dp() = runTest {
        assertThat(underTest.config.first())
            .isEqualTo(PulseConfig(enabled = false, color = 0xFFFFFF, heightDp = 48))
    }

    @Test
    fun config_combinesSettingUpdates() = runTest {
        secureSettingsRepository.setBoolean(PulseSettingsRepository.ENABLED_KEY, true)
        secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_KEY, 0x123456)
        secureSettingsRepository.setInt(PulseSettingsRepository.HEIGHT_KEY, 72)

        assertThat(underTest.config.first())
            .isEqualTo(PulseConfig(enabled = true, color = 0x123456, heightDp = 72))
    }

    @Test
    fun config_masksColorToLower24Bits() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_KEY, 0xAB123456.toInt())

        assertThat(underTest.config.first().color).isEqualTo(0x123456)
    }

    @Test
    fun config_clampsHeightToSupportedRange() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.HEIGHT_KEY, 2)
        assertThat(underTest.config.first().heightDp).isEqualTo(8)

        secureSettingsRepository.setInt(PulseSettingsRepository.HEIGHT_KEY, 120)
        assertThat(underTest.config.first().heightDp).isEqualTo(96)
    }
}
