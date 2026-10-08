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

import static com.android.server.display.CrtScreenOffPolicy.Decision;
import static com.android.server.display.CrtScreenOffPolicy.Path;
import static com.android.server.display.CrtScreenOffPolicy.SETTING_CRT;
import static com.android.server.display.CrtScreenOffPolicy.SETTING_STOCK;
import static com.android.server.display.CrtScreenOffPolicy.decide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CrtScreenOffPolicyTest {
    @Test
    public void settingKey_matchesSystemUi() {
        assertEquals("lineage_screen_off_animation", CrtScreenOffPolicy.SETTING_KEY);
    }

    @Test
    public void crtSelected_eligibleOff_playsCrt() {
        assertEquals(Decision.CRT, decide(Path.OFF, SETTING_CRT, true, true, true, true));
    }

    @Test
    public void crtSelected_eligibleDoze_playsCrtEvenWithoutOffTransition() {
        assertEquals(Decision.CRT, decide(Path.DOZE, SETTING_CRT, true, true, false, true));
    }

    @Test
    public void anyValueOtherThanCrt_isStock() {
        assertEquals(Decision.STOCK_SELECTED,
                decide(Path.OFF, SETTING_STOCK, true, true, true, true));
        assertEquals(Decision.STOCK_SELECTED, decide(Path.OFF, 2, true, true, true, true));
        assertEquals(Decision.STOCK_SELECTED, decide(Path.OFF, -1, true, true, true, true));
    }

    @Test
    public void gates_rejectInOrder() {
        assertEquals(Decision.STOCK_SELECTED,
                decide(Path.OFF, SETTING_STOCK, false, false, false, false));
        assertEquals(Decision.NOT_DEFAULT_DISPLAY,
                decide(Path.OFF, SETTING_CRT, false, false, false, false));
        assertEquals(Decision.COLOR_FADE_DISABLED,
                decide(Path.OFF, SETTING_CRT, true, false, false, false));
        assertEquals(Decision.SKIP_SCREEN_OFF_TRANSITION,
                decide(Path.OFF, SETTING_CRT, true, true, false, false));
        assertEquals(Decision.DISPLAY_NOT_ON,
                decide(Path.OFF, SETTING_CRT, true, true, true, false));
    }

    @Test
    public void isRecordedFallback_excludesStockAndSecondaryDisplays() {
        assertFalse(Decision.CRT.isRecordedFallback());
        assertFalse(Decision.STOCK_SELECTED.isRecordedFallback());
        assertFalse(Decision.NOT_DEFAULT_DISPLAY.isRecordedFallback());
        assertTrue(Decision.PREPARE_FAILED.isRecordedFallback());
        assertTrue(Decision.DISPLAY_NOT_ON.isRecordedFallback());
    }
}
