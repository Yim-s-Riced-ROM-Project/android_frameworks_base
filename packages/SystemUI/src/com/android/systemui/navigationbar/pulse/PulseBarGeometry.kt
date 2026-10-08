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
 * Places Pulse bars across the view width. Each bar owns an equal slot; [layout] removes the
 * requested gap, as a percent of the slot, half from each side.
 *
 * A bar is never narrower than [MIN_BAR_WIDTH_PX]: if the requested gap would leave less, the gap
 * shrinks instead. Only a slot narrower than that minimum draws narrower bars, with no gap.
 *
 * Kept free of Android types so it tests on a plain JVM.
 */
internal object PulseBarGeometry {
    const val MIN_BAR_WIDTH_PX = 1f

    /**
     * Fills [left] and [right] with one edge pair per bar for a view [widthPx] wide and returns the
     * effective gap in pixels. Without a width or bars it changes nothing and returns 0.
     */
    fun layout(widthPx: Int, gapPercent: Int, left: FloatArray, right: FloatArray): Float {
        val count = left.size
        if (widthPx <= 0 || count == 0) return 0f

        val slot = widthPx.toFloat() / count
        val requestedGap = slot * gapPercent / 100f
        val gap =
            if (slot - requestedGap >= MIN_BAR_WIDTH_PX) requestedGap
            else (slot - MIN_BAR_WIDTH_PX).coerceAtLeast(0f)
        val inset = gap / 2f
        for (index in 0 until count) {
            left[index] = index * slot + inset
            right[index] = (index + 1) * slot - inset
        }
        return gap
    }
}
