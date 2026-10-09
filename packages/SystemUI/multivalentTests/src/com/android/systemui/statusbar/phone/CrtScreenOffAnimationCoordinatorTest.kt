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
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Coordinator ownership, dump, and log tests. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class CrtScreenOffAnimationCoordinatorTest : SysuiTestCase() {
    private val settings = MutableStateFlow(STOCK_SETTING)
    private val settingsRepository = mock<ScreenOffAnimationSettingsRepository>()
    private val dumpManager = mock<DumpManager>()
    private val logBuffer = mock<LogBuffer>()
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
        underTest.initialize()
        underTest.initialize()

        verify(dumpManager, times(1)).registerNormalDumpable(TAG, underTest)
    }

    @Test
    fun isCrtOwnedByDisplay_followsSelection() {
        assertThat(underTest.isCrtOwnedByDisplay()).isFalse()

        settings.value = CRT_SETTING
        assertThat(underTest.isCrtOwnedByDisplay()).isTrue()

        settings.value = STOCK_SETTING
        assertThat(underTest.isCrtOwnedByDisplay()).isFalse()
    }

    @Test
    fun isCrtOwnedByDisplay_trueForEveryGlitchEffect() {
        for (selection in
            listOf(
                ScreenOffAnimationSelection.TEAR,
                ScreenOffAnimationSelection.CORRUPT,
                ScreenOffAnimationSelection.SIGNAL_LOSS,
            )) {
            settings.value = ScreenOffAnimationSetting(selection.ordinal, selection)
            assertThat(underTest.isCrtOwnedByDisplay()).isTrue()
        }
        settings.value = STOCK_SETTING
        assertThat(underTest.isCrtOwnedByDisplay()).isFalse()
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
    fun dump_reportsCrtOwnershipAndSettingValue() {
        settings.value = CRT_SETTING
        underTest.onStockDecision(
            ScreenOffAnimationDecision.blocked(
                ScreenOffAnimationBlockedReason.CRT_OWNED_BY_DISPLAY
            )
        )

        val dump = dump()
        assertThat(dump).contains("settingValue=1\n")
        assertThat(dump).contains("crtOwnedByDisplay=true\n")
        assertThat(dump).contains("blockedBy=CRT_OWNED_BY_DISPLAY\n")
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
    fun logBuffer_recordsOnlyDecisionChanges() {
        val blocked = ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.NOT_SHADE)

        repeat(5) { underTest.onStockDecision(blocked) }
        repeat(5) { underTest.onStockDecision(ScreenOffAnimationDecision.ELIGIBLE) }

        assertThat(loggedMessages)
            .containsExactly(
                "stock eligible=false blockedBy=NOT_SHADE",
                "stock eligible=true blockedBy=none",
            )
            .inOrder()
    }

    @Test
    fun dumpAndLogs_excludeSensitiveOrIdentifyingData() {
        settings.value = CRT_SETTING
        underTest.onStockDecision(
            ScreenOffAnimationDecision.blocked(ScreenOffAnimationBlockedReason.SHADE_EXPANDED)
        )

        val output = dump() + loggedMessages.joinToString("\n")

        for (term in FORBIDDEN_TERMS) {
            assertWithMessage(term).that(output).doesNotContain(term)
        }
        for (line in dump().lines().filter { it.isNotEmpty() }) {
            assertWithMessage(line).that(line).matches(DUMP_LINE_ALLOW_LIST)
        }
    }

    private fun dump(): String =
        StringWriter().also { underTest.dump(PrintWriter(it), emptyArray()) }.toString()

    private companion object {
        val STOCK_SETTING = ScreenOffAnimationSetting(0, ScreenOffAnimationSelection.STOCK)
        val CRT_SETTING = ScreenOffAnimationSetting(1, ScreenOffAnimationSelection.CRT)
        val DUMP_FIELDS = listOf("settingValue", "crtOwnedByDisplay", "stockEligible", "blockedBy")
        val FORBIDDEN_TERMS = listOf("package=", "uid=", "content=", "pixel=", "biometric=")
        const val DUMP_LINE_ALLOW_LIST =
            "^(settingValue|crtOwnedByDisplay|stockEligible|blockedBy)" +
                "=(true|false|-?\\d+|[A-Z_]+|none)$"
    }
}
