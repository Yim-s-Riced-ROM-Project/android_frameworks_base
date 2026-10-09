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
 * How Pulse picks its bar color. [value] is the stored `lineage_pulse_color_mode` setting.
 *
 * Kept free of Android types so the color decision is testable on a plain JVM.
 */
enum class PulseColorMode(val value: Int, val animated: Boolean = false) {
    /** The user's stored RGB. The default, and the only behavior before the setting existed. */
    SOLID(0),

    /** White bars with the system dark theme on, black bars with it off. */
    MATCH_THEME(1),

    /** Each bar its own hue across the spectrum; the hues drift over time. See [PulseRainbow]. */
    RAINBOW_GRADIENT(2, animated = true),

    /** All bars share one hue that cycles through the spectrum. See [PulseRainbow]. */
    RAINBOW_CYCLE(3, animated = true);

    /**
     * The bar color with [alpha] in the top byte, ready for [android.graphics.Paint.setColor].
     * Animated modes return white: [PulseView] replaces the RGB per frame and keeps the alpha.
     */
    fun resolveArgb(rgb: Int, alpha: Int, nightMode: Boolean): Int {
        val baseRgb =
            when (this) {
                SOLID -> rgb
                MATCH_THEME -> if (nightMode) WHITE_RGB else BLACK_RGB
                RAINBOW_GRADIENT,
                RAINBOW_CYCLE -> WHITE_RGB
            }
        return (alpha shl 24) or (baseRgb and RGB_MASK)
    }

    /**
     * The color of bar [index] of [count] at [timeMs], given the [resolvedArgb] from
     * [resolveArgb]. Static modes return [resolvedArgb] unchanged; animated modes keep only its
     * alpha. Called per bar in [PulseView.onDraw], so it must not allocate.
     */
    fun barArgb(resolvedArgb: Int, timeMs: Long, index: Int, count: Int): Int {
        val alpha = resolvedArgb ushr 24
        return when (this) {
            SOLID,
            MATCH_THEME -> resolvedArgb
            RAINBOW_GRADIENT -> PulseRainbow.gradientArgb(timeMs, index, count, alpha)
            RAINBOW_CYCLE -> PulseRainbow.cycleArgb(timeMs, alpha)
        }
    }

    companion object {
        /** Unknown values, including modes from a newer Settings, read as [SOLID]. */
        fun fromSetting(raw: Int): PulseColorMode = entries.firstOrNull { it.value == raw } ?: SOLID

        private const val WHITE_RGB = 0xFFFFFF
        private const val BLACK_RGB = 0x000000
        private const val RGB_MASK = 0xFFFFFF
    }
}
