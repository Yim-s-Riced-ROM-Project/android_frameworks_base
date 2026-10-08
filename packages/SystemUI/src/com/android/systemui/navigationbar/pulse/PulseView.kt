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
    private val levels = FloatArray(BAR_COUNT)
    private val drawLevels = FloatArray(BAR_COUNT)
    private val barLeft = FloatArray(BAR_COUNT)
    private val barRight = FloatArray(BAR_COUNT)
    private val heightCurve = PulseHeightCurve()

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

    /** Sets the bar color, including its alpha. Called on setting changes, never per frame. */
    fun setColor(argb: Int) {
        paint.color = argb
        postInvalidateOnAnimation()
    }

    /** Sets the [PulseHeightCurve] strength. Called on setting changes, never per frame. */
    fun setBoost(strength: Int) {
        heightCurve.strength = strength
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
        if (width <= 0) return

        val slotWidth = width.toFloat() / BAR_COUNT
        val inset = slotWidth * BAR_INSET_RATIO
        for (index in barLeft.indices) {
            barLeft[index] = index * slotWidth + inset
            barRight[index] = (index + 1) * slotWidth - inset
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(levels) { levels.copyInto(drawLevels) }

        val bottom = height.toFloat()
        for (index in drawLevels.indices) {
            val level = heightCurve.apply(drawLevels[index])
            if (level <= 0f) continue
            canvas.drawRect(barLeft[index], bottom * (1f - level), barRight[index], bottom, paint)
        }
    }

    private companion object {
        const val BAR_COUNT = 32
        const val BAR_INSET_RATIO = 0.15f
    }
}
