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

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseThemeRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = PulseThemeRepository(kosmos.configurationInteractor)

    @Test
    fun nightMode_isTrueWithDarkTheme() =
        kosmos.testScope.runTest {
            val nightMode by collectLastValue(underTest.nightMode)

            emitUiMode(Configuration.UI_MODE_NIGHT_YES)

            assertThat(nightMode).isTrue()
        }

    @Test
    fun nightMode_isFalseWithLightTheme() =
        kosmos.testScope.runTest {
            val nightMode by collectLastValue(underTest.nightMode)

            emitUiMode(Configuration.UI_MODE_NIGHT_NO)

            assertThat(nightMode).isFalse()
        }

    @Test
    fun nightMode_followsThemeToggle() =
        kosmos.testScope.runTest {
            val nightMode by collectLastValue(underTest.nightMode)
            emitUiMode(Configuration.UI_MODE_NIGHT_NO)
            assertThat(nightMode).isFalse()

            emitUiMode(Configuration.UI_MODE_NIGHT_YES)

            assertThat(nightMode).isTrue()
        }

    @Test
    fun nightMode_ignoresUnrelatedConfigurationChanges() =
        kosmos.testScope.runTest {
            val values by collectValues(underTest.nightMode)
            emitUiMode(Configuration.UI_MODE_NIGHT_YES)

            emitUiMode(Configuration.UI_MODE_NIGHT_YES, orientation = 2)

            assertThat(values).containsExactly(true)
        }

    private fun TestScope.emitUiMode(night: Int, orientation: Int = 1) {
        val configuration =
            Configuration().apply {
                uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
                this.orientation = orientation
            }
        kosmos.fakeConfigurationRepository.onConfigurationChange(configuration)
        runCurrent()
    }
}
