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

import android.view.Display.DEFAULT_DISPLAY
import com.android.app.displaylib.PerDisplayRepository
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PulseHostStateRepositoryStoreTest {
    private val repository = PulseHostStateRepository()
    private val displayComponent =
        mock<SystemUIDisplaySubcomponent>().also {
            whenever(it.pulseHostStateRepository).thenReturn(repository)
        }
    private val displayComponents = mock<PerDisplayRepository<SystemUIDisplaySubcomponent>>()
    private val underTest = PulseHostStateRepositoryStore(displayComponents)

    @Test
    fun secondaryDisplay_returnsNullWithoutCreatingDisplayComponent() {
        whenever(displayComponents[any()]).thenReturn(displayComponent)

        assertThat(underTest.forDisplay(SECONDARY_DISPLAY)).isNull()

        verify(displayComponents, never())[any()]
    }

    @Test
    fun defaultDisplay_isLookedUpOnceAndCached() {
        whenever(displayComponents[DEFAULT_DISPLAY]).thenReturn(displayComponent)

        assertThat(underTest.forDisplay(DEFAULT_DISPLAY)).isSameInstanceAs(repository)
        assertThat(underTest.forDisplay(DEFAULT_DISPLAY)).isSameInstanceAs(repository)

        verify(displayComponents, times(1))[DEFAULT_DISPLAY]
    }

    @Test
    fun defaultDisplay_missingComponentIsNotCached() {
        whenever(displayComponents[DEFAULT_DISPLAY]).thenReturn(null, displayComponent)

        assertThat(underTest.forDisplay(DEFAULT_DISPLAY)).isNull()
        assertThat(underTest.forDisplay(DEFAULT_DISPLAY)).isSameInstanceAs(repository)
    }

    private companion object {
        const val SECONDARY_DISPLAY = 2
    }
}
