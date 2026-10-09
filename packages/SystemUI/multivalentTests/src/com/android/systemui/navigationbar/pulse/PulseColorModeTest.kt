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
    fun fromSetting_mapsEachStoredValue() {
        assertThat(PulseColorMode.fromSetting(1)).isEqualTo(PulseColorMode.MATCH_THEME)
        assertThat(PulseColorMode.fromSetting(2)).isEqualTo(PulseColorMode.RAINBOW_GRADIENT)
        assertThat(PulseColorMode.fromSetting(3)).isEqualTo(PulseColorMode.RAINBOW_CYCLE)
    }

    @Test
    fun fromSetting_unknownValuesAreSolid() {
        for (raw in listOf(0, 4, -1, 99, Int.MIN_VALUE)) {
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

    @Test
    fun animated_onlyForRainbowModes() {
        assertThat(PulseColorMode.SOLID.animated).isFalse()
        assertThat(PulseColorMode.MATCH_THEME.animated).isFalse()
        assertThat(PulseColorMode.RAINBOW_GRADIENT.animated).isTrue()
        assertThat(PulseColorMode.RAINBOW_CYCLE.animated).isTrue()
    }

    @Test
    fun rainbowModes_resolveToWhiteBaseWithStoredAlpha() {
        for (mode in listOf(PulseColorMode.RAINBOW_GRADIENT, PulseColorMode.RAINBOW_CYCLE)) {
            assertThat(mode.resolveArgb(0x123456, 0x80, nightMode = false))
                .isEqualTo(0x80FFFFFF.toInt())
        }
    }

    @Test
    fun barArgb_staticModesReturnResolvedColorAtAnyTime() {
        for (mode in listOf(PulseColorMode.SOLID, PulseColorMode.MATCH_THEME)) {
            for (timeMs in listOf(0L, 1_234L, 99_999L)) {
                assertThat(mode.barArgb(0x80123456.toInt(), timeMs, index = 7, count = 32))
                    .isEqualTo(0x80123456.toInt())
            }
        }
    }

    @Test
    fun barArgb_rainbowGradientUsesPerBarHueAndResolvedAlpha() {
        val mode = PulseColorMode.RAINBOW_GRADIENT

        assertThat(mode.barArgb(0x80FFFFFF.toInt(), 1_000L, index = 3, count = 16))
            .isEqualTo(PulseRainbow.gradientArgb(1_000L, 3, 16, 0x80))
        assertThat(mode.barArgb(0x80FFFFFF.toInt(), 1_000L, index = 0, count = 16))
            .isNotEqualTo(mode.barArgb(0x80FFFFFF.toInt(), 1_000L, index = 8, count = 16))
    }

    @Test
    fun barArgb_rainbowCycleSharesOneColorAcrossBars() {
        val mode = PulseColorMode.RAINBOW_CYCLE

        for (index in 0 until 32) {
            assertThat(mode.barArgb(0x40FFFFFF, 2_000L, index, count = 32))
                .isEqualTo(PulseRainbow.cycleArgb(2_000L, 0x40))
        }
    }
}
