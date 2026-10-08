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

class PulseColorModeTest {
    @Test
    fun fromSetting_onlyOneIsMatchTheme() {
        assertThat(PulseColorMode.fromSetting(1)).isEqualTo(PulseColorMode.MATCH_THEME)
        for (raw in listOf(0, 2, -1, 99, Int.MIN_VALUE)) {
            assertThat(PulseColorMode.fromSetting(raw)).isEqualTo(PulseColorMode.SOLID)
        }
    }

    @Test
    fun solid_usesStoredRgbAndAlphaInEitherTheme() {
        for (nightMode in listOf(true, false)) {
            assertThat(PulseColorMode.SOLID.resolveArgb(0x123456, 128, nightMode))
                .isEqualTo(0x80123456.toInt())
        }
    }

    @Test
    fun matchTheme_drawsWhiteWithDarkTheme() {
        assertThat(PulseColorMode.MATCH_THEME.resolveArgb(0x123456, 217, nightMode = true))
            .isEqualTo(0xD9FFFFFF.toInt())
    }

    @Test
    fun matchTheme_drawsBlackWithLightTheme() {
        assertThat(PulseColorMode.MATCH_THEME.resolveArgb(0x123456, 64, nightMode = false))
            .isEqualTo(0x40000000)
    }

    @Test
    fun resolveArgb_masksStoredRgbToLower24Bits() {
        assertThat(PulseColorMode.SOLID.resolveArgb(0xAB123456.toInt(), 255, nightMode = false))
            .isEqualTo(0xFF123456.toInt())
    }
}
