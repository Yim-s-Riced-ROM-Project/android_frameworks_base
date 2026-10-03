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

class PulseSpectrumProcessorTest {
    private val underTest = PulseSpectrumProcessor()

    @Test
    fun process_returns32NormalizedBarsAndReusesArray() {
        val fft = fullScaleFft()

        val first = underTest.process(fft)
        val second = underTest.process(fft)

        assertThat(first).hasLength(32)
        assertThat(first).isSameInstanceAs(second)
        assertThat(first.all { it in 0f..1f }).isTrue()
        assertThat(first.all { it > 0f }).isTrue()
    }

    @Test
    fun process_skipsDcAndNyquistBins() {
        val fft = ByteArray(66)
        fft[0] = Byte.MAX_VALUE
        fft[1] = Byte.MAX_VALUE

        assertThat(underTest.process(fft).all { it == 0f }).isTrue()
    }

    @Test
    fun process_assignsEachFftBinToOnlyOneBar() {
        val fft = ByteArray(66)
        fft[2] = Byte.MIN_VALUE
        fft[3] = Byte.MIN_VALUE

        val bars = underTest.process(fft)

        assertThat(bars.count { it > 0f }).isEqualTo(1)
    }

    @Test
    fun process_appliesNoiseFloor() {
        val fft = ByteArray(66)
        for (index in 2 until fft.size step 2) {
            fft[index] = 1
            fft[index + 1] = 1
        }

        assertThat(underTest.process(fft).all { it == 0f }).isTrue()
    }

    @Test
    fun process_usesAttackAndDecaySmoothing() {
        val attacked = underTest.process(fullScaleFft()).copyOf()
        val decayed = underTest.process(ByteArray(66)).copyOf()

        assertThat(attacked[0]).isWithin(0.001f).of(0.55f)
        assertThat(decayed[0]).isWithin(0.001f).of(0.44f)
    }

    @Test
    fun reset_clearsSmoothedState() {
        underTest.process(fullScaleFft())

        underTest.reset()

        assertThat(underTest.process(ByteArray(66)).all { it == 0f }).isTrue()
    }

    @Test
    fun process_malformedOrShortInputClearsSafely() {
        underTest.process(fullScaleFft())

        val malformed = underTest.process(ByteArray(65))
        assertThat(malformed.all { it == 0f }).isTrue()

        underTest.process(fullScaleFft())
        val short = underTest.process(ByteArray(4))
        assertThat(short.all { it == 0f }).isTrue()
    }

    private fun fullScaleFft(): ByteArray {
        return ByteArray(66).also { fft ->
            for (index in 2 until fft.size step 2) {
                fft[index] = Byte.MIN_VALUE
                fft[index + 1] = Byte.MIN_VALUE
            }
        }
    }
}
