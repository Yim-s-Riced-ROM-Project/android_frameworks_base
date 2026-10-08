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
import org.mockito.kotlin.argumentCaptor

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
    fun draw_usesConfiguredArgb() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val paintCaptor = ArgumentCaptor.forClass(Paint::class.java)
        view.layout(0, 0, 320, 100)
        view.setColor(Color.argb(0x80, 0x12, 0x34, 0x56))
        view.setLevels(floatArrayOf(1f))

        view.draw(canvas)

        verify(canvas)
            .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), paintCaptor.capture())
        assertThat(paintCaptor.value.color).isEqualTo(Color.argb(0x80, 0x12, 0x34, 0x56))
        assertThat(paintCaptor.value.alpha).isEqualTo(0x80)
    }

    @Test
    fun draw_appliesDefaultBoostCurveToLevel() {
        val top = drawnTop(level = 0.05f)

        assertThat(top).isWithin(1e-3f).of(100f * (1f - PulseHeightCurve().heightFor(0.05f)))
        assertThat(top).isLessThan(95f)
    }

    @Test
    fun draw_withZeroBoost_matchesLinearHeight() {
        assertThat(drawnTop(level = 0.5f, boost = 0)).isEqualTo(50f)
        assertThat(drawnTop(level = 0.05f, boost = 0)).isWithin(1e-4f).of(95f)
    }

    @Test
    fun draw_atMaxBoost_keepsFullAndSilentEndpoints() {
        assertThat(drawnTop(level = 1f, boost = 100)).isEqualTo(0f)

        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        view.layout(0, 0, 320, 100)
        view.setBoost(100)
        view.setLevels(floatArrayOf(0f, 0f))
        view.draw(canvas)

        verify(canvas, never()).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any())
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

    @Test
    fun draw_defaultLayout_keepsLegacyBarEdges() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val left = argumentCaptor<Float>()
        val right = argumentCaptor<Float>()
        view.layout(0, 0, 320, 100)
        view.setLevels(floatArrayOf(1f))

        view.draw(canvas)

        verify(canvas).drawRect(left.capture(), anyFloat(), right.capture(), anyFloat(), any())
        assertThat(left.firstValue).isWithin(1e-4f).of(1.5f)
        assertThat(right.firstValue).isWithin(1e-4f).of(8.5f)
    }

    @Test
    fun draw_moreBarsThanBands_drawsOneRectPerBar() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        view.layout(0, 0, 640, 100)
        view.setBarLayout(count = 64, gapPercent = 30)
        view.setLevels(FloatArray(32) { 1f })

        view.draw(canvas)

        verify(canvas, times(64)).drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any())
    }

    @Test
    fun draw_fewerBarsThanBands_keepsBandPeak() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val left = argumentCaptor<Float>()
        view.layout(0, 0, 320, 100)
        view.setBarLayout(count = 16, gapPercent = 0)
        view.setLevels(FloatArray(32).also { it[1] = 1f })

        view.draw(canvas)

        verify(canvas).drawRect(left.capture(), anyFloat(), anyFloat(), anyFloat(), any())
        assertThat(left.firstValue).isEqualTo(0f)
    }

    @Test
    fun setBarLayout_afterSizing_recomputesEdgesAndReportsGap() {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val right = argumentCaptor<Float>()
        view.layout(0, 0, 320, 100)

        view.setBarLayout(count = 16, gapPercent = 0)
        view.setLevels(FloatArray(32).also { it[0] = 1f })
        view.draw(canvas)

        verify(canvas).drawRect(anyFloat(), anyFloat(), right.capture(), anyFloat(), any())
        assertThat(right.firstValue).isWithin(1e-4f).of(20f)
        assertThat(view.effectiveBarGapPx).isEqualTo(0f)

        view.setBarLayout(count = 16, gapPercent = 50)
        assertThat(view.effectiveBarGapPx).isWithin(1e-4f).of(10f)
    }

    private fun drawnTop(level: Float, boost: Int? = null): Float {
        val view = PulseView(mContext)
        val canvas = mock(Canvas::class.java)
        val topCaptor = argumentCaptor<Float>()
        view.layout(0, 0, 320, 100)
        boost?.let(view::setBoost)
        view.setLevels(floatArrayOf(level))

        view.draw(canvas)

        verify(canvas).drawRect(anyFloat(), topCaptor.capture(), anyFloat(), anyFloat(), any())
        return topCaptor.firstValue
    }
}
