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

import java.io.PrintWriter;

/**
 * Records CRT screen-off transitions for logcat and {@code dumpsys display}: counters, the last
 * path and outcome, and a bounded history that survives logcat rotation. Holds only enums, the
 * setting integer, the speed percent and duration, uptime, and counters. Called on the
 * DisplayPowerController handler thread.
 */
final class CrtScreenOffRecorder {
    /** How a started CRT transition ended. */
    enum Result { COMPLETED, CANCELLED_WAKE }

    private static final int HISTORY_SIZE = 8;

    private final String[] mHistory = new String[HISTORY_SIZE];
    private int mHistoryNext;
    private int mHistoryCount;
    private boolean mRunning;
    private CrtScreenOffPolicy.Path mLastPath;
    private String mLastOutcome = "NONE";
    private int mLastSpeedPercent = -1;
    private long mLastDurationMs = -1;
    private int mStarts;
    private int mCompletions;
    private int mCancellations;
    private int mFallbacks;

    boolean isRunning() {
        return mRunning;
    }

    /** Whether a CRT transition on {@code path} is running. */
    boolean isRunning(CrtScreenOffPolicy.Path path) {
        return mRunning && mLastPath == path;
    }

    /** Marks a CRT transition as running at its effective speed and returns its log line. */
    String started(long uptimeMillis, CrtScreenOffPolicy.Path path, int setting,
            int speedPercent, long durationMs) {
        mRunning = true;
        mLastPath = path;
        mLastOutcome = "RUNNING";
        mLastSpeedPercent = speedPercent;
        mLastDurationMs = durationMs;
        mStarts++;
        return remember(uptimeMillis, "start path=" + path + " setting=" + setting
                + " speed=" + speedPercent + " durationMs=" + durationMs);
    }

    /** Ends the running transition; returns its log line, or null when none is running. */
    String ended(long uptimeMillis, Result result) {
        if (!mRunning) {
            return null;
        }
        mRunning = false;
        mLastOutcome = result.name();
        if (result == Result.COMPLETED) {
            mCompletions++;
        } else {
            mCancellations++;
        }
        return remember(uptimeMillis, "end path=" + mLastPath + " outcome=" + result);
    }

    /** Records that a CRT-selected transition ran stock, and why; returns its log line. */
    String fallback(long uptimeMillis, CrtScreenOffPolicy.Path path, int setting,
            CrtScreenOffPolicy.Decision reason) {
        mLastPath = path;
        mLastOutcome = "FALLBACK:" + reason;
        mFallbacks++;
        return remember(uptimeMillis,
                "end path=" + path + " outcome=" + mLastOutcome + " setting=" + setting);
    }

    void dump(PrintWriter pw) {
        pw.println("  CrtScreenOffAnimation:");
        pw.println("    running=" + mRunning);
        pw.println("    lastPath=" + mLastPath);
        pw.println("    lastOutcome=" + mLastOutcome);
        pw.println("    lastSpeedPercent=" + mLastSpeedPercent);
        pw.println("    lastDurationMs=" + mLastDurationMs);
        pw.println("    starts=" + mStarts);
        pw.println("    completions=" + mCompletions);
        pw.println("    cancellations=" + mCancellations);
        pw.println("    fallbacks=" + mFallbacks);
        pw.println("    history:");
        final int oldest = (mHistoryNext - mHistoryCount + HISTORY_SIZE) % HISTORY_SIZE;
        for (int i = 0; i < mHistoryCount; i++) {
            pw.println("      " + mHistory[(oldest + i) % HISTORY_SIZE]);
        }
    }

    private String remember(long uptimeMillis, String line) {
        mHistory[mHistoryNext] = uptimeMillis + ": " + line;
        mHistoryNext = (mHistoryNext + 1) % HISTORY_SIZE;
        mHistoryCount = Math.min(mHistoryCount + 1, HISTORY_SIZE);
        return line;
    }
}
