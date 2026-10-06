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
import com.android.systemui.statusbar.CrtCollapseReveal
import com.android.systemui.statusbar.LightRevealScrim
import java.io.PrintWriter
import javax.inject.Inject

/** First stock gate that rejected the unlocked screen-off animation, in evaluation order. */
enum class ScreenOffAnimationBlockedReason {
    NOT_INITIALIZED,
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

/** Why an accepted screen-off transition stopped before completing. */
enum class CrtCancellationReason {
    WAKE,
    ANIMATOR_CANCELLED,
}

/**
 * Owns the CRT screen-off selection and the temporary [LightRevealScrim] override. The stock
 * [UnlockedScreenOffAnimationController] keeps eligibility, timing, AOD, and wake handling, and
 * calls [start], [complete], and [cancel] at its accepted lifecycle boundaries.
 *
 * The setting is sampled once per accepted transition. Every completion, cancellation, and failure
 * clears the override; repeated calls are no-ops. Unexpected [RuntimeException]s fall back to Stock
 * and never escape onto the animation or wake path. Dump and logs hold only booleans, enums, stage
 * names, exception class names, and counters. All calls except [dump] happen on the main thread.
 */
@SysUISingleton
class CrtScreenOffAnimationCoordinator
@Inject
constructor(
    private val settingsRepository: ScreenOffAnimationSettingsRepository,
    private val dumpManager: DumpManager,
    @param:CrtScreenOffAnimationLog private val logBuffer: LogBuffer,
) : Dumpable {
    private enum class SelectedEffect {
        STOCK,
        CRT,
    }

    private enum class AnimationState {
        IDLE,
        RUNNING,
    }

    private enum class EndReason {
        NONE,
        COMPLETED,
        WAKE,
        ANIMATOR_CANCELLED,
    }

    private var scrim: LightRevealScrim? = null
    private var dumpableRegistered = false
    private var lastDecision =
        ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.NOT_INITIALIZED)
    private var selectedEffect = SelectedEffect.STOCK
    private var minMode = false
    private var overrideActive = false
    private var animationState = AnimationState.IDLE
    private var lastEndReason = EndReason.NONE
    private var starts = 0
    private var completions = 0
    private var cancellations = 0

    /** Stores the runtime scrim and registers the dumpable once. */
    fun initialize(scrim: LightRevealScrim) {
        if (this.scrim !== scrim && overrideActive) {
            runGuarded(STAGE_CLEAR) { clearOverride() }
        }
        this.scrim = scrim
        if (dumpableRegistered) return
        try {
            dumpManager.registerNormalDumpable(TAG, this)
            dumpableRegistered = true
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Dumpable already registered")
        }
    }

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

    /**
     * Starts an accepted screen-off transition: samples the setting once and installs the CRT
     * override synchronously when selected. [normalMode] is false in min mode, which forces Stock.
     */
    fun start(normalMode: Boolean) {
        if (animationState == AnimationState.RUNNING) return
        animationState = AnimationState.RUNNING
        starts++
        minMode = !normalMode
        selectedEffect = SelectedEffect.STOCK
        val effect = runGuarded(STAGE_SAMPLE) { sampleSelection(normalMode) } ?: return
        if (effect == SelectedEffect.CRT) {
            runGuarded(STAGE_INSTALL) { installOverride() }
        }
    }

    /** Completes the running transition at black and clears the override. */
    fun complete() = endTransition(EndReason.COMPLETED)

    /** Cancels the running transition and clears the override immediately. */
    fun cancel(reason: CrtCancellationReason) =
        endTransition(
            when (reason) {
                CrtCancellationReason.WAKE -> EndReason.WAKE
                CrtCancellationReason.ANIMATOR_CANCELLED -> EndReason.ANIMATOR_CANCELLED
            }
        )

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("settingValue=${settingsRepository.setting.value.rawValue}")
        pw.println("selectedEffect=$selectedEffect")
        pw.println("stockEligible=${lastDecision.eligible}")
        pw.println("blockedBy=${lastDecision.blockedBy?.name ?: NONE}")
        pw.println("minMode=$minMode")
        pw.println("overrideActive=$overrideActive")
        pw.println("animationState=$animationState")
        pw.println("lastEndReason=$lastEndReason")
        pw.println("starts=$starts")
        pw.println("completions=$completions")
        pw.println("cancellations=$cancellations")
    }

    private fun sampleSelection(normalMode: Boolean): SelectedEffect {
        val sampled = settingsRepository.setting.value
        val effect =
            if (normalMode && sampled.selection == ScreenOffAnimationSelection.CRT) {
                SelectedEffect.CRT
            } else {
                SelectedEffect.STOCK
            }
        selectedEffect = effect
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = sampled.rawValue
                bool1 = normalMode
                str1 = effect.name
            },
            { "transition started setting=$int1 normalMode=$bool1 selected=$str1" },
        )
        return effect
    }

    private fun installOverride() {
        val target = scrim
        if (target == null) {
            selectedEffect = SelectedEffect.STOCK
            logBuffer.log(TAG, LogLevel.DEBUG, {}, { "override skipped, not initialized" })
            return
        }
        overrideActive = true
        target.screenOffRevealEffectOverride = CrtCollapseReveal
        logBuffer.log(TAG, LogLevel.DEBUG, {}, { "override installed" })
    }

    private fun endTransition(reason: EndReason) {
        if (animationState != AnimationState.RUNNING) return
        animationState = AnimationState.IDLE
        lastEndReason = reason
        if (reason == EndReason.COMPLETED) completions++ else cancellations++
        if (overrideActive) {
            runGuarded(STAGE_CLEAR) { clearOverride() }
        }
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = reason.name }, { "transition ended=$str1" })
    }

    private fun clearOverride() {
        overrideActive = false
        scrim?.screenOffRevealEffectOverride = null
    }

    /** Runs [block]; on failure clears the override, falls back to Stock, and reports. */
    private inline fun <T> runGuarded(stage: String, block: () -> T): T? =
        try {
            block()
        } catch (e: RuntimeException) {
            onFailure(stage, e)
            null
        }

    private fun onFailure(stage: String, throwable: RuntimeException) {
        selectedEffect = SelectedEffect.STOCK
        clearOverrideAfterFailure()
        val exception = throwable.javaClass.simpleName
        logBuffer.log(
            TAG,
            LogLevel.WARNING,
            {
                str1 = stage
                str2 = exception
            },
            { "failed stage=$str1 exception=$str2" },
        )
        Log.w(TAG, "CRT failure stage=$stage exception=$exception")
    }

    private fun clearOverrideAfterFailure() {
        overrideActive = false
        try {
            scrim?.screenOffRevealEffectOverride = null
        } catch (e: RuntimeException) {
            Log.w(TAG, "CRT cleanup failed exception=${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val TAG = "CrtScreenOffAnimation"
        private const val NONE = "none"
        private const val STAGE_SAMPLE = "sample"
        private const val STAGE_INSTALL = "install"
        private const val STAGE_CLEAR = "clear"
    }
}
