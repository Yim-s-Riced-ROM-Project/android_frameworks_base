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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.PrintWriter;
import java.io.StringWriter;

@RunWith(JUnit4.class)
public class CrtScreenOffRecorderTest {
    private final CrtScreenOffRecorder mRecorder = new CrtScreenOffRecorder();

    @Test
    public void startThenComplete_logsBothAndCounts() {
        assertEquals("start path=OFF effect=CRT setting=1 speed=100 durationMs=500",
                mRecorder.started(10, Path.OFF, 1, ScreenOffEffect.CRT, 100, 500L));
        assertTrue(mRecorder.isRunning());
        assertEquals("end path=OFF outcome=COMPLETED",
                mRecorder.ended(510, CrtScreenOffRecorder.Result.COMPLETED));
        assertFalse(mRecorder.isRunning());
        String dump = dump();
        assertTrue(dump, dump.contains("starts=1"));
        assertTrue(dump, dump.contains("completions=1"));
        assertTrue(dump, dump.contains("lastOutcome=COMPLETED"));
    }

    @Test
    public void isRunning_path_matchesOnlyTheRunningPath() {
        assertFalse(mRecorder.isRunning(Path.DOZE));
        mRecorder.started(0, Path.DOZE, 1, ScreenOffEffect.CRT, 100, 500L);
        assertTrue(mRecorder.isRunning(Path.DOZE));
        assertFalse(mRecorder.isRunning(Path.OFF));
        mRecorder.ended(500, CrtScreenOffRecorder.Result.COMPLETED);
        assertFalse(mRecorder.isRunning(Path.DOZE));
    }

    @Test
    public void glitchStart_logsEffectAndDumpsLastEffect() {
        assertEquals("start path=DOZE effect=SIGNAL_LOSS setting=4 speed=50 durationMs=1200",
                mRecorder.started(0, Path.DOZE, 4, ScreenOffEffect.SIGNAL_LOSS, 50, 1200L));
        String dump = dump();
        assertTrue(dump, dump.contains("lastEffect=SIGNAL_LOSS\n"));
        assertTrue(dump, dump.contains("lastDurationMs=1200\n"));
    }

    @Test
    public void dump_beforeAnyStart_reportsNoEffect() {
        assertTrue(dump().contains("lastEffect=NONE\n"));
    }

    @Test
    public void endWithoutStart_isNoOp() {
        assertNull(mRecorder.ended(1, CrtScreenOffRecorder.Result.COMPLETED));
        assertTrue(dump().contains("completions=0"));
    }

    @Test
    public void wakeCancel_isRecordedOnce() {
        mRecorder.started(0, Path.DOZE, 1, ScreenOffEffect.CRT, 100, 500L);
        assertEquals("end path=DOZE outcome=CANCELLED_WAKE",
                mRecorder.ended(100, CrtScreenOffRecorder.Result.CANCELLED_WAKE));
        assertNull(mRecorder.ended(200, CrtScreenOffRecorder.Result.COMPLETED));
        assertTrue(dump().contains("cancellations=1"));
    }

    @Test
    public void fallback_namesReason() {
        assertEquals("end path=OFF outcome=FALLBACK:PREPARE_FAILED setting=1",
                mRecorder.fallback(5, Path.OFF, 1, Decision.PREPARE_FAILED));
        String dump = dump();
        assertTrue(dump, dump.contains("lastOutcome=FALLBACK:PREPARE_FAILED"));
        assertTrue(dump, dump.contains("fallbacks=1"));
    }

    @Test
    public void started_recordsSpeedAndDuration() {
        assertEquals("start path=DOZE effect=CRT setting=1 speed=50 durationMs=1000",
                mRecorder.started(0, Path.DOZE, 1, ScreenOffEffect.CRT, 50, 1000L));
        String dump = dump();
        assertTrue(dump, dump.contains("lastSpeedPercent=50"));
        assertTrue(dump, dump.contains("lastDurationMs=1000"));
    }

    @Test
    public void dump_beforeAnyStart_reportsNoSpeed() {
        String dump = dump();
        assertTrue(dump, dump.contains("lastSpeedPercent=-1"));
        assertTrue(dump, dump.contains("lastDurationMs=-1"));
    }

    @Test
    public void dump_listsEveryKey() {
        String dump = dump();
        for (String key : new String[] {"CrtScreenOffAnimation:", "running=", "lastPath=",
                "lastOutcome=", "lastSpeedPercent=", "lastDurationMs=", "starts=",
                "completions=", "cancellations=", "fallbacks=", "history:"}) {
            assertTrue(key, dump.contains(key));
        }
    }

    @Test
    public void history_keepsLastEightOldestFirst() {
        for (int i = 0; i < 10; i++) {
            mRecorder.fallback(i, Path.OFF, 1, Decision.DISPLAY_NOT_ON);
        }
        String dump = dump();
        assertFalse(dump, dump.contains("    1: "));
        assertTrue(dump, dump.contains("    2: "));
        assertTrue(dump, dump.indexOf("    2: ") < dump.indexOf("    9: "));
    }

    private String dump() {
        StringWriter out = new StringWriter();
        mRecorder.dump(new PrintWriter(out));
        return out.toString();
    }
}
