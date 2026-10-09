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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CrtDozeEntryGateTest {
    private final CrtDozeEntryGate mGate = new CrtDozeEntryGate();

    @Test
    public void startsDisarmed() {
        assertFalse(mGate.isArmed());
        assertFalse(mGate.consume(false, true, true, true));
    }

    @Test
    public void awakeUpdate_keepsTheArm() {
        mGate.onAwakeScreenOn();

        assertFalse(mGate.consume(true, false, true, true));
        assertTrue(mGate.isArmed());
    }

    @Test
    public void firstDozeUpdate_playsOnce() {
        mGate.onAwakeScreenOn();

        assertTrue(mGate.consume(false, true, true, true));
        assertFalse(mGate.isArmed());
    }

    @Test
    public void holdRelease_neverPlaysLate() {
        // SystemUI holds STATE_ON under POLICY_DOZE, then releases into STATE_DOZE seconds later.
        mGate.onAwakeScreenOn();
        mGate.consume(false, true, true, true);

        assertFalse(mGate.consume(false, true, true, true));
    }

    @Test
    public void fallbackAtTheLock_stillConsumesTheArm() {
        mGate.onAwakeScreenOn();

        assertFalse("screen not on", mGate.consume(false, true, false, true));
        assertFalse("late retry", mGate.consume(false, true, true, true));
    }

    @Test
    public void fadedScreen_consumesWithoutPlaying() {
        mGate.onAwakeScreenOn();

        assertFalse(mGate.consume(false, true, true, false));
        assertFalse(mGate.isArmed());
    }

    @Test
    public void offPolicy_consumesWithoutPlaying() {
        mGate.onAwakeScreenOn();

        assertFalse(mGate.consume(false, false, true, true));
        assertFalse(mGate.isArmed());
    }

    @Test
    public void disarm_clearsTheArm() {
        mGate.onAwakeScreenOn();
        mGate.disarm();

        assertFalse(mGate.consume(false, true, true, true));
    }

    @Test
    public void rearmAfterWake_playsAtTheNextLock() {
        mGate.onAwakeScreenOn();
        mGate.consume(false, true, true, true);
        mGate.onAwakeScreenOn();

        assertTrue(mGate.consume(false, true, true, true));
    }
}
