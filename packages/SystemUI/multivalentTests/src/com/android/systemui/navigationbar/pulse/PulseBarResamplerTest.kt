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

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class PulseBarResamplerTest {
    @Test
    fun resample_equalCounts_copiesBands() {
        val bands = FloatArray(32) { it / 31f }
        val bars = FloatArray(32)

        PulseBarResampler.resample(bands, bars)

        assertThat(bars.toList()).isEqualTo(bands.toList())
    }

    @Test
    fun resample_moreBars_interpolatesLinearlyAndKeepsEndpoints() {
        val bands = floatArrayOf(0f, 1f, 0f)
        val bars = FloatArray(5)

        PulseBarResampler.resample(bands, bars)

        assertThat(bars.toList()).containsExactly(0f, 0.5f, 1f, 0.5f, 0f).inOrder()
    }

    @Test
    fun resample_everyHigherCount_hitsEndBandsExactlyWithoutOvershoot() {
        val bands = FloatArray(32) { if (it % 2 == 0) 0.2f else 0.9f }
        for (count in 33..64) {
            val bars = FloatArray(count)

            PulseBarResampler.resample(bands, bars)

            assertWithMessage("first of $count").that(bars.first()).isEqualTo(bands.first())
            assertWithMessage("last of $count").that(bars.last()).isEqualTo(bands.last())
            for (level in bars) {
                assertWithMessage("$count bars").that(level).isIn(Range.closed(0.2f, 0.9f))
            }
        }
    }

    @Test
    fun resample_halfBars_takesPairMaximum() {
        val bands = FloatArray(32) { if (it % 2 == 0) 0.1f else 0.7f }
        val bars = FloatArray(16)

        PulseBarResampler.resample(bands, bars)

        assertThat(bars.toSet()).containsExactly(0.7f)
    }

    @Test
    fun resample_fewerBars_keepsEverySingleBandPeak() {
        val bars = FloatArray(20)
        for (peak in 0 until 32) {
            val bands = FloatArray(32)
            bands[peak] = 1f

            PulseBarResampler.resample(bands, bars)

            assertThat(bars.max()).isEqualTo(1f)
        }
    }

    @Test
    fun resample_singleBar_takesOverallMaximum() {
        val bands = FloatArray(32).also { it[17] = 0.6f }
        val bars = FloatArray(1)

        PulseBarResampler.resample(bands, bars)

        assertThat(bars[0]).isEqualTo(0.6f)
    }

    @Test
    fun resample_singleBand_fillsEveryBar() {
        val bars = FloatArray(4)

        PulseBarResampler.resample(floatArrayOf(0.4f), bars)

        assertThat(bars.toSet()).containsExactly(0.4f)
    }

    @Test
    fun resample_noBands_clearsBars() {
        val bars = floatArrayOf(1f, 1f)

        PulseBarResampler.resample(FloatArray(0), bars)

        assertThat(bars.toList()).containsExactly(0f, 0f)
    }
}
