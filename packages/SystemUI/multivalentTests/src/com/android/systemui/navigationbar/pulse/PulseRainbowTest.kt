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

class PulseRainbowTest {
    @Test
    fun hueRgb_mapsPrimaryAndSecondaryHues() {
        assertThat(PulseRainbow.hueRgb(0)).isEqualTo(0xFF0000)
        assertThat(PulseRainbow.hueRgb(60)).isEqualTo(0xFFFF00)
        assertThat(PulseRainbow.hueRgb(120)).isEqualTo(0x00FF00)
        assertThat(PulseRainbow.hueRgb(240)).isEqualTo(0x0000FF)
        assertThat(PulseRainbow.hueRgb(360)).isEqualTo(0xFF0000)
    }

    @Test
    fun cycleArgb_movesThroughHuesOverOnePeriod() {
        val period = PulseRainbow.CYCLE_PERIOD_MS

        assertThat(PulseRainbow.cycleArgb(0L, 0xFF)).isEqualTo(0xFFFF0000.toInt())
        assertThat(PulseRainbow.cycleArgb(period / 3, 0xFF)).isEqualTo(0xFF00FF00.toInt())
        assertThat(PulseRainbow.cycleArgb(period * 2 / 3, 0xFF)).isEqualTo(0xFF0000FF.toInt())
        assertThat(PulseRainbow.cycleArgb(period, 0xFF)).isEqualTo(0xFFFF0000.toInt())
    }

    @Test
    fun cycleArgb_keepsAlphaInTopByte() {
        assertThat(PulseRainbow.cycleArgb(0L, 0x40)).isEqualTo(0x40FF0000)
    }

    @Test
    fun cycleArgb_toleratesNegativeTime() {
        val period = PulseRainbow.CYCLE_PERIOD_MS
        assertThat(PulseRainbow.cycleArgb(-period / 3, 0xFF))
            .isEqualTo(PulseRainbow.cycleArgb(period * 2 / 3, 0xFF))
    }

    @Test
    fun gradientArgb_givesEachBarItsOwnHue() {
        for (count in listOf(16, 32, 64)) {
            val colors = (0 until count).map { PulseRainbow.gradientArgb(0L, it, count, 0xFF) }
            assertThat(colors.toSet()).hasSize(count)
        }
    }

    @Test
    fun gradientArgb_spreadsHuesAcrossTheSpectrum() {
        assertThat(PulseRainbow.gradientArgb(0L, 0, 3, 0xFF)).isEqualTo(0xFFFF0000.toInt())
        assertThat(PulseRainbow.gradientArgb(0L, 1, 3, 0xFF)).isEqualTo(0xFF00FF00.toInt())
        assertThat(PulseRainbow.gradientArgb(0L, 2, 3, 0xFF)).isEqualTo(0xFF0000FF.toInt())
    }

    @Test
    fun gradientArgb_driftsOverTime() {
        val drift = PulseRainbow.GRADIENT_DRIFT_PERIOD_MS
        assertThat(PulseRainbow.gradientArgb(drift / 3, 0, 32, 0xFF)).isEqualTo(0xFF00FF00.toInt())
        assertThat(PulseRainbow.gradientArgb(drift, 5, 32, 0x80))
            .isEqualTo(PulseRainbow.gradientArgb(0L, 5, 32, 0x80))
    }

    @Test
    fun gradientArgb_withZeroCountKeepsAlpha() {
        assertThat(PulseRainbow.gradientArgb(0L, 0, 0, 0x40)).isEqualTo(0x40FF0000)
    }

    @Test
    fun periods_matchTheApprovedSpeeds() {
        assertThat(PulseRainbow.CYCLE_PERIOD_MS).isEqualTo(6_000L)
        assertThat(PulseRainbow.GRADIENT_DRIFT_PERIOD_MS).isEqualTo(12_000L)
    }
}
