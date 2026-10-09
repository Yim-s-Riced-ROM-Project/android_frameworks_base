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

package com.android.server.display;

/**
 * The screen-off effect chosen by the per-user {@code lineage_screen_off_animation} setting.
 * Stock runs the platform fade; every other effect is drawn by {@link ColorFade} and its base
 * duration is scaled by {@link ScreenOffAnimationSpeed}. Pure Java so it is testable off device.
 */
enum ScreenOffEffect {
    STOCK(0, 0L),
    CRT(1, 500L),
    TEAR(2, 600L),
    CORRUPT(3, 600L),
    SIGNAL_LOSS(4, 600L);

    /** The stored {@code Settings.Secure} value. */
    final int settingValue;
    /** Duration at 1x speed. Zero for Stock, which keeps the platform timing. */
    final long baseDurationMillis;

    ScreenOffEffect(int settingValue, long baseDurationMillis) {
        this.settingValue = settingValue;
        this.baseDurationMillis = baseDurationMillis;
    }

    /** Maps a stored value; any unknown value is Stock. */
    static ScreenOffEffect from(int settingValue) {
        for (ScreenOffEffect effect : values()) {
            if (effect.settingValue == settingValue) {
                return effect;
            }
        }
        return STOCK;
    }

    /** Whether the effect distorts a screenshot (as opposed to CRT's mask or Stock). */
    boolean isGlitch() {
        return this == TEAR || this == CORRUPT || this == SIGNAL_LOSS;
    }
}
