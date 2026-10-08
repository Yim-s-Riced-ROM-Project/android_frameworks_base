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

class PulseBarGeometryTest {
    @Test
    fun layout_defaultMatchesLegacyFifteenPercentInsets() {
        val left = FloatArray(32)
        val right = FloatArray(32)

        PulseBarGeometry.layout(1440, 30, left, right)

        val slot = 1440f / 32
        for (index in 0 until 32) {
            assertThat(left[index]).isWithin(1e-3f).of(index * slot + slot * 0.15f)
            assertThat(right[index]).isWithin(1e-3f).of((index + 1) * slot - slot * 0.15f)
        }
    }

    @Test
    fun layout_returnsEffectiveGapInPixels() {
        val gap = PulseBarGeometry.layout(1280, 50, FloatArray(16), FloatArray(16))

        assertThat(gap).isWithin(1e-4f).of(40f)
    }

    @Test
    fun layout_zeroGap_makesBarsTouch() {
        val left = FloatArray(16)
        val right = FloatArray(16)

        PulseBarGeometry.layout(1000, 0, left, right)

        for (index in 1 until 16) assertThat(left[index]).isWithin(1e-3f).of(right[index - 1])
        assertThat(left.first()).isEqualTo(0f)
        assertThat(right.last()).isWithin(1e-3f).of(1000f)
    }

    @Test
    fun layout_gapThatLeavesUnderOnePixel_shrinksGapNotBar() {
        val left = FloatArray(64)
        val right = FloatArray(64)

        val gap = PulseBarGeometry.layout(100, 80, left, right)

        val slot = 100f / 64
        assertThat(gap).isWithin(1e-4f).of(slot - 1f)
        for (index in 0 until 64) assertThat(right[index] - left[index]).isWithin(1e-4f).of(1f)
    }

    @Test
    fun layout_slotNarrowerThanOnePixel_dropsGapEntirely() {
        val left = FloatArray(64)
        val right = FloatArray(64)

        val gap = PulseBarGeometry.layout(32, 80, left, right)

        assertThat(gap).isEqualTo(0f)
        assertThat(right[0] - left[0]).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun layout_withoutWidthOrBars_leavesArraysAndReturnsZero() {
        val left = floatArrayOf(7f)
        val right = floatArrayOf(9f)

        assertThat(PulseBarGeometry.layout(0, 30, left, right)).isEqualTo(0f)
        assertThat(PulseBarGeometry.layout(100, 30, FloatArray(0), FloatArray(0))).isEqualTo(0f)
        assertThat(left[0]).isEqualTo(7f)
        assertThat(right[0]).isEqualTo(9f)
    }
}
