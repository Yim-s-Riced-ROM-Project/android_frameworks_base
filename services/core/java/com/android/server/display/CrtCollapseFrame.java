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
 * One frame of the CRT screen-off animation as solid-colour rectangles, one per layer.
 *
 * <p>Port of SystemUI's {@code CrtCollapseReveal} geometry and {@code LightRevealScrim} CRT drawing
 * (framework 224468bd), so the system-server animation keeps the shipped look: black masks close
 * vertically toward the centre, then the remaining aperture becomes a white beam with a phosphor
 * trail and red/blue fringe that contracts horizontally. Level 1 shows nothing; level 0 is opaque
 * black. Pure Java with no allocation in {@link #update}.
 */
final class CrtCollapseFrame {
    static final int LAYER_MASK_TOP = 0;
    static final int LAYER_MASK_BOTTOM = 1;
    static final int LAYER_MASK_LEFT = 2;
    static final int LAYER_MASK_RIGHT = 3;
    static final int LAYER_TRAIL = 4;
    static final int LAYER_RED_FRINGE = 5;
    static final int LAYER_BLUE_FRINGE = 6;
    static final int LAYER_CORE = 7;
    static final int LAYER_COUNT = 8;

    /** RGB per layer. Z-order equals the layer index. */
    static final float[][] LAYER_RGB = {
            {0f, 0f, 0f}, {0f, 0f, 0f}, {0f, 0f, 0f}, {0f, 0f, 0f},
            {1f, 1f, 1f}, {1f, 0f, 0f}, {0f, 0f, 1f}, {1f, 1f, 1f},
    };

    /** Share of the transition spent closing the aperture vertically. */
    private static final float VERTICAL_PHASE_END = 0.5f;
    /** Horizontal aperture overscan per side, so rounded corners and cutouts never show. */
    private static final float HORIZONTAL_OVERSCAN_FRACTION = 0.03f;
    private static final float MAX_FRINGE_ALPHA = 0.22f;
    private static final float MAX_TRAIL_ALPHA = 0.30f;

    final float[] left = new float[LAYER_COUNT];
    final float[] top = new float[LAYER_COUNT];
    final float[] right = new float[LAYER_COUNT];
    final float[] bottom = new float[LAYER_COUNT];
    final float[] alpha = new float[LAYER_COUNT];

    /** Computes the frame for {@code level} (1 visible, 0 closed) on a display of this size. */
    void update(float level, float width, float height, float density) {
        clear();
        final float amount = clamp01(level);
        if (amount >= 1f) {
            return;
        }
        final float progress = 1f - amount;
        final float vertical = clamp01(progress / VERTICAL_PHASE_END);
        final float beam = clamp01((progress - VERTICAL_PHASE_END) / (1f - VERTICAL_PHASE_END));
        final float centerX = width / 2f;
        final float centerY = height / 2f;
        final float beamHalfHeight = Math.max(1f, density);
        // Never closes below the beam, so the closure stays monotonic into the beam phase.
        final float halfApertureHeight =
                Math.max(beamHalfHeight, centerY * (1f - vertical * vertical));
        final float halfApertureWidth =
                (centerX + width * HORIZONTAL_OVERSCAN_FRACTION) * (1f - beam * beam);
        final float apertureLeft = centerX - halfApertureWidth;
        final float apertureTop = centerY - halfApertureHeight;
        final float apertureRight = centerX + halfApertureWidth;
        final float apertureBottom = centerY + halfApertureHeight;

        set(LAYER_MASK_TOP, 0f, 0f, width, apertureTop, 1f);
        set(LAYER_MASK_BOTTOM, 0f, apertureBottom, width, height, 1f);
        if (apertureLeft > 0f) {
            set(LAYER_MASK_LEFT, 0f, apertureTop, apertureLeft, apertureBottom, 1f);
        }
        if (apertureRight < width) {
            set(LAYER_MASK_RIGHT, apertureRight, apertureTop, width, apertureBottom, 1f);
        }
        final float emission = (beam == 0f || beam == 1f) ? 0f : 4f * beam * (1f - beam);
        if (emission > 0f) {
            setBeam(apertureLeft, apertureRight, (apertureTop + apertureBottom) / 2f,
                    beamHalfHeight, density, emission);
        }
    }

    /** Phosphor trail, red fringe above, blue fringe below, then the white core on top. */
    private void setBeam(float l, float r, float centerY, float beamHalfHeight, float density,
            float emission) {
        final float glowHalfHeight = Math.max(4f, 5f * density);
        final float fringeOffset = Math.max(1f, 2f * density);
        final float coreTop = centerY - beamHalfHeight;
        final float coreBottom = centerY + beamHalfHeight;
        set(LAYER_TRAIL, l, centerY - glowHalfHeight, r, centerY + glowHalfHeight,
                emission * MAX_TRAIL_ALPHA);
        set(LAYER_RED_FRINGE, l, coreTop - fringeOffset, r, coreTop, emission * MAX_FRINGE_ALPHA);
        set(LAYER_BLUE_FRINGE, l, coreBottom, r, coreBottom + fringeOffset,
                emission * MAX_FRINGE_ALPHA);
        set(LAYER_CORE, l, coreTop, r, coreBottom, emission);
    }

    private void clear() {
        for (int i = 0; i < LAYER_COUNT; i++) {
            set(i, 0f, 0f, 0f, 0f, 0f);
        }
    }

    private void set(int layer, float l, float t, float r, float b, float a) {
        left[layer] = l;
        top[layer] = t;
        right[layer] = r;
        bottom[layer] = b;
        alpha[layer] = a;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
