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

import kotlin.math.ln1p

/**
 * Maps a smoothed 0..1 bar level to a 0..1 bar height through `ln(1 + k·level) / ln(1 + k)`.
 *
 * Quiet levels grow a lot and loud levels a little; 0 and 1 stay fixed. A [strength] `k` of 0 is
 * linear. Changing [strength] precomputes `1 / ln(1 + k)`, so [apply] allocates nothing and costs
 * one `ln1p` per bar, which keeps it safe inside `onDraw`.
 */
class PulseHeightCurve {
    private var inverseRange = inverseRange(DEFAULT_STRENGTH)

    /** Curve strength `k`, clamped to [MIN_STRENGTH]..[MAX_STRENGTH]. */
    var strength: Int = DEFAULT_STRENGTH
        set(value) {
            val clamped = value.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
            if (clamped == field) return
            field = clamped
            inverseRange = inverseRange(clamped)
        }

    /** Returns the drawn height fraction for [level]; NaN and levels outside 0..1 are clamped. */
    fun apply(level: Float): Float {
        if (!(level > 0f)) return 0f
        if (level >= 1f) return 1f
        if (strength == 0) return level
        return (ln1p(strength * level) * inverseRange).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 100
        const val DEFAULT_STRENGTH = 20

        private fun inverseRange(strength: Int): Float =
            if (strength == 0) 0f else 1f / ln1p(strength.toFloat())
    }
}
