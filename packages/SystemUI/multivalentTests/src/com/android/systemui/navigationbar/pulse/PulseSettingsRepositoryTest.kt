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

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
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
            .isEqualTo(PulseConfig(enabled = false, color = 0xFFFFFF, heightDp = 48, alpha = 217))
    }

    @Test
    fun config_combinesSettingUpdates() = runTest {
        secureSettingsRepository.setBoolean(PulseSettingsRepository.ENABLED_KEY, true)
        secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_KEY, 0x123456)
        secureSettingsRepository.setInt(PulseSettingsRepository.HEIGHT_KEY, 72)
        secureSettingsRepository.setInt(PulseSettingsRepository.ALPHA_KEY, 128)

        assertThat(underTest.config.first())
            .isEqualTo(PulseConfig(enabled = true, color = 0x123456, heightDp = 72, alpha = 128))
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

    @Test
    fun config_alphaDefaultsToPreviousFixedAlpha() = runTest {
        assertThat(underTest.config.first().alpha).isEqualTo(217)
    }

    @Test
    fun pulseConfig_alphaDefaultsToPreviousFixedAlpha() {
        assertThat(PulseConfig(enabled = true, color = 0x123456, heightDp = 48).alpha)
            .isEqualTo(217)
    }

    @Test
    fun config_alphaIsIndependentOfColorHighBits() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_KEY, 0x80123456.toInt())

        assertThat(underTest.config.first().alpha).isEqualTo(217)
    }

    @Test
    fun config_clampsAlphaToSupportedRange() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.ALPHA_KEY, 0)
        assertThat(underTest.config.first().alpha).isEqualTo(26)

        secureSettingsRepository.setInt(PulseSettingsRepository.ALPHA_KEY, 25)
        assertThat(underTest.config.first().alpha).isEqualTo(26)

        secureSettingsRepository.setInt(PulseSettingsRepository.ALPHA_KEY, 300)
        assertThat(underTest.config.first().alpha).isEqualTo(255)
    }

    @Test
    fun config_passesInRangeAlphaThrough() = runTest {
        for (alpha in listOf(26, 128, 255)) {
            secureSettingsRepository.setInt(PulseSettingsRepository.ALPHA_KEY, alpha)
            assertThat(underTest.config.first().alpha).isEqualTo(alpha)
        }
    }

    @Test
    fun config_composesArgbFromAlphaAndRgb() {
        val config = PulseConfig(enabled = true, color = 0x123456, heightDp = 48, alpha = 0x80)

        assertThat(config.argb(nightMode = false)).isEqualTo(0x80123456.toInt())
        assertThat(config.argb(nightMode = true)).isEqualTo(0x80123456.toInt())
    }

    @Test
    fun config_matchThemeArgbFollowsNightMode() {
        val config =
            PulseConfig(
                enabled = true,
                color = 0x123456,
                heightDp = 48,
                alpha = 0x80,
                colorMode = PulseColorMode.MATCH_THEME,
            )

        assertThat(config.argb(nightMode = true)).isEqualTo(0x80FFFFFF.toInt())
        assertThat(config.argb(nightMode = false)).isEqualTo(0x80000000.toInt())
    }

    @Test
    fun config_colorModeDefaultsToSolid() = runTest {
        assertThat(underTest.config.first().colorMode).isEqualTo(PulseColorMode.SOLID)
    }

    @Test
    fun config_readsMatchThemeColorMode() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_MODE_KEY, 1)

        assertThat(underTest.config.first().colorMode).isEqualTo(PulseColorMode.MATCH_THEME)
    }

    @Test
    fun config_readsUnknownColorModeAsSolid() = runTest {
        for (raw in listOf(-1, 2, 99)) {
            secureSettingsRepository.setInt(PulseSettingsRepository.COLOR_MODE_KEY, raw)
            assertThat(underTest.config.first().colorMode).isEqualTo(PulseColorMode.SOLID)
        }
    }

    @Test
    fun config_boostDefaultsToTwenty() = runTest {
        assertThat(underTest.config.first().boost).isEqualTo(20)
    }

    @Test
    fun pulseConfig_boostDefaultsToTwenty() {
        assertThat(PulseConfig(enabled = true, color = 0x123456, heightDp = 48).boost)
            .isEqualTo(20)
    }

    @Test
    fun config_clampsBoostToSupportedRange() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.BOOST_KEY, -1)
        assertThat(underTest.config.first().boost).isEqualTo(0)

        secureSettingsRepository.setInt(PulseSettingsRepository.BOOST_KEY, 101)
        assertThat(underTest.config.first().boost).isEqualTo(100)
    }

    @Test
    fun config_passesInRangeBoostThrough() = runTest {
        for (boost in listOf(0, 20, 100)) {
            secureSettingsRepository.setInt(PulseSettingsRepository.BOOST_KEY, boost)
            assertThat(underTest.config.first().boost).isEqualTo(boost)
        }
    }

    @Test
    fun config_barLayoutDefaultsToLegacyLook() = runTest {
        val config = underTest.config.first()

        assertThat(config.barCount).isEqualTo(32)
        assertThat(config.barGapPercent).isEqualTo(30)
    }

    @Test
    fun pulseConfig_barLayoutDefaultsToLegacyLook() {
        val config = PulseConfig(enabled = true, color = 0x123456, heightDp = 48)

        assertThat(config.barCount).isEqualTo(32)
        assertThat(config.barGapPercent).isEqualTo(30)
    }

    @Test
    fun config_clampsBarCountToSupportedRange() = runTest {
        for ((stored, expected) in listOf(-1 to 16, 15 to 16, 65 to 64, 1000 to 64)) {
            secureSettingsRepository.setInt(PulseSettingsRepository.BAR_COUNT_KEY, stored)
            assertThat(underTest.config.first().barCount).isEqualTo(expected)
        }
    }

    @Test
    fun config_clampsBarGapToSupportedRange() = runTest {
        for ((stored, expected) in listOf(-5 to 0, 81 to 80, Int.MAX_VALUE to 80)) {
            secureSettingsRepository.setInt(PulseSettingsRepository.BAR_GAP_PERCENT_KEY, stored)
            assertThat(underTest.config.first().barGapPercent).isEqualTo(expected)
        }
    }

    @Test
    fun config_passesInRangeOffGridBarLayoutThrough() = runTest {
        secureSettingsRepository.setInt(PulseSettingsRepository.BAR_COUNT_KEY, 17)
        secureSettingsRepository.setInt(PulseSettingsRepository.BAR_GAP_PERCENT_KEY, 33)

        val config = underTest.config.first()

        assertThat(config.barCount).isEqualTo(17)
        assertThat(config.barGapPercent).isEqualTo(33)
    }

    @Test
    fun config_alphaFollowsSettingChangesForSelectedUser() {
        val kosmos = userAwareKosmos()
        kosmos.testScope.runTest {
            val underTest = PulseSettingsRepository(kosmos.userAwareSecureSettingsRepository)
            kosmos.fakeUserRepository.setSelectedUserInfo(USER_1)
            val config by collectLastValue(underTest.config)
            runCurrent()
            assertThat(config?.alpha).isEqualTo(100)

            kosmos.fakeSettings.putIntForUser(PulseSettingsRepository.ALPHA_KEY, 150, USER_1.id)
            runCurrent()

            assertThat(config?.alpha).isEqualTo(150)
        }
    }

    @Test
    fun config_alphaFollowsUserSwitch() {
        val kosmos = userAwareKosmos()
        kosmos.testScope.runTest {
            val underTest = PulseSettingsRepository(kosmos.userAwareSecureSettingsRepository)
            kosmos.fakeUserRepository.setSelectedUserInfo(USER_1)
            val config by collectLastValue(underTest.config)
            runCurrent()
            assertThat(config?.alpha).isEqualTo(100)

            kosmos.fakeUserRepository.setSelectedUserInfo(USER_2)
            runCurrent()

            assertThat(config?.alpha).isEqualTo(200)
        }
    }

    private fun userAwareKosmos() =
        testKosmos().apply {
            fakeUserRepository.setUserInfos(listOf(USER_1, USER_2))
            fakeSettings.putIntForUser(PulseSettingsRepository.ALPHA_KEY, 100, USER_1.id)
            fakeSettings.putIntForUser(PulseSettingsRepository.ALPHA_KEY, 200, USER_2.id)
        }

    private companion object {
        val USER_1 = UserInfo(/* id= */ 0, "user1", /* flags= */ 0)
        val USER_2 = UserInfo(/* id= */ 1, "user2", /* flags= */ 0)
    }
}
