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
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import javax.inject.Inject
import kotlin.math.min

@PerDisplaySingleton
class PulseView @Inject constructor(@param:DisplayAware context: Context) : View(context) {
    /** Notified on the main thread when the attached window's configuration changes. */
    var configurationListener: (() -> Unit)? = null

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    private val levels = FloatArray(PulseSpectrumProcessor.BAND_COUNT)
    private val drawBands = FloatArray(PulseSpectrumProcessor.BAND_COUNT)
    // Per-bar buffers, main-thread only. Reallocated by setBarLayout when the count changes.
    private var drawLevels = FloatArray(PulseSettingsRepository.DEFAULT_BAR_COUNT)
    private var barLeft = FloatArray(PulseSettingsRepository.DEFAULT_BAR_COUNT)
    private var barRight = FloatArray(PulseSettingsRepository.DEFAULT_BAR_COUNT)
    private var barGapPercent = PulseSettingsRepository.DEFAULT_BAR_GAP_PERCENT
    private val heightCurve = PulseHeightCurve()
    // Main-thread only. The resolved color is kept apart from paint, which animated modes overwrite.
    private var resolvedArgb = Color.WHITE
    private var colorMode = PulseColorMode.SOLID

    /** The gap between bars in pixels after [PulseBarGeometry]'s 1 px rule; 0 before sizing. */
    var effectiveBarGapPx = 0f
        private set

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun setLevels(input: FloatArray) {
        synchronized(levels) {
            levels.fill(0f)
            input.copyInto(levels, endIndex = min(input.size, levels.size))
        }
        postInvalidateOnAnimation()
    }

    /**
     * Sets the bar color from [PulseColorMode.resolveArgb], including its alpha. Called on setting
     * changes, never per frame.
     */
    fun setColor(argb: Int) {
        resolvedArgb = argb
        paint.color = argb
        postInvalidateOnAnimation()
    }

    /**
     * Sets how bars are colored. An animated mode redraws on every frame while this view is
     * attached; detaching it (hiding Pulse) stops the redraws. Called on setting changes only.
     */
    fun setColorMode(mode: PulseColorMode) {
        if (mode == colorMode) return
        colorMode = mode
        paint.color = resolvedArgb
        postInvalidateOnAnimation()
    }

    /** Sets the [PulseHeightCurve] strength. Called on setting changes, never per frame. */
    fun setBoost(strength: Int) {
        heightCurve.strength = strength
        postInvalidateOnAnimation()
    }

    /**
     * Sets how many bars span the width and the percent of each bar's slot left empty. Called on
     * the main thread on setting changes, never per frame; reallocates only when [count] changes.
     */
    fun setBarLayout(count: Int, gapPercent: Int) {
        val barCount = count.coerceAtLeast(1)
        if (barCount != drawLevels.size) {
            drawLevels = FloatArray(barCount)
            barLeft = FloatArray(barCount)
            barRight = FloatArray(barCount)
        }
        barGapPercent = gapPercent
        layoutBars(width)
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(levels) { levels.fill(0f) }
        postInvalidateOnAnimation()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationListener?.invoke()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        layoutBars(width)
    }

    private fun layoutBars(width: Int) {
        if (width <= 0) return
        effectiveBarGapPx = PulseBarGeometry.layout(width, barGapPercent, barLeft, barRight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(levels) { levels.copyInto(drawBands) }
        PulseBarResampler.resample(drawBands, drawLevels)

        val bottom = height.toFloat()
        // The frame's vsync-aligned time, so every bar in one frame shares a clock.
        val timeMs = drawingTime
        for (index in drawLevels.indices) {
            val level = heightCurve.heightFor(drawLevels[index])
            if (level <= 0f) continue
            paint.color = colorMode.barArgb(resolvedArgb, timeMs, index, drawLevels.size)
            canvas.drawRect(barLeft[index], bottom * (1f - level), barRight[index], bottom, paint)
        }
        // Keeps the color clock running between FFT frames. A detached view gets no frames.
        if (colorMode.animated) postInvalidateOnAnimation()
    }
}
