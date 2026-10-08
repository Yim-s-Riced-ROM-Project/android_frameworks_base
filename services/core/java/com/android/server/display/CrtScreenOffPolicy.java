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
 * Decides whether a default-display screen-off plays the CRT animation. The setting is the
 * per-user {@code Settings.Secure} key SystemUI and Settings already use: 1 selects CRT, and any
 * other or missing value selects Stock. Pure Java so it is testable off device.
 */
final class CrtScreenOffPolicy {
    static final String SETTING_KEY = "lineage_screen_off_animation";
    static final int SETTING_STOCK = 0;
    static final int SETTING_CRT = 1;

    /** Which display transition is animating. */
    enum Path { OFF, DOZE }

    /** The outcome, in gate order. Every value except {@link #CRT} runs the stock path. */
    enum Decision {
        CRT,
        STOCK_SELECTED,
        NOT_DEFAULT_DISPLAY,
        COLOR_FADE_DISABLED,
        SKIP_SCREEN_OFF_TRANSITION,
        DISPLAY_NOT_ON,
        PREPARE_FAILED;

        /** Whether this outcome is a CRT fallback worth logging on the default display. */
        boolean isRecordedFallback() {
            return this != CRT && this != STOCK_SELECTED && this != NOT_DEFAULT_DISPLAY;
        }
    }

    private CrtScreenOffPolicy() {}

    /**
     * Returns the first rejecting gate, or {@link Decision#CRT}. {@code performScreenOffTransition}
     * applies only to {@link Path#OFF}; the doze path has no stock screen-off transition.
     */
    static Decision decide(Path path, int settingValue, boolean defaultDisplay,
            boolean colorFadeEnabled, boolean performScreenOffTransition, boolean screenOn) {
        if (settingValue != SETTING_CRT) return Decision.STOCK_SELECTED;
        if (!defaultDisplay) return Decision.NOT_DEFAULT_DISPLAY;
        if (!colorFadeEnabled) return Decision.COLOR_FADE_DISABLED;
        if (path == Path.OFF && !performScreenOffTransition) {
            return Decision.SKIP_SCREEN_OFF_TRANSITION;
        }
        if (!screenOn) return Decision.DISPLAY_NOT_ON;
        return Decision.CRT;
    }
}
