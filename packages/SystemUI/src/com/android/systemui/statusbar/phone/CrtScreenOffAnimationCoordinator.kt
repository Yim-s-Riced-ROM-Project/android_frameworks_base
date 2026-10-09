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

package com.android.systemui.statusbar.phone

import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.CrtScreenOffAnimationLog
import java.io.PrintWriter
import javax.inject.Inject

/** First stock gate that rejected the unlocked screen-off animation, in evaluation order. */
enum class ScreenOffAnimationBlockedReason {
    NOT_INITIALIZED,
    CRT_OWNED_BY_DISPLAY,
    CANNOT_CONTROL_UNLOCKED_SCREEN_OFF,
    PREVIOUSLY_REJECTED,
    ANIMATIONS_DISABLED,
    SHADE_EXPANDED,
    NOT_SHADE,
    CENTRAL_SURFACES_UNAVAILABLE,
    DEFAULT_DISPLAY_OFF,
}

/**
 * Stock unlocked screen-off eligibility, with the first failing gate when ineligible. [blockedBy]
 * is set exactly when [eligible] is false.
 */
data class ScreenOffAnimationDecision(
    val eligible: Boolean,
    val blockedBy: ScreenOffAnimationBlockedReason? = null,
) {
    init {
        require(eligible == (blockedBy == null)) { "blockedBy must be set exactly when ineligible" }
    }

    companion object {
        val ELIGIBLE = ScreenOffAnimationDecision(eligible = true)

        private val BLOCKED =
            ScreenOffAnimationBlockedReason.entries.map {
                ScreenOffAnimationDecision(eligible = false, blockedBy = it)
            }

        /** Returns a shared instance, so per-frame eligibility checks never allocate. */
        fun blocked(reason: ScreenOffAnimationBlockedReason): ScreenOffAnimationDecision =
            BLOCKED[reason.ordinal]
    }
}

/**
 * Records SystemUI's unlocked screen-off decision for the CRT setting. With CRT selected,
 * DisplayPowerController plays the CRT animation for every default-display screen-off, so
 * [UnlockedScreenOffAnimationController] stands down with
 * [ScreenOffAnimationBlockedReason.CRT_OWNED_BY_DISPLAY] and SystemUI behaves as stock without an
 * animation. Dump and logs hold only booleans, enums, and the setting integer.
 */
@SysUISingleton
class CrtScreenOffAnimationCoordinator
@Inject
constructor(
    private val settingsRepository: ScreenOffAnimationSettingsRepository,
    private val dumpManager: DumpManager,
    @param:CrtScreenOffAnimationLog private val logBuffer: LogBuffer,
) : Dumpable {
    private var dumpableRegistered = false
    private var lastDecision =
        ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.NOT_INITIALIZED)

    /** Registers the dumpable once. */
    fun initialize() {
        if (dumpableRegistered) return
        try {
            dumpManager.registerNormalDumpable(TAG, this)
            dumpableRegistered = true
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Dumpable already registered")
        }
    }

    /**
     * Whether a non-Stock effect is selected (CRT or a glitch), in which case
     * DisplayPowerController owns every screen-off.
     */
    fun isCrtOwnedByDisplay(): Boolean =
        settingsRepository.setting.value.selection != ScreenOffAnimationSelection.STOCK

    /** Records the latest stock eligibility decision; logs only when it changes. */
    fun onStockDecision(decision: ScreenOffAnimationDecision) {
        if (decision == lastDecision) return
        lastDecision = decision
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = decision.eligible
                str1 = decision.blockedBy?.name ?: NONE
            },
            { "stock eligible=$bool1 blockedBy=$str1" },
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("settingValue=${settingsRepository.setting.value.rawValue}")
        pw.println("crtOwnedByDisplay=${isCrtOwnedByDisplay()}")
        pw.println("stockEligible=${lastDecision.eligible}")
        pw.println("blockedBy=${lastDecision.blockedBy?.name ?: NONE}")
    }

    companion object {
        const val TAG = "CrtScreenOffAnimation"
        private const val NONE = "none"
    }
}
