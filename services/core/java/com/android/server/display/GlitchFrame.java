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
 * Shader uniforms for one glitch frame. {@link GlitchSchedule} fills one instance per frame and
 * {@link ColorFade} reuses it, so drawing allocates nothing.
 */
final class GlitchFrame {
    /** Which random pattern the frame shows; changes a fixed number of times per animation. */
    int tick;
    /** Effect strength, 0 to 1. */
    float intensity;
    /** Red/blue channel offset as a fraction of the display width. */
    float split;
    /** Tear: torn slice count. Corrupt: share of corrupted blocks. Signal loss: band-kick odds. */
    float share;
    /** Tear: 1 dims this frame to 12 % (a dropped frame), else 0. */
    float dropFrame;
    /** Tear: vertical jitter; Signal loss: vertical roll. Fraction of the display height. */
    float roll;
    /** Signal loss: static noise mix, 0 to 1. */
    float staticAmount;
    /** Share of bands or blocks forced black (Tear, Corrupt) or black overlay (Signal loss). */
    float dark;
}
