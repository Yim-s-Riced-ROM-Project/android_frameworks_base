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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogMessageImpl
import com.android.systemui.log.core.LogMessage
import com.android.systemui.statusbar.CrtCollapseReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.phone.CrtScreenOffAnimationCoordinator.Companion.TAG
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Coordinator lifecycle, dump, and log tests. The scrim is a mock: override ownership semantics on
 * a real [LightRevealScrim] are covered by CrtCollapseRevealTest.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class CrtScreenOffAnimationCoordinatorTest : SysuiTestCase() {
    private val settings = MutableStateFlow(STOCK_SETTING)
    private val settingsRepository = mock<ScreenOffAnimationSettingsRepository>()
    private val dumpManager = mock<DumpManager>()
    private val logBuffer = mock<LogBuffer>()
    private val scrim = mock<LightRevealScrim>()
    private val loggedMessages = mutableListOf<String>()

    private lateinit var underTest: CrtScreenOffAnimationCoordinator

    @Before
    fun setUp() {
        whenever(settingsRepository.setting).thenReturn(settings)
        // LogBuffer.dump needs android.icu: record printed messages instead.
        whenever(logBuffer.obtain(any(), any(), any(), anyOrNull())).thenAnswer {
            LogMessageImpl.create().apply {
                reset(
                    it.getArgument(0),
                    it.getArgument(1),
                    0L,
                    it.getArgument(2),
                    it.getArgument(3),
                )
            }
        }
        doAnswer {
                val message = it.getArgument<LogMessage>(0)
                loggedMessages += message.messagePrinter(message)
            }
            .whenever(logBuffer)
            .commit(any())
        underTest = CrtScreenOffAnimationCoordinator(settingsRepository, dumpManager, logBuffer)
    }

    @Test
    fun initialize_registersNormalDumpableOnce() {
        underTest.initialize(scrim)
        underTest.initialize(scrim)

        verify(dumpManager, times(1)).registerNormalDumpable(TAG, underTest)
    }

    @Test
    fun crtStart_installsOverride() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)

        underTest.start(normalMode = true)

        verify(scrim).screenOffRevealEffectOverride = CrtCollapseReveal
        val dump = dump()
        assertThat(dump).contains("selectedEffect=CRT\n")
        assertThat(dump).contains("overrideActive=true\n")
        assertThat(dump).contains("animationState=RUNNING\n")
    }

    @Test
    fun stockStart_doesNotInstallOverride() {
        underTest.initialize(scrim)

        underTest.start(normalMode = true)

        verify(scrim, never()).screenOffRevealEffectOverride = CrtCollapseReveal
        assertThat(dump()).contains("selectedEffect=STOCK\n")
        assertThat(dump()).contains("overrideActive=false\n")
    }

    @Test
    fun minMode_forcesStock() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)

        underTest.start(normalMode = false)

        verify(scrim, never()).screenOffRevealEffectOverride = CrtCollapseReveal
        val dump = dump()
        assertThat(dump).contains("minMode=true\n")
        assertThat(dump).contains("selectedEffect=STOCK\n")
    }

    @Test
    fun setting_isSampledOncePerAcceptedTransition() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)

        underTest.start(normalMode = true)
        underTest.start(normalMode = true)
        verify(settingsRepository, times(1)).setting

        underTest.complete()
        underTest.start(normalMode = true)
        verify(settingsRepository, times(2)).setting
    }

    @Test
    fun settingChange_duringAnimation_appliesOnlyToNextTransition() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)
        underTest.start(normalMode = true)

        settings.value = STOCK_SETTING
        assertThat(dump()).contains("overrideActive=true\n")
        verify(scrim, never()).screenOffRevealEffectOverride = null

        underTest.complete()
        underTest.start(normalMode = true)

        verify(scrim, times(1)).screenOffRevealEffectOverride = CrtCollapseReveal
        assertThat(dump()).contains("selectedEffect=STOCK\n")
    }

    @Test
    fun complete_clearsOverride() {
        startCrt()

        underTest.complete()

        verify(scrim).screenOffRevealEffectOverride = null
        val dump = dump()
        assertThat(dump).contains("overrideActive=false\n")
        assertThat(dump).contains("animationState=IDLE\n")
        assertThat(dump).contains("lastEndReason=COMPLETED\n")
    }

    @Test
    fun cancel_clearsOverride() {
        startCrt()

        underTest.cancel(CrtCancellationReason.ANIMATOR_CANCELLED)

        verify(scrim).screenOffRevealEffectOverride = null
        val dump = dump()
        assertThat(dump).contains("overrideActive=false\n")
        assertThat(dump).contains("lastEndReason=ANIMATOR_CANCELLED\n")
    }

    @Test
    fun repeatedCompleteAndCancel_areIdempotent() {
        startCrt()

        underTest.complete()
        underTest.complete()
        underTest.cancel(CrtCancellationReason.WAKE)
        underTest.cancel(CrtCancellationReason.ANIMATOR_CANCELLED)

        verify(scrim, times(1)).screenOffRevealEffectOverride = null
        val dump = dump()
        assertThat(dump).contains("completions=1\n")
        assertThat(dump).contains("cancellations=0\n")
        assertThat(dump).contains("lastEndReason=COMPLETED\n")
    }

    @Test
    fun crtLifecycle_neverWritesBaseRevealEffect() {
        startCrt()

        underTest.complete()

        // The coordinator only owns the temporary slot, so base effect updates are left alone.
        verify(scrim, never()).revealEffect = any<LightRevealEffect>()
    }

    @Test
    fun wakeCancellation_exposesLatestBaseEffect() {
        startCrt()

        underTest.cancel(CrtCancellationReason.WAKE)

        verify(scrim).screenOffRevealEffectOverride = null
        verify(scrim, never()).revealEffect = any<LightRevealEffect>()
        assertThat(dump()).contains("lastEndReason=WAKE\n")
    }

    @Test
    fun installFailure_clearsOverrideAndDoesNotEscape() {
        settings.value = CRT_SETTING
        doThrow(IllegalStateException("secret detail"))
            .whenever(scrim)
            .screenOffRevealEffectOverride = CrtCollapseReveal
        underTest.initialize(scrim)

        underTest.start(normalMode = true)

        verify(scrim).screenOffRevealEffectOverride = null
        val dump = dump()
        assertThat(dump).contains("overrideActive=false\n")
        assertThat(dump).contains("selectedEffect=STOCK\n")
        val log = loggedMessages.joinToString("\n")
        assertThat(log).contains("failed stage=install exception=IllegalStateException")
        assertThat(log).doesNotContain("secret detail")

        underTest.complete()
        assertThat(dump()).contains("completions=1\n")
    }

    @Test
    fun dump_containsEveryDocumentedField() {
        val dump = dump()

        for (field in DUMP_FIELDS) {
            assertWithMessage(field).that(dump).containsMatch("(?m)^$field=")
        }
        assertThat(dump.lines().filter { it.isNotEmpty() }).hasSize(DUMP_FIELDS.size)
    }

    @Test
    fun dump_reportsKnownFalseBlockedReason() {
        underTest.onStockDecision(
            ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.ANIMATIONS_DISABLED)
        )

        val dump = dump()
        assertThat(dump).contains("stockEligible=false\n")
        assertThat(dump).contains("blockedBy=ANIMATIONS_DISABLED\n")

        underTest.onStockDecision(ScreenOffAnimationDecision.ELIGIBLE)
        assertThat(dump()).contains("stockEligible=true\n")
        assertThat(dump()).contains("blockedBy=none\n")
    }

    @Test
    fun counters_trackStartCompletionAndCancellation() {
        underTest.initialize(scrim)

        underTest.start(normalMode = true)
        underTest.complete()
        underTest.start(normalMode = true)
        underTest.cancel(CrtCancellationReason.WAKE)
        underTest.start(normalMode = false)

        val dump = dump()
        assertThat(dump).contains("starts=3\n")
        assertThat(dump).contains("completions=1\n")
        assertThat(dump).contains("cancellations=1\n")
        assertThat(dump).contains("animationState=RUNNING\n")
    }

    @Test
    fun logBuffer_recordsOnlyTransitions() {
        val blocked = ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.NOT_SHADE)
        settings.value = CRT_SETTING
        underTest.initialize(scrim)

        repeat(5) { underTest.onStockDecision(blocked) }
        repeat(5) { underTest.onStockDecision(ScreenOffAnimationDecision.ELIGIBLE) }
        underTest.start(normalMode = true)
        underTest.complete()
        underTest.complete()

        assertThat(loggedMessages)
            .containsExactly(
                "stock eligible=false blockedBy=NOT_SHADE",
                "stock eligible=true blockedBy=none",
                "transition started setting=1 normalMode=true selected=CRT",
                "override installed",
                "transition ended=COMPLETED",
            )
            .inOrder()
    }

    @Test
    fun dumpAndLogs_excludeSensitiveOrIdentifyingData() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)
        underTest.onStockDecision(
            ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.SHADE_EXPANDED)
        )
        underTest.start(normalMode = true)
        underTest.cancel(CrtCancellationReason.WAKE)

        val output = dump() + loggedMessages.joinToString("\n")

        for (term in FORBIDDEN_TERMS) {
            assertWithMessage(term).that(output).doesNotContain(term)
        }
        for (line in dump().lines().filter { it.isNotEmpty() }) {
            assertWithMessage(line).that(line).matches(DUMP_LINE_ALLOW_LIST)
        }
    }

    @Test
    fun initialize_withNewScrimWhileOverrideActive_clearsOldScrim() {
        startCrt()
        val newScrim = mock<LightRevealScrim>()

        underTest.initialize(newScrim)

        verify(scrim).screenOffRevealEffectOverride = null
        assertThat(dump()).contains("overrideActive=false\n")
        underTest.complete()
        verify(newScrim, never()).screenOffRevealEffectOverride = null
        verify(dumpManager, times(1)).registerNormalDumpable(TAG, underTest)
    }

    @Test
    fun sampleFailure_fallsBackToStockWithoutEscaping() {
        underTest.initialize(scrim)
        doThrow(IllegalStateException("secret detail")).whenever(settingsRepository).setting

        underTest.start(normalMode = true)

        verify(scrim, never()).screenOffRevealEffectOverride = CrtCollapseReveal
        val log = loggedMessages.joinToString("\n")
        assertThat(log).contains("failed stage=sample exception=IllegalStateException")
        assertThat(log).doesNotContain("secret detail")

        doReturn(settings).whenever(settingsRepository).setting
        val dump = dump()
        assertThat(dump).contains("selectedEffect=STOCK\n")
        assertThat(dump).contains("overrideActive=false\n")
        assertThat(dump).contains("animationState=RUNNING\n")
        assertThat(dump).contains("starts=1\n")
    }

    @Test
    fun clearFailure_stillCountsCompletionAndEndsIdle() {
        startCrt()
        doThrow(IllegalStateException("secret detail"))
            .whenever(scrim)
            .screenOffRevealEffectOverride = null

        underTest.complete()

        val dump = dump()
        assertThat(dump).contains("completions=1\n")
        assertThat(dump).contains("animationState=IDLE\n")
        assertThat(dump).contains("overrideActive=false\n")
        assertThat(dump).contains("lastEndReason=COMPLETED\n")
        assertThat(loggedMessages.joinToString("\n"))
            .contains("failed stage=clear exception=IllegalStateException")
    }

    private fun startCrt() {
        settings.value = CRT_SETTING
        underTest.initialize(scrim)
        underTest.start(normalMode = true)
        verify(scrim).screenOffRevealEffectOverride = CrtCollapseReveal
    }

    private fun dump(): String =
        StringWriter().also { underTest.dump(PrintWriter(it), emptyArray()) }.toString()

    private companion object {
        val STOCK_SETTING = ScreenOffAnimationSetting(0, ScreenOffAnimationSelection.STOCK)
        val CRT_SETTING = ScreenOffAnimationSetting(1, ScreenOffAnimationSelection.CRT)
        val DUMP_FIELDS =
            listOf(
                "settingValue",
                "selectedEffect",
                "stockEligible",
                "blockedBy",
                "minMode",
                "overrideActive",
                "animationState",
                "lastEndReason",
                "starts",
                "completions",
                "cancellations",
            )
        val FORBIDDEN_TERMS = listOf("package=", "uid=", "content=", "pixel=", "biometric=")
        const val DUMP_LINE_ALLOW_LIST =
            "^(settingValue|selectedEffect|stockEligible|blockedBy|minMode|overrideActive|" +
                "animationState|lastEndReason|starts|completions|cancellations)" +
                "=(true|false|-?\\d+|[A-Z_]+|none)$"
    }
}
