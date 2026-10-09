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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GlitchScheduleTest {
    private static final ScreenOffEffect[] GLITCHES = {
            ScreenOffEffect.TEAR, ScreenOffEffect.CORRUPT, ScreenOffEffect.SIGNAL_LOSS};

    private static GlitchFrame at(ScreenOffEffect effect, float level) {
        GlitchFrame frame = new GlitchFrame();
        GlitchSchedule.at(effect, level, frame);
        return frame;
    }

    @Test
    public void dark_neverDecreases_andIsOneAtLevelZero() {
        for (ScreenOffEffect effect : GLITCHES) {
            float previous = 0f;
            for (int i = 1000; i >= 0; i--) {
                float dark = at(effect, i / 1000f).dark;
                assertTrue(effect + " at " + i, dark >= previous);
                previous = dark;
            }
            assertEquals(effect.name(), 1f, at(effect, 0f).dark, 0f);
        }
    }

    @Test
    public void levelOne_isQuiet() {
        for (ScreenOffEffect effect : GLITCHES) {
            GlitchFrame f = at(effect, 1f);
            assertEquals(effect.name(), 0, f.tick);
            assertEquals(effect.name(), 0f, f.intensity, 0f);
            assertEquals(effect.name(), 0f, f.dark, 0f);
            assertEquals(effect.name(), 0f, f.dropFrame, 0f);
            assertEquals(effect.name(), 0f, f.staticAmount, 0f);
            assertEquals(effect.name(), 0f, f.roll, 0f);
        }
    }

    @Test
    public void intensity_staysInRange() {
        for (ScreenOffEffect effect : GLITCHES) {
            for (int i = 0; i <= 1000; i++) {
                float v = at(effect, i / 1000f).intensity;
                assertTrue(effect + " at " + i, v >= 0f && v <= 1f);
            }
        }
    }

    @Test
    public void sameLevel_givesSameFrame() {
        for (ScreenOffEffect effect : GLITCHES) {
            GlitchFrame a = at(effect, 0.37f);
            GlitchFrame b = at(effect, 0.37f);
            assertEquals(a.tick, b.tick);
            assertEquals(a.intensity, b.intensity, 0f);
            assertEquals(a.split, b.split, 0f);
            assertEquals(a.share, b.share, 0f);
            assertEquals(a.dropFrame, b.dropFrame, 0f);
            assertEquals(a.roll, b.roll, 0f);
            assertEquals(a.staticAmount, b.staticAmount, 0f);
            assertEquals(a.dark, b.dark, 0f);
        }
    }

    @Test
    public void at_overwritesEveryFieldOfAReusedFrame() {
        GlitchFrame frame = new GlitchFrame();
        frame.dropFrame = 1f;
        frame.roll = 0.5f;
        frame.staticAmount = 0.5f;
        GlitchSchedule.at(ScreenOffEffect.CORRUPT, 0.2f, frame);
        assertEquals(0f, frame.dropFrame, 0f);
        assertEquals(0f, frame.roll, 0f);
        assertEquals(0f, frame.staticAmount, 0f);
        assertEquals(at(ScreenOffEffect.CORRUPT, 0.2f).dark, frame.dark, 0f);
    }

    @Test
    public void ticks_perEffect() {
        assertEquals(9, at(ScreenOffEffect.TEAR, 0.5f).tick);
        assertEquals(7, at(ScreenOffEffect.CORRUPT, 0.5f).tick);
        assertEquals(11, at(ScreenOffEffect.SIGNAL_LOSS, 0.5f).tick);
    }

    @Test
    public void tear_phases() {
        assertEquals(0f, at(ScreenOffEffect.TEAR, 0.41f).dark, 0f);
        assertTrue(at(ScreenOffEffect.TEAR, 0.39f).dark > 0f);
        assertEquals(1f, at(ScreenOffEffect.TEAR, 0.2f).intensity, 0f);
        for (int i = 750; i <= 1000; i++) {
            assertEquals(0f, at(ScreenOffEffect.TEAR, i / 1000f).dropFrame, 0f);
        }
        for (int i = 0; i < 100; i++) {
            assertEquals(0f, at(ScreenOffEffect.TEAR, i / 1000f).dropFrame, 0f);
        }
        assertEquals(13f, at(ScreenOffEffect.TEAR, 0.1f).share, 0f);
    }

    @Test
    public void tear_dropsSomeFramesMidway() {
        int dropped = 0;
        for (int i = 110; i < 750; i++) {
            dropped += at(ScreenOffEffect.TEAR, i / 1000f).dropFrame > 0f ? 1 : 0;
        }
        assertTrue("dropped=" + dropped, dropped > 0);
    }

    @Test
    public void corrupt_phases() {
        assertEquals(0f, at(ScreenOffEffect.CORRUPT, 0.46f).dark, 0f);
        assertTrue(at(ScreenOffEffect.CORRUPT, 0.44f).dark > 0f);
        assertEquals(0.39f, at(ScreenOffEffect.CORRUPT, 0.2f).share, 1e-6f);
    }

    @Test
    public void signalLoss_phases() {
        assertEquals(0f, at(ScreenOffEffect.SIGNAL_LOSS, 0.21f).dark, 0f);
        assertTrue(at(ScreenOffEffect.SIGNAL_LOSS, 0.19f).dark > 0f);
        assertEquals(0f, at(ScreenOffEffect.SIGNAL_LOSS, 0.71f).staticAmount, 0f);
        assertTrue(at(ScreenOffEffect.SIGNAL_LOSS, 0.5f).staticAmount > 0f);
        for (int i = 701; i <= 1000; i++) {
            assertEquals(0f, at(ScreenOffEffect.SIGNAL_LOSS, i / 1000f).roll, 0f);
        }
    }

    @Test
    public void hash_isDeterministicAndInRange() {
        for (int seed = 0; seed < 5; seed++) {
            for (int tick = 0; tick < 30; tick++) {
                float h = GlitchSchedule.hash(seed, tick);
                assertTrue(h >= 0f && h < 1f);
                assertEquals(h, GlitchSchedule.hash(seed, tick), 0f);
            }
        }
    }

    @Test
    public void nonGlitchEffects_areRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GlitchSchedule.at(ScreenOffEffect.CRT, 0.5f, new GlitchFrame()));
        assertThrows(IllegalArgumentException.class,
                () -> GlitchSchedule.at(ScreenOffEffect.STOCK, 0.5f, new GlitchFrame()));
    }
}
