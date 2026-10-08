/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.view.DisplayInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ColorFadeTest {
    private static final int DISPLAY_ID = 123;

    private Context mContext;

    @Mock private DisplayManagerInternal mDisplayManagerInternalMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = getInstrumentation().getTargetContext();
    }

    @Test
    public void testPrepareColorFadeForInvalidDisplay() {
        when(mDisplayManagerInternalMock.getDisplayInfo(eq(DISPLAY_ID))).thenReturn(null);
        ColorFade colorFade = new ColorFade(DISPLAY_ID, mDisplayManagerInternalMock);
        assertFalse(colorFade.prepare(mContext, ColorFade.MODE_FADE));
    }

    @Test
    public void testPrepareCrtNeverCapturesScreen() {
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = 100;
        info.logicalHeight = 200;
        info.logicalDensityDpi = 320;
        when(mDisplayManagerInternalMock.getDisplayInfo(eq(DISPLAY_ID))).thenReturn(info);
        ColorFade colorFade = new ColorFade(DISPLAY_ID, mDisplayManagerInternalMock);

        assertTrue(colorFade.prepare(mContext, ColorFade.MODE_CRT));
        assertTrue(colorFade.draw(0.25f));
        assertTrue(colorFade.draw(0f));
        colorFade.dismiss();

        verify(mDisplayManagerInternalMock, never()).systemScreenshot(anyInt());
    }
}
