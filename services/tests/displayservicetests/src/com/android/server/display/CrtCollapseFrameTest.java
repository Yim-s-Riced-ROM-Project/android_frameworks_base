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

import static com.android.server.display.CrtCollapseFrame.LAYER_BLUE_FRINGE;
import static com.android.server.display.CrtCollapseFrame.LAYER_CORE;
import static com.android.server.display.CrtCollapseFrame.LAYER_COUNT;
import static com.android.server.display.CrtCollapseFrame.LAYER_MASK_BOTTOM;
import static com.android.server.display.CrtCollapseFrame.LAYER_MASK_LEFT;
import static com.android.server.display.CrtCollapseFrame.LAYER_MASK_RIGHT;
import static com.android.server.display.CrtCollapseFrame.LAYER_MASK_TOP;
import static com.android.server.display.CrtCollapseFrame.LAYER_RED_FRINGE;
import static com.android.server.display.CrtCollapseFrame.LAYER_TRAIL;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CrtCollapseFrameTest {
    private static final float W = 1000f;
    private static final float H = 2000f;
    private static final float DENSITY = 3f;
    private static final float EPS = 1e-3f;

    private final CrtCollapseFrame mFrame = new CrtCollapseFrame();

    @Test
    public void fullyVisible_showsNoLayer() {
        mFrame.update(1f, W, H, DENSITY);
        for (int i = 0; i < LAYER_COUNT; i++) {
            assertEquals("layer " + i, 0f, mFrame.alpha[i], 0f);
        }
    }

    @Test
    public void closed_masksCoverWholeDisplayInOpaqueBlack() {
        mFrame.update(0f, W, H, DENSITY);
        assertRect(LAYER_MASK_TOP, 0f, 0f, W, 997f);
        assertRect(LAYER_MASK_BOTTOM, 0f, 1003f, W, H);
        assertRect(LAYER_MASK_LEFT, 0f, 997f, 500f, 1003f);
        assertRect(LAYER_MASK_RIGHT, 500f, 997f, W, 1003f);
        for (int i = LAYER_MASK_TOP; i <= LAYER_MASK_RIGHT; i++) {
            assertEquals(1f, mFrame.alpha[i], 0f);
        }
        for (int i = LAYER_TRAIL; i <= LAYER_CORE; i++) {
            assertEquals(0f, mFrame.alpha[i], 0f);
        }
    }

    @Test
    public void verticalPhase_overscannedApertureHasNoSideMasksOrBeam() {
        mFrame.update(0.75f, W, H, DENSITY);
        assertRect(LAYER_MASK_TOP, 0f, 0f, W, 250f);
        assertRect(LAYER_MASK_BOTTOM, 0f, 1750f, W, H);
        assertEquals(0f, mFrame.alpha[LAYER_MASK_LEFT], 0f);
        assertEquals(0f, mFrame.alpha[LAYER_MASK_RIGHT], 0f);
        assertEquals(0f, mFrame.alpha[LAYER_CORE], 0f);
    }

    @Test
    public void beamPeak_drawsTrailFringesAndCoreAtFullEmission() {
        mFrame.update(0.25f, W, H, DENSITY);
        assertRect(LAYER_MASK_LEFT, 0f, 997f, 102.5f, 1003f);
        assertRect(LAYER_MASK_RIGHT, 897.5f, 997f, W, 1003f);
        assertRect(LAYER_TRAIL, 102.5f, 985f, 897.5f, 1015f);
        assertRect(LAYER_RED_FRINGE, 102.5f, 991f, 897.5f, 997f);
        assertRect(LAYER_BLUE_FRINGE, 102.5f, 1003f, 897.5f, 1009f);
        assertRect(LAYER_CORE, 102.5f, 997f, 897.5f, 1003f);
        assertEquals(1f, mFrame.alpha[LAYER_CORE], EPS);
        assertEquals(0.30f, mFrame.alpha[LAYER_TRAIL], EPS);
        assertEquals(0.22f, mFrame.alpha[LAYER_RED_FRINGE], EPS);
        assertEquals(0.22f, mFrame.alpha[LAYER_BLUE_FRINGE], EPS);
    }

    @Test
    public void closure_isMonotonic() {
        float lastTopMaskBottom = 0f;
        float lastLeftMaskRight = -1f;
        for (int step = 100; step >= 0; step--) {
            mFrame.update(step / 100f, W, H, DENSITY);
            if (step == 100) continue;
            assertTrue(mFrame.bottom[LAYER_MASK_TOP] >= lastTopMaskBottom - EPS);
            lastTopMaskBottom = mFrame.bottom[LAYER_MASK_TOP];
            if (mFrame.alpha[LAYER_MASK_LEFT] > 0f) {
                assertTrue(mFrame.right[LAYER_MASK_LEFT] >= lastLeftMaskRight - EPS);
                lastLeftMaskRight = mFrame.right[LAYER_MASK_LEFT];
            }
        }
    }

    @Test
    public void lowDensity_beamNeverThinnerThanOnePixel() {
        mFrame.update(0f, W, H, 0.5f);
        assertEquals(999f, mFrame.bottom[LAYER_MASK_TOP], EPS);
        assertEquals(1001f, mFrame.top[LAYER_MASK_BOTTOM], EPS);
    }

    @Test
    public void outOfRangeLevels_clamp() {
        CrtCollapseFrame expected = new CrtCollapseFrame();
        expected.update(0f, W, H, DENSITY);
        mFrame.update(-1f, W, H, DENSITY);
        assertArrayEquals(expected.bottom, mFrame.bottom, 0f);
        mFrame.update(2f, W, H, DENSITY);
        assertEquals(0f, mFrame.alpha[LAYER_MASK_TOP], 0f);
    }

    @Test
    public void layerColours_matchShippedPaints() {
        float[] black = {0f, 0f, 0f};
        float[] white = {1f, 1f, 1f};
        assertArrayEquals(black, CrtCollapseFrame.LAYER_RGB[LAYER_MASK_TOP], 0f);
        assertArrayEquals(white, CrtCollapseFrame.LAYER_RGB[LAYER_TRAIL], 0f);
        assertArrayEquals(new float[] {1f, 0f, 0f},
                CrtCollapseFrame.LAYER_RGB[LAYER_RED_FRINGE], 0f);
        assertArrayEquals(new float[] {0f, 0f, 1f},
                CrtCollapseFrame.LAYER_RGB[LAYER_BLUE_FRINGE], 0f);
        assertArrayEquals(white, CrtCollapseFrame.LAYER_RGB[LAYER_CORE], 0f);
    }

    private void assertRect(int layer, float l, float t, float r, float b) {
        assertEquals("left " + layer, l, mFrame.left[layer], EPS);
        assertEquals("top " + layer, t, mFrame.top[layer], EPS);
        assertEquals("right " + layer, r, mFrame.right[layer], EPS);
        assertEquals("bottom " + layer, b, mFrame.bottom[layer], EPS);
    }
}
