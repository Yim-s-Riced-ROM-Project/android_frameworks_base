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

package com.android.systemui.navigationbar.pulse

import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseWindowControllerTest : SysuiTestCase() {
    private val metrics = DisplayMetrics().apply { density = 2f }
    private val resources = mock<Resources>()
    private val displayContext = mock<Context>()
    private val windowManager = mock<WindowManager>()
    private val view = mock<PulseView>()

    private lateinit var underTest: PulseWindowController

    @Before
    fun setUp() {
        whenever(displayContext.resources).thenReturn(resources)
        whenever(resources.displayMetrics).thenReturn(metrics)
        whenever(displayContext.displayId).thenReturn(0)
        whenever(displayContext.getSystemService(WindowManager::class.java))
            .thenReturn(windowManager)
        underTest = PulseWindowController(displayContext, view)
    }

    @Test
    fun show_usesDisplayContextWindowManagerAndTitle() {
        assertThat(underTest.show(48)).isTrue()

        val params = addedParams()
        assertThat(params.title.toString()).isEqualTo("Pulse0")
    }

    @Test
    fun show_addsTrustedNonTouchableBottomOverlay() {
        underTest.show(48)

        val params = addedParams()
        assertThat(params.type).isEqualTo(LayoutParams.TYPE_NAVIGATION_BAR_PANEL)
        assertThat(params.width).isEqualTo(LayoutParams.MATCH_PARENT)
        assertThat(params.height).isEqualTo(96)
        assertThat(params.gravity).isEqualTo(Gravity.BOTTOM)
        assertThat(params.format).isEqualTo(PixelFormat.TRANSLUCENT)
        for (flag in
            listOf(
                LayoutParams.FLAG_NOT_FOCUSABLE,
                LayoutParams.FLAG_NOT_TOUCHABLE,
                LayoutParams.FLAG_NOT_TOUCH_MODAL,
                LayoutParams.FLAG_HARDWARE_ACCELERATED,
            )) {
            assertThat(params.flags and flag).isEqualTo(flag)
        }
        assertThat(params.privateFlags and LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
            .isEqualTo(LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        assertThat(params.accessibilityTitle.toString()).isEmpty()
    }

    @Test
    fun show_twiceAddsWindowOnce() {
        underTest.show(48)

        assertThat(underTest.show(48)).isTrue()

        verify(windowManager, times(1)).addView(any(), any())
    }

    @Test
    fun hide_clearsAndRemovesOnce() {
        underTest.show(48)

        underTest.hide()
        underTest.hide()

        verify(view, times(1)).clear()
        verify(windowManager, times(1)).removeViewImmediate(view)
    }

    @Test
    fun hide_withoutWindowDoesNothing() {
        underTest.hide()

        verify(windowManager, never()).removeViewImmediate(any())
    }

    @Test
    fun updateHeight_clampsAndUpdatesAttachedWindow() {
        underTest.show(48)

        assertThat(underTest.updateHeight(120)).isTrue()

        val captor = argumentCaptor<LayoutParams>()
        verify(windowManager).updateViewLayout(any(), captor.capture())
        assertThat(captor.lastValue.height).isEqualTo(192)
    }

    @Test
    fun updateHeight_whileHiddenDefersUntilShow() {
        underTest.updateHeight(7)
        verify(windowManager, never()).updateViewLayout(any(), any())

        underTest.show(7)

        assertThat(addedParams().height).isEqualTo(16)
    }

    @Test
    fun configurationChange_recomputesHeightFromDisplayDensity() {
        underTest.show(48)
        metrics.density = 3f

        configurationListener().invoke()

        val captor = argumentCaptor<LayoutParams>()
        verify(windowManager).updateViewLayout(any(), captor.capture())
        assertThat(captor.lastValue.height).isEqualTo(144)
    }

    @Test
    fun configurationChange_whileHiddenDoesNothing() {
        configurationListener().invoke()

        verify(windowManager, never()).updateViewLayout(any(), any())
    }

    @Test
    fun addFailure_returnsFalseAndLeavesNoWindow() {
        doThrow(WindowManager.BadTokenException()).whenever(windowManager).addView(any(), any())

        assertThat(underTest.show(48)).isFalse()
        underTest.hide()

        verify(windowManager, never()).removeViewImmediate(any())
    }

    @Test
    fun updateFailure_returnsFalse() {
        underTest.show(48)
        doThrow(IllegalArgumentException()).whenever(windowManager).updateViewLayout(any(), any())

        assertThat(underTest.updateHeight(64)).isFalse()
    }

    @Test
    fun removeFailure_isContainedAndWindowIsForgotten() {
        underTest.show(48)
        doThrow(IllegalArgumentException()).whenever(windowManager).removeViewImmediate(any())

        underTest.hide()
        underTest.hide()

        verify(windowManager, times(1)).removeViewImmediate(view)
        assertThat(underTest.show(48)).isTrue()
        verify(windowManager, times(2)).addView(any(), any())
    }

    @Test
    fun configurationUpdateFailure_removesWindowAndNotifiesListener() {
        var failures = 0
        underTest.setWindowFailureListener { failures++ }
        underTest.show(48)
        doThrow(IllegalArgumentException()).whenever(windowManager).updateViewLayout(any(), any())

        configurationListener().invoke()

        assertThat(failures).isEqualTo(1)
        verify(windowManager).removeViewImmediate(view)
    }

    private fun addedParams(): LayoutParams {
        val captor = argumentCaptor<LayoutParams>()
        verify(windowManager).addView(any(), captor.capture())
        return captor.lastValue
    }

    private fun configurationListener(): () -> Unit {
        val captor = argumentCaptor<() -> Unit>()
        verify(view).configurationListener = captor.capture()
        return captor.lastValue
    }
}
