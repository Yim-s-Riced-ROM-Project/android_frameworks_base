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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.android.systemui.statusbar.phone.ScreenOffAnimationSettingsRepository.Companion.SETTING_KEY
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenOffAnimationSettingsRepositoryTest : SysuiTestCase() {
    private val secureSettingsRepository = FakeSecureSettingsRepository()

    @Test
    fun missingSetting_defaultsToStock() = runTest {
        val underTest = createRepository()
        runCurrent()

        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(0, ScreenOffAnimationSelection.STOCK))
    }

    @Test
    fun zero_selectsStock() = runTest {
        secureSettingsRepository.setInt(SETTING_KEY, 0)
        val underTest = createRepository()
        runCurrent()

        assertThat(underTest.setting.value.selection).isEqualTo(ScreenOffAnimationSelection.STOCK)
    }

    @Test
    fun one_selectsCrt() = runTest {
        secureSettingsRepository.setInt(SETTING_KEY, 1)
        val underTest = createRepository()
        runCurrent()

        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(1, ScreenOffAnimationSelection.CRT))
    }

    @Test
    fun unknownValue_selectsStock() = runTest {
        secureSettingsRepository.setInt(SETTING_KEY, 7)
        val underTest = createRepository()
        runCurrent()

        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(7, ScreenOffAnimationSelection.STOCK))
    }

    @Test
    fun readFailure_selectsStock() = runTest {
        val failing = FailingSecureSettingsRepository(failures = Int.MAX_VALUE)
        val underTest = createRepository(failing)
        runCurrent()

        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(0, ScreenOffAnimationSelection.STOCK))

        // A permanent failure retries a bounded number of times, then settles on Stock.
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(0, ScreenOffAnimationSelection.STOCK))
        assertThat(failing.reads).isEqualTo(4)
    }

    @Test
    fun transientReadFailure_recoversOnRetry() = runTest {
        secureSettingsRepository.setInt(SETTING_KEY, 1)
        val failing =
            FailingSecureSettingsRepository(failures = 1, delegate = secureSettingsRepository)
        val underTest = createRepository(failing)
        runCurrent()
        assertThat(underTest.setting.value.selection).isEqualTo(ScreenOffAnimationSelection.STOCK)

        advanceTimeBy(1_000)
        runCurrent()

        assertThat(underTest.setting.value)
            .isEqualTo(ScreenOffAnimationSetting(1, ScreenOffAnimationSelection.CRT))
        assertThat(failing.reads).isEqualTo(2)
    }

    @Test
    fun settingChange_updatesNextSample() = runTest {
        val underTest = createRepository()
        runCurrent()
        assertThat(underTest.setting.value.selection).isEqualTo(ScreenOffAnimationSelection.STOCK)

        secureSettingsRepository.setInt(SETTING_KEY, 1)
        runCurrent()
        assertThat(underTest.setting.value.selection).isEqualTo(ScreenOffAnimationSelection.CRT)

        secureSettingsRepository.setInt(SETTING_KEY, 0)
        runCurrent()
        assertThat(underTest.setting.value.selection).isEqualTo(ScreenOffAnimationSelection.STOCK)
    }

    private fun TestScope.createRepository(
        repository: SecureSettingsRepository = secureSettingsRepository
    ) =
        ScreenOffAnimationSettingsRepository(
            repository,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )

    /** A secure-settings source whose first [failures] reads fail, then delegate. */
    private class FailingSecureSettingsRepository(
        private val failures: Int,
        private val delegate: SecureSettingsRepository = FakeSecureSettingsRepository(),
    ) : SecureSettingsRepository by delegate {
        var reads = 0
            private set

        override fun intSetting(name: String, defaultValue: Int): Flow<Int> = flow {
            reads++
            if (reads <= failures) throw IllegalStateException("unreadable")
            emitAll(delegate.intSetting(name, defaultValue))
        }
    }
}
