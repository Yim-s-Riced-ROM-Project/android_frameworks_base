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
 * Time-driven rainbow colors for the animated [PulseColorMode]s.
 *
 * Fully saturated, full-value hues come from a table built once, so the per-frame calls do not
 * allocate. Kept free of Android types (unlike `Color.HSVToColor`) so it is testable on a plain
 * JVM.
 */
internal object PulseRainbow {
    /** How long Rainbow cycle takes to pass through every hue once. */
    const val CYCLE_PERIOD_MS = 6_000L

    /** How long the Rainbow gradient takes to drift by a full turn of the hue wheel. */
    const val GRADIENT_DRIFT_PERIOD_MS = 12_000L

    private const val HUE_STEPS = 360
    private const val SECTOR_DEGREES = 60
    private const val MAX_CHANNEL = 255

    private val hueTable = IntArray(HUE_STEPS) { computeHueRgb(it) }

    /** The RGB (no alpha) for [hueDegrees], wrapped into 0-359. */
    fun hueRgb(hueDegrees: Int): Int = hueTable[Math.floorMod(hueDegrees, HUE_STEPS)]

    /** The shared Rainbow cycle color at [timeMs], with [alpha] in the top byte. */
    fun cycleArgb(timeMs: Long, alpha: Int): Int =
        withAlpha(hueRgb(phaseDegrees(timeMs, CYCLE_PERIOD_MS)), alpha)

    /**
     * The Rainbow gradient color of bar [index] of [count] at [timeMs], with [alpha] in the top
     * byte. Bars spread evenly over one turn of the hue wheel, which drifts with time.
     */
    fun gradientArgb(timeMs: Long, index: Int, count: Int, alpha: Int): Int {
        val barOffset = if (count > 0) index * HUE_STEPS / count else 0
        val hue = barOffset + phaseDegrees(timeMs, GRADIENT_DRIFT_PERIOD_MS)
        return withAlpha(hueRgb(hue), alpha)
    }

    private fun phaseDegrees(timeMs: Long, periodMs: Long): Int =
        (Math.floorMod(timeMs, periodMs) * HUE_STEPS / periodMs).toInt()

    private fun withAlpha(rgb: Int, alpha: Int): Int = (alpha shl 24) or rgb

    /** HSV to RGB with saturation and value both 1. */
    private fun computeHueRgb(hue: Int): Int {
        val rising = (hue % SECTOR_DEGREES) * MAX_CHANNEL / SECTOR_DEGREES
        val falling = MAX_CHANNEL - rising
        return when (hue / SECTOR_DEGREES) {
            0 -> rgb(MAX_CHANNEL, rising, 0)
            1 -> rgb(falling, MAX_CHANNEL, 0)
            2 -> rgb(0, MAX_CHANNEL, rising)
            3 -> rgb(0, falling, MAX_CHANNEL)
            4 -> rgb(rising, 0, MAX_CHANNEL)
            else -> rgb(MAX_CHANNEL, 0, falling)
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int = (red shl 16) or (green shl 8) or blue
}
