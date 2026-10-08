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
import kotlin.math.ln
import org.junit.Test

class PulseHeightCurveTest {
    private val underTest = PulseHeightCurve()

    @Test
    fun defaultStrength_isTwenty() {
        assertThat(underTest.strength).isEqualTo(20)
    }

    @Test
    fun apply_withZeroStrength_isLinear() {
        underTest.strength = 0

        for (level in LEVELS) {
            assertThat(underTest.heightFor(level)).isEqualTo(level)
        }
    }

    @Test
    fun apply_keepsEndpointsForEveryStrength() {
        for (strength in listOf(0, 1, 20, 100)) {
            underTest.strength = strength

            assertThat(underTest.heightFor(0f)).isEqualTo(0f)
            assertThat(underTest.heightFor(1f)).isEqualTo(1f)
        }
    }

    @Test
    fun apply_followsLogarithmicFormula() {
        underTest.strength = 20

        val expected = (ln(1.0 + 20 * 0.05) / ln(21.0)).toFloat()
        assertThat(underTest.heightFor(0.05f)).isWithin(1e-6f).of(expected)
        // A 5% band on the default 48 dp bar grows from 2.4 dp to about 11 dp.
        assertThat(underTest.heightFor(0.05f) * 48f).isWithin(0.5f).of(10.9f)
    }

    @Test
    fun apply_withPositiveStrength_isMonotonicAndAtLeastLinear() {
        underTest.strength = 100
        var previous = 0f

        for (level in LEVELS) {
            val height = underTest.heightFor(level)
            assertThat(height).isAtLeast(previous)
            assertThat(height).isAtLeast(level)
            previous = height
        }
    }

    @Test
    fun apply_strongerBoostRaisesQuietLevelsMore() {
        underTest.strength = 20
        val moderate = underTest.heightFor(0.05f)
        underTest.strength = 100

        assertThat(underTest.heightFor(0.05f)).isGreaterThan(moderate)
    }

    @Test
    fun apply_clampsLevelsOutsideUnitRange() {
        underTest.strength = 20

        assertThat(underTest.heightFor(-0.5f)).isEqualTo(0f)
        assertThat(underTest.heightFor(1.5f)).isEqualTo(1f)
        assertThat(underTest.heightFor(Float.NaN)).isEqualTo(0f)
    }

    @Test
    fun strength_isClampedToSupportedRange() {
        underTest.strength = -5
        assertThat(underTest.strength).isEqualTo(0)

        underTest.strength = 500
        assertThat(underTest.strength).isEqualTo(100)
    }

    private companion object {
        val LEVELS = listOf(0f, 0.001f, 0.02f, 0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 0.999f, 1f)
    }
}
