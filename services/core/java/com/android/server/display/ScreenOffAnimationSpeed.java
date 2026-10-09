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
 * The user's playback speed for custom screen-off effects, as a percent of normal speed: 200
 * plays twice as fast. Stock never reads it. Each effect passes its own base duration.
 */
final class ScreenOffAnimationSpeed {
    static final String SETTING_KEY = "lineage_screen_off_animation_speed";
    static final int DEFAULT_PERCENT = 100;
    static final int MIN_PERCENT = 50;
    static final int MAX_PERCENT = 200;

    private ScreenOffAnimationSpeed() {}

    /** Clamps a stored value into [{@link #MIN_PERCENT}, {@link #MAX_PERCENT}]. */
    static int percent(int raw) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, raw));
    }

    /** Returns {@code baseMillis} played at {@code percent} speed, after clamping it. */
    static long scaledDurationMillis(long baseMillis, int percent) {
        return baseMillis * 100 / percent(percent);
    }
}
