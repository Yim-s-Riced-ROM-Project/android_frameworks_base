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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ScreenOffEffectTest {
    @Test
    public void from_mapsEachStoredValue() {
        assertEquals(ScreenOffEffect.STOCK, ScreenOffEffect.from(0));
        assertEquals(ScreenOffEffect.CRT, ScreenOffEffect.from(1));
        assertEquals(ScreenOffEffect.TEAR, ScreenOffEffect.from(2));
        assertEquals(ScreenOffEffect.CORRUPT, ScreenOffEffect.from(3));
        assertEquals(ScreenOffEffect.SIGNAL_LOSS, ScreenOffEffect.from(4));
    }

    @Test
    public void from_unknownValues_areStock() {
        assertEquals(ScreenOffEffect.STOCK, ScreenOffEffect.from(-1));
        assertEquals(ScreenOffEffect.STOCK, ScreenOffEffect.from(5));
        assertEquals(ScreenOffEffect.STOCK, ScreenOffEffect.from(99));
    }

    @Test
    public void baseDurations_matchTheSpec() {
        assertEquals(500L, ScreenOffEffect.CRT.baseDurationMillis);
        assertEquals(600L, ScreenOffEffect.TEAR.baseDurationMillis);
        assertEquals(600L, ScreenOffEffect.CORRUPT.baseDurationMillis);
        assertEquals(600L, ScreenOffEffect.SIGNAL_LOSS.baseDurationMillis);
    }

    @Test
    public void isGlitch_onlyForScreenshotEffects() {
        assertFalse(ScreenOffEffect.STOCK.isGlitch());
        assertFalse(ScreenOffEffect.CRT.isGlitch());
        assertTrue(ScreenOffEffect.TEAR.isGlitch());
        assertTrue(ScreenOffEffect.CORRUPT.isGlitch());
        assertTrue(ScreenOffEffect.SIGNAL_LOSS.isGlitch());
    }
}
