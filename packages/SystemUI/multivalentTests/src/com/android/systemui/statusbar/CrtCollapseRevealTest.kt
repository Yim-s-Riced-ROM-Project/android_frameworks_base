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

package com.android.systemui.statusbar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Geometry, final-black, and override-ownership tests for [CrtCollapseReveal] on a real
 * [LightRevealScrim]. Drawing is checked through a mock [Canvas]; no pixels are read back.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class CrtCollapseRevealTest : SysuiTestCase() {

    private lateinit var scrim: LightRevealScrim
    private var density = 1f

    @Before
    fun setUp() {
        // Ravenwood cannot construct Views; this suite runs under Robolectric and on device.
        assumeFalse(SysuiTestCase.isRavenwoodTest())
        density = context.resources.displayMetrics.density
        scrim = createScrim(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
    }

    @Test
    fun revealOne_hasFullOverscannedApertureAndNoEmission() {
        applyCrt(1f)

        assertAperture(
            left = -0.03f * PORTRAIT_WIDTH,
            top = 0f,
            right = 1.03f * PORTRAIT_WIDTH,
            bottom = PORTRAIT_HEIGHT.toFloat(),
        )
        assertNoEmission()

        scrim.screenOffRevealEffectOverride = CrtCollapseReveal
        val canvas = mock<Canvas>()
        drawScrim(canvas)
        verify(canvas, never()).drawColor(anyInt())
        verify(canvas, never())
            .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any<Paint>())
    }

    @Test
    fun firstHalf_closesVerticallyAndMonotonically() {
        var previousTop = -1f
        var previousBottom = Float.MAX_VALUE
        for (amount in floatArrayOf(1f, 0.875f, 0.75f, 0.625f, 0.5f)) {
            applyCrt(amount)

            assertThat(scrim.crtApertureTop).isAtLeast(previousTop)
            assertThat(scrim.crtApertureBottom).isAtMost(previousBottom)
            assertThat(scrim.crtApertureLeft).isWithin(TOLERANCE).of(-0.03f * PORTRAIT_WIDTH)
            assertThat(scrim.crtApertureRight).isWithin(TOLERANCE).of(1.03f * PORTRAIT_WIDTH)
            assertNoEmission()
            previousTop = scrim.crtApertureTop
            previousBottom = scrim.crtApertureBottom
        }
        assertThat(previousTop).isGreaterThan(0f)
        assertThat(previousBottom).isLessThan(PORTRAIT_HEIGHT.toFloat())
    }

    @Test
    fun phaseBoundary_hasThinFullWidthHorizontalAperture() {
        applyCrt(0.5f)

        val beamHalfHeight = maxOf(1f, density)
        val centerY = PORTRAIT_HEIGHT / 2f
        assertAperture(
            left = -0.03f * PORTRAIT_WIDTH,
            top = centerY - beamHalfHeight,
            right = 1.03f * PORTRAIT_WIDTH,
            bottom = centerY + beamHalfHeight,
        )
        assertThat(scrim.crtBeamHalfHeight).isWithin(TOLERANCE).of(beamHalfHeight)
        assertNoEmission()
    }

    @Test
    fun secondHalf_contractsHorizontallyTowardCenter() {
        val centerX = PORTRAIT_WIDTH / 2f
        var previousWidth = Float.MAX_VALUE
        for (amount in floatArrayOf(0.5f, 0.375f, 0.25f, 0.125f, 0f)) {
            applyCrt(amount)

            val apertureWidth = scrim.crtApertureRight - scrim.crtApertureLeft
            assertThat(apertureWidth).isAtMost(previousWidth)
            assertThat((scrim.crtApertureLeft + scrim.crtApertureRight) / 2f)
                .isWithin(TOLERANCE)
                .of(centerX)
            previousWidth = apertureWidth
        }
        assertThat(scrim.crtApertureLeft).isWithin(TOLERANCE).of(centerX)
        assertThat(scrim.crtApertureRight).isWithin(TOLERANCE).of(centerX)
    }

    @Test
    fun allEmissionAlphasStayInBounds() {
        val unit = Range.closed(0f, 1f)
        for (step in 0..100) {
            applyCrt(step / 100f)

            assertThat(scrim.crtCoreAlpha).isIn(unit)
            assertThat(scrim.crtTrailAlpha).isIn(unit)
            assertThat(scrim.crtRedFringeAlpha).isIn(unit)
            assertThat(scrim.crtBlueFringeAlpha).isIn(unit)
            assertThat(scrim.crtTrailAlpha).isAtMost(scrim.crtCoreAlpha)
            assertThat(scrim.crtRedFringeAlpha).isAtMost(scrim.crtCoreAlpha)
            assertThat(scrim.crtBlueFringeAlpha).isAtMost(scrim.crtCoreAlpha)
        }

        applyCrt(0.25f)
        assertThat(scrim.crtCoreAlpha).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun revealZero_hasNoEmissionAndDrawsExactOpaqueBlack() {
        scrim.screenOffRevealEffectOverride = CrtCollapseReveal
        scrim.revealAmount = 0f

        assertNoEmission()
        val canvas = mock<Canvas>()
        drawScrim(canvas)
        verify(canvas).drawColor(Color.BLACK)
        verify(canvas, never())
            .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any<Paint>())
    }

    @Test
    fun beamPhase_drawsOnlyMaskAndBeamRects() {
        scrim.screenOffRevealEffectOverride = CrtCollapseReveal
        scrim.revealAmount = 0.25f

        val canvas = mock<Canvas>()
        drawScrim(canvas)

        // Top, bottom, left, right masks; then trail, red fringe, blue fringe, white core.
        verify(canvas, times(8))
            .drawRect(anyFloat(), anyFloat(), anyFloat(), anyFloat(), any<Paint>())
        verify(canvas, never()).drawColor(anyInt())
    }

    @Test
    fun geometry_usesCurrentPortraitDimensions() {
        applyCrt(1f)

        assertThat(scrim.crtApertureRight).isWithin(TOLERANCE).of(1.03f * PORTRAIT_WIDTH)
        assertThat(scrim.crtApertureBottom).isWithin(TOLERANCE).of(PORTRAIT_HEIGHT.toFloat())
        applyCrt(0.5f)
        assertThat((scrim.crtApertureTop + scrim.crtApertureBottom) / 2f)
            .isWithin(TOLERANCE)
            .of(PORTRAIT_HEIGHT / 2f)
    }

    @Test
    fun geometry_usesCurrentLandscapeDimensions() {
        scrim = createScrim(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)

        applyCrt(1f)
        assertAperture(
            left = -0.03f * LANDSCAPE_WIDTH,
            top = 0f,
            right = 1.03f * LANDSCAPE_WIDTH,
            bottom = LANDSCAPE_HEIGHT.toFloat(),
        )
        applyCrt(0.5f)
        assertThat((scrim.crtApertureTop + scrim.crtApertureBottom) / 2f)
            .isWithin(TOLERANCE)
            .of(LANDSCAPE_HEIGHT / 2f)
    }

    @Test
    fun calculation_doesNotRetainPriorScrimDimensions() {
        applyCrt(0.25f)
        scrim.layout(0, 0, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
        applyCrt(0.25f)
        val rotated = snapshot(scrim)

        val fresh = createScrim(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
        CrtCollapseReveal.setRevealAmountOnScrim(0.25f, fresh)

        assertThat(rotated).isEqualTo(snapshot(fresh))
    }

    @Test
    fun screenOffOverride_becomesActiveWithoutReplacingBase() {
        val base = RecordingRevealEffect()
        val override = RecordingRevealEffect()
        scrim.revealEffect = base

        scrim.screenOffRevealEffectOverride = override
        scrim.revealAmount = 0.4f

        assertThat(scrim.revealEffect).isSameInstanceAs(base)
        assertThat(scrim.activeRevealEffect).isSameInstanceAs(override)
        assertThat(override.lastRevealAmount).isEqualTo(0.4f)
    }

    @Test
    fun baseEffectUpdate_duringOverrideDoesNotReplaceActiveEffect() {
        val override = RecordingRevealEffect()
        val latestBase = RecordingRevealEffect()
        scrim.screenOffRevealEffectOverride = override

        scrim.revealEffect = latestBase
        scrim.revealAmount = 0.3f

        assertThat(scrim.activeRevealEffect).isSameInstanceAs(override)
        assertThat(override.lastRevealAmount).isEqualTo(0.3f)
        assertThat(latestBase.lastRevealAmount).isNull()
    }

    @Test
    fun clearingOverride_appliesLatestBaseEffectAtCurrentAmount() {
        val override = RecordingRevealEffect()
        val latestBase = RecordingRevealEffect()
        scrim.screenOffRevealEffectOverride = override
        scrim.revealEffect = latestBase
        scrim.revealAmount = 0.25f

        scrim.screenOffRevealEffectOverride = null

        assertThat(scrim.activeRevealEffect).isSameInstanceAs(latestBase)
        assertThat(latestBase.lastRevealAmount).isEqualTo(0.25f)
    }

    @Test
    fun switchingAwayFromCrt_resetsAllCrtPrimitiveState() {
        scrim.screenOffRevealEffectOverride = CrtCollapseReveal
        scrim.revealAmount = 0.25f
        assertThat(scrim.crtCoreAlpha).isGreaterThan(0f)

        scrim.screenOffRevealEffectOverride = null

        assertThat(snapshot(scrim)).isEqualTo(List(11) { 0f })
    }

    @Test
    fun clearingOverride_restoresBaseInterpolatedRevealAmount() {
        scrim.interpolatedRevealAmount = 1f
        scrim.screenOffRevealEffectOverride = CrtCollapseReveal
        scrim.revealAmount = 0.05f
        assertThat(scrim.isScrimAlmostOccludes).isTrue()

        scrim.screenOffRevealEffectOverride = null

        assertThat(scrim.interpolatedRevealAmount).isEqualTo(1f)
    }

    private fun createScrim(width: Int, height: Int): LightRevealScrim =
        LightRevealScrim(context, null, width, height).apply { layout(0, 0, width, height) }

    private fun applyCrt(amount: Float) = CrtCollapseReveal.setRevealAmountOnScrim(amount, scrim)

    private fun drawScrim(canvas: Canvas) {
        LightRevealScrim::class
            .java
            .getDeclaredMethod("onDraw", Canvas::class.java)
            .apply { isAccessible = true }
            .invoke(scrim, canvas)
    }

    private fun assertAperture(left: Float, top: Float, right: Float, bottom: Float) {
        assertThat(scrim.crtApertureLeft).isWithin(TOLERANCE).of(left)
        assertThat(scrim.crtApertureTop).isWithin(TOLERANCE).of(top)
        assertThat(scrim.crtApertureRight).isWithin(TOLERANCE).of(right)
        assertThat(scrim.crtApertureBottom).isWithin(TOLERANCE).of(bottom)
    }

    private fun assertNoEmission() {
        assertThat(scrim.crtCoreAlpha).isEqualTo(0f)
        assertThat(scrim.crtTrailAlpha).isEqualTo(0f)
        assertThat(scrim.crtRedFringeAlpha).isEqualTo(0f)
        assertThat(scrim.crtBlueFringeAlpha).isEqualTo(0f)
    }

    private fun snapshot(target: LightRevealScrim): List<Float> =
        with(target) {
            listOf(
                crtApertureLeft,
                crtApertureTop,
                crtApertureRight,
                crtApertureBottom,
                crtBeamHalfHeight,
                crtGlowHalfHeight,
                crtFringeOffset,
                crtCoreAlpha,
                crtTrailAlpha,
                crtRedFringeAlpha,
                crtBlueFringeAlpha,
            )
        }

    private class RecordingRevealEffect : LightRevealEffect {
        var lastRevealAmount: Float? = null

        override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {
            lastRevealAmount = amount
        }
    }

    private companion object {
        const val PORTRAIT_WIDTH = 1080
        const val PORTRAIT_HEIGHT = 2400
        const val LANDSCAPE_WIDTH = 2400
        const val LANDSCAPE_HEIGHT = 1080
        const val TOLERANCE = 0.001f
    }
}
