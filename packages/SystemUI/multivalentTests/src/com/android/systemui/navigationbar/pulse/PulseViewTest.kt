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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulseViewTest : SysuiTestCase() {
    @Test
    fun draw_rendersOneBottomAnchoredRectPerNonzeroLevel() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        view.layout(0, 0, 320, 100)
        view.setLevels(floatArrayOf(1f, 0.5f))

        view.draw(canvas)

        verify(canvas, times(2)).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any())
    }

    @Test
    fun draw_usesConfiguredRgbWithFixedAlpha() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val paintCaptor = ArgumentCaptor.forClass(Paint::class.java)
        view.layout(0, 0, 320, 100)
        view.setColorRgb(0x123456)
        view.setLevels(floatArrayOf(1f))

        view.draw(canvas)

        verify(canvas)
            .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), paintCaptor.capture())
        assertThat(paintCaptor.value.color and 0xFFFFFF)
            .isEqualTo(Color.rgb(0x12, 0x34, 0x56) and 0xFFFFFF)
        assertThat(paintCaptor.value.alpha).isEqualTo(217)
    }

    @Test
    fun setLevels_copiesCallerBuffer() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val levels = floatArrayOf(1f)
        view.layout(0, 0, 320, 100)
        view.setLevels(levels)
        levels[0] = 0f

        view.draw(canvas)

        verify(canvas).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any())
    }

    @Test
    fun clear_removesRenderedBars() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        view.layout(0, 0, 320, 100)
        view.setLevels(floatArrayOf(1f))
        view.draw(canvas)
        clearInvocations(canvas)

        view.clear()
        view.draw(canvas)

        verify(canvas, never()).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any())
    }
}
