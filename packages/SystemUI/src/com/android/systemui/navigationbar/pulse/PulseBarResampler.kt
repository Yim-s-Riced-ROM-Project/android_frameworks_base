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

/**
 * Maps the fixed analysed bands onto the configured number of drawn bars.
 *
 * With more bars than bands, each bar interpolates linearly between its two nearest bands, so the
 * first and last bar equal the first and last band. With fewer bars, each bar takes the maximum of
 * every band it overlaps, including partly overlapped ones, so no band peak is dropped. Equal
 * counts copy through.
 *
 * Kept free of Android types so it tests on a plain JVM. [resample] allocates nothing, which keeps
 * it safe inside `onDraw`.
 */
internal object PulseBarResampler {
    /** Writes one level per element of [bars] from [bands]. An empty [bands] clears [bars]. */
    fun resample(bands: FloatArray, bars: FloatArray) {
        when {
            bands.isEmpty() -> bars.fill(0f)
            bars.size == bands.size -> bands.copyInto(bars)
            bars.size > bands.size -> interpolate(bands, bars)
            else -> takeMaximum(bands, bars)
        }
    }

    private fun interpolate(bands: FloatArray, bars: FloatArray) {
        if (bands.size == 1) {
            bars.fill(bands[0])
            return
        }
        val lastBand = bands.size - 1
        val lastBar = bars.size - 1
        for (index in bars.indices) {
            // Integer numerator keeps both end bars exactly on the end bands.
            val position = (index * lastBand).toFloat() / lastBar
            val lower = position.toInt()
            if (lower >= lastBand) {
                bars[index] = bands[lastBand]
                continue
            }
            val fraction = position - lower
            bars[index] = bands[lower] + (bands[lower + 1] - bands[lower]) * fraction
        }
    }

    private fun takeMaximum(bands: FloatArray, bars: FloatArray) {
        for (index in bars.indices) {
            val start = index * bands.size / bars.size
            val end = ((index + 1) * bands.size + bars.size - 1) / bars.size
            var maximum = 0f
            for (band in start until end) {
                if (bands[band] > maximum) maximum = bands[band]
            }
            bars[index] = maximum
        }
    }
}
