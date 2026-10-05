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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PulseHostStateRepositoryTest {
    private val underTest = PulseHostStateRepository()

    @Test
    fun initialState_isInactive() {
        assertThat(underTest.state.value).isEqualTo(PulseHostState())
        assertThat(underTest.state.value.activeHost).isEqualTo(PulseHost.NONE)
    }

    @Test
    fun activate_setsActiveHost() {
        underTest.activate(PulseHost.NAVIGATION_BAR)

        assertThat(underTest.state.value)
            .isEqualTo(PulseHostState(activeHost = PulseHost.NAVIGATION_BAR))
    }

    @Test
    fun activate_resetsHostState() {
        underTest.activate(PulseHost.NAVIGATION_BAR)
        underTest.updateNavigationVisible(PulseHost.NAVIGATION_BAR, true)
        underTest.updateScreenPinning(PulseHost.NAVIGATION_BAR, true)

        underTest.activate(PulseHost.TASKBAR)

        assertThat(underTest.state.value).isEqualTo(PulseHostState(activeHost = PulseHost.TASKBAR))
    }

    @Test(expected = IllegalArgumentException::class)
    fun activate_none_isRejected() {
        underTest.activate(PulseHost.NONE)
    }

    @Test
    fun activeHostUpdates_areApplied() {
        underTest.activate(PulseHost.TASKBAR)

        underTest.updateNavigationVisible(PulseHost.TASKBAR, true)
        underTest.updateScreenPinning(PulseHost.TASKBAR, true)

        assertThat(underTest.state.value)
            .isEqualTo(
                PulseHostState(
                    activeHost = PulseHost.TASKBAR,
                    navigationVisible = true,
                    screenPinningActive = true,
                )
            )
    }

    @Test
    fun inactiveHostUpdate_isIgnored() {
        underTest.activate(PulseHost.TASKBAR)

        underTest.updateNavigationVisible(PulseHost.NAVIGATION_BAR, true)
        underTest.updateScreenPinning(PulseHost.NAVIGATION_BAR, true)

        assertThat(underTest.state.value).isEqualTo(PulseHostState(activeHost = PulseHost.TASKBAR))
    }

    @Test
    fun updatesBeforeActivation_areIgnored() {
        underTest.updateNavigationVisible(PulseHost.NONE, true)
        underTest.updateScreenPinning(PulseHost.NONE, true)
        underTest.updateNavigationVisible(PulseHost.NAVIGATION_BAR, true)
        underTest.updateScreenPinning(PulseHost.NAVIGATION_BAR, true)

        assertThat(underTest.state.value).isEqualTo(PulseHostState())
    }

    @Test
    fun deactivate_clearsVisibilityAndPinning() {
        underTest.activate(PulseHost.TASKBAR)
        underTest.updateNavigationVisible(PulseHost.TASKBAR, true)
        underTest.updateScreenPinning(PulseHost.TASKBAR, true)

        underTest.deactivate()

        assertThat(underTest.state.value).isEqualTo(PulseHostState())
    }

    @Test
    fun updateAfterDeactivate_isIgnored() {
        underTest.activate(PulseHost.TASKBAR)
        underTest.deactivate()

        underTest.updateNavigationVisible(PulseHost.TASKBAR, true)
        underTest.updateScreenPinning(PulseHost.TASKBAR, true)

        assertThat(underTest.state.value).isEqualTo(PulseHostState())
    }
}
