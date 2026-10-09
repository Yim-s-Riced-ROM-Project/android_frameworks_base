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
 * Decides once per awake period whether the CRT screen-off plays at a doze entry.
 *
 * <p>It arms when the screen turns on for an awake user. DisplayPowerController consults it on
 * every ON, DOZE, and DOZE_SUSPEND target update, and the screen-off branch disarms it. The first
 * such update under a non-awake policy disarms it, and plays only when that update is a doze
 * request with the screen still ON and unfaded. SystemUI can hold {@code STATE_ON} under {@code POLICY_DOZE} for seconds while it
 * animates the lockscreen into AOD, so CRT must start at the lock or not at all: a later doze
 * update finds the gate disarmed and never plays over AOD that is already showing. Pure Java;
 * called on the DisplayPowerController handler thread.
 */
final class CrtDozeEntryGate {
    private boolean mArmed;

    /** Arms the gate after the screen turned on under a bright or dim policy. */
    void onAwakeScreenOn() {
        mArmed = true;
    }

    /** Clears the gate, e.g. when the screen-off branch runs. */
    void disarm() {
        mArmed = false;
    }

    boolean isArmed() {
        return mArmed;
    }

    /**
     * Returns whether CRT plays on this update. An awake policy keeps the arm and returns false.
     * Any other policy consumes the arm, returning true only for a doze request with the screen
     * ON and the fade level at 1.
     */
    boolean consume(boolean awakePolicy, boolean dozePolicy, boolean screenOn, boolean unfaded) {
        if (awakePolicy || !mArmed) {
            return false;
        }
        mArmed = false;
        return dozePolicy && screenOn && unfaded;
    }
}
