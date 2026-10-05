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

import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Android Visualizer FFT byte layout shared by Pulse consumers: byte 0 is the DC real part, byte 1
 * is the Nyquist real part, and bins 1 until size / 2 are (real, imaginary) signed byte pairs.
 */
internal object PulseFft {
    /** Normalized magnitude at or below this value is treated as noise. */
    const val NOISE_FLOOR = 0.02f
    val MAX_MAGNITUDE = sqrt(2f * Byte.MIN_VALUE * Byte.MIN_VALUE)

    /** Magnitude of [bin] (1 until size / 2) scaled to 0..1 against the largest possible pair. */
    fun normalizedMagnitude(fft: ByteArray, bin: Int): Float {
        val real = fft[bin * 2].toFloat()
        val imaginary = fft[bin * 2 + 1].toFloat()
        return sqrt(real * real + imaginary * imaginary) / MAX_MAGNITUDE
    }
}

/** Converts Android Visualizer FFT output into smoothed logarithmic spectrum bars. */
@PerDisplaySingleton
class PulseSpectrumProcessor @Inject constructor() {
    private val bars = FloatArray(BAR_COUNT)

    fun process(fft: ByteArray): FloatArray {
        if (fft.size < MIN_FFT_SIZE || fft.size % 2 != 0) {
            return clearBars()
        }

        val binCount = fft.size / 2 - 1
        val logarithmicRange = ln((binCount + 1).toDouble())
        for (barIndex in bars.indices) {
            val startBin = logarithmicBoundary(barIndex, binCount, logarithmicRange)
            val endBin =
                max(startBin + 1, logarithmicBoundary(barIndex + 1, binCount, logarithmicRange))
            val target = averageMagnitude(fft, startBin, endBin.coerceAtMost(binCount + 1))
            val coefficient = if (target > bars[barIndex]) ATTACK else DECAY
            bars[barIndex] += (target - bars[barIndex]) * coefficient
        }
        return bars
    }

    fun reset() {
        bars.fill(0f)
    }

    private fun logarithmicBoundary(barIndex: Int, binCount: Int, logarithmicRange: Double): Int {
        val boundary = (exp(logarithmicRange * barIndex / BAR_COUNT) - 1).toInt() + 1
        val minimum = barIndex + 1
        val maximum = binCount - BAR_COUNT + barIndex + 1
        return boundary.coerceIn(minimum, maximum)
    }

    private fun averageMagnitude(fft: ByteArray, startBin: Int, endBin: Int): Float {
        var sum = 0f
        for (bin in startBin until endBin) {
            sum += PulseFft.normalizedMagnitude(fft, bin)
        }
        val average = sum / (endBin - startBin)
        return ((average - PulseFft.NOISE_FLOOR) / (1f - PulseFft.NOISE_FLOOR)).coerceIn(0f, 1f)
    }

    private fun clearBars(): FloatArray {
        reset()
        return bars
    }

    private companion object {
        const val BAR_COUNT = 32
        const val MIN_FFT_SIZE = 2 + BAR_COUNT * 2
        const val ATTACK = 0.55f
        const val DECAY = 0.20f
    }
}
