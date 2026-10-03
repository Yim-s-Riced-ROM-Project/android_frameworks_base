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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseWindowControllerTest : SysuiTestCase() {
    @Mock private lateinit var context: Context
    @Mock private lateinit var resources: Resources
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var view: PulseView

    private lateinit var underTest: PulseWindowController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val metrics = DisplayMetrics().apply { density = 2f }
        whenever(context.resources).thenReturn(resources)
        whenever(resources.displayMetrics).thenReturn(metrics)
        whenever(context.displayId).thenReturn(0)
        underTest = PulseWindowController(context, windowManager, view)
    }

    @Test
    fun show_addsTrustedBottomWindowWithConfiguredHeight() {
        val paramsCaptor = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)

        assertThat(underTest.show(48)).isTrue()

        verify(windowManager).addView(view, paramsCaptor.capture())
        val params = paramsCaptor.value
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL)
        assertThat(params.width).isEqualTo(WindowManager.LayoutParams.MATCH_PARENT)
        assertThat(params.height).isEqualTo(96)
        assertThat(params.gravity).isEqualTo(Gravity.BOTTOM)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun show_twiceAddsWindowOnce() {
        underTest.show(48)

        underTest.show(48)

        verify(windowManager, times(1)).addView(any(), any())
    }

    @Test
    fun updateHeight_clampsAndUpdatesAttachedWindow() {
        val paramsCaptor = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)
        underTest.show(48)

        underTest.updateHeight(120)

        verify(windowManager).updateViewLayout(view, paramsCaptor.capture())
        assertThat(paramsCaptor.value.height).isEqualTo(192)
    }

    @Test
    fun hide_clearsAndRemovesOnce() {
        underTest.show(48)

        underTest.hide()
        underTest.hide()

        verify(view).clear()
        verify(windowManager, times(1)).removeViewImmediate(view)
    }

    @Test
    fun updateHeight_whileHiddenDefersUntilShow() {
        underTest.updateHeight(7)
        verify(windowManager, never()).updateViewLayout(any(), any())
        val paramsCaptor = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)

        underTest.show(7)

        verify(windowManager).addView(view, paramsCaptor.capture())
        assertThat(paramsCaptor.value.height).isEqualTo(16)
    }
}
