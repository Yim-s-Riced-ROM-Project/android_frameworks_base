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
 * Maps a glitch effect and a ColorFade level (1 = screen on, 0 = black) to the frame's shader
 * uniforms. Values follow the approved "Glitch Screen-Off Directions" prototype, whose pixel
 * sizes were authored for a 720 x 1560 reference screen. Pure Java, no allocation.
 */
final class GlitchSchedule {
    static final int TEAR_TICKS = 18;
    static final int CORRUPT_TICKS = 14;
    static final int SIGNAL_LOSS_TICKS = 22;

    private static final float REFERENCE_WIDTH_PX = 720f;
    private static final float REFERENCE_HEIGHT_PX = 1560f;
    private static final int TEAR_SEED = 1000;
    private static final int SIGNAL_LOSS_SEED = 3000;

    private GlitchSchedule() {}

    /** Fills {@code out} for {@code effect} at {@code level}; only glitch effects are valid. */
    static void at(ScreenOffEffect effect, float level, GlitchFrame out) {
        final float p = 1f - Math.max(0f, Math.min(1f, level));
        switch (effect) {
            case TEAR:
                tear(p, out);
                break;
            case CORRUPT:
                corrupt(p, out);
                break;
            case SIGNAL_LOSS:
                signalLoss(p, out);
                break;
            default:
                throw new IllegalArgumentException("not a glitch effect: " + effect);
        }
    }

    private static void tear(float p, GlitchFrame f) {
        f.tick = tick(p, TEAR_TICKS);
        f.intensity = (float) Math.pow(Math.min(1f, p / 0.75f), 1.5);
        f.split = (4f + f.intensity * 28f) / REFERENCE_WIDTH_PX;
        f.share = 1f + (float) Math.floor(f.intensity * 12f);
        f.dropFrame = p > 0.25f && p < 0.9f
                && hash(TEAR_SEED, f.tick) < f.intensity * 0.22f ? 1f : 0f;
        f.roll = (hash(TEAR_SEED + 1, f.tick) - 0.5f) * f.intensity * 24f / REFERENCE_HEIGHT_PX;
        f.staticAmount = 0f;
        f.dark = smoothstep(0.6f, 1f, p);
    }

    private static void corrupt(float p, GlitchFrame f) {
        f.tick = tick(p, CORRUPT_TICKS);
        f.intensity = (float) Math.pow(Math.min(1f, p / 0.75f), 1.3);
        f.split = f.intensity * 8f / REFERENCE_WIDTH_PX;
        f.share = 0.01f + f.intensity * 0.38f;
        f.dropFrame = 0f;
        f.roll = 0f;
        f.staticAmount = 0f;
        f.dark = smoothstep(0.55f, 1f, p);
    }

    private static void signalLoss(float p, GlitchFrame f) {
        f.tick = tick(p, SIGNAL_LOSS_TICKS);
        f.intensity = Math.min(1f, p / 0.8f);
        f.split = (3f + f.intensity * 18f) / REFERENCE_WIDTH_PX;
        f.share = f.intensity * 0.12f;
        f.dropFrame = 0f;
        f.roll = p > 0.3f && hash(SIGNAL_LOSS_SEED, f.tick) < f.intensity * 0.55f
                ? hash(SIGNAL_LOSS_SEED + 1, f.tick) * f.intensity * 0.45f : 0f;
        f.staticAmount = smoothstep(0.3f, 0.85f, p) * 0.45f + smoothstep(0.8f, 1f, p) * 0.3f;
        f.dark = smoothstep(0.8f, 1f, p);
    }

    private static int tick(float p, int ticks) {
        return (int) Math.floor(p * ticks);
    }

    /** Hermite step from 0 at {@code edge0} to exactly 1 at and after {@code edge1}. */
    private static float smoothstep(float edge0, float edge1, float x) {
        final float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** Deterministic integer hash of (seed, tick) into [0, 1). */
    static float hash(int seed, int tick) {
        int h = seed * 0x9E3779B1 + tick * 0x85EBCA6B;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return (h >>> 8) / 16777216f;
    }
}
