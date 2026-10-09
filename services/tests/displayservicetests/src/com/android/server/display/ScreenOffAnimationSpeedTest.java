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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ScreenOffAnimationSpeedTest {
    @Test
    public void settingKey_isPrivateSecureKey() {
        assertEquals("lineage_screen_off_animation_speed", ScreenOffAnimationSpeed.SETTING_KEY);
        assertEquals(100, ScreenOffAnimationSpeed.DEFAULT_PERCENT);
    }

    @Test
    public void percent_clampsIntoRange() {
        assertEquals(50, ScreenOffAnimationSpeed.percent(0));
        assertEquals(50, ScreenOffAnimationSpeed.percent(-100));
        assertEquals(50, ScreenOffAnimationSpeed.percent(49));
        assertEquals(75, ScreenOffAnimationSpeed.percent(75));
        assertEquals(200, ScreenOffAnimationSpeed.percent(201));
        assertEquals(200, ScreenOffAnimationSpeed.percent(Integer.MAX_VALUE));
    }

    @Test
    public void scaledDuration_crtListValues() {
        assertEquals(1000L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 50));
        assertEquals(666L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 75));
        assertEquals(500L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 100));
        assertEquals(333L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 150));
        assertEquals(250L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 200));
    }

    @Test
    public void scaledDuration_clampsPercentFirst() {
        assertEquals(1000L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 0));
        assertEquals(250L, ScreenOffAnimationSpeed.scaledDurationMillis(500, 10_000));
    }

    @Test
    public void scaledDuration_usesEachEffectsOwnBase() {
        assertEquals(1600L, ScreenOffAnimationSpeed.scaledDurationMillis(800, 50));
    }
}
