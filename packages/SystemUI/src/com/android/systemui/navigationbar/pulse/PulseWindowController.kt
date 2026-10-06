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
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.PerDisplaySingleton
import javax.inject.Inject
import kotlin.math.roundToInt

/** Sole owner of the Pulse overlay window on one display. Main thread only. */
@PerDisplaySingleton
class PulseWindowController
@Inject
constructor(@param:DisplayAware private val context: Context, private val view: PulseView) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val windowTitle = "Pulse${context.displayId}"
    private var attached = false
    private var windowFailureListener: (() -> Unit)? = null
    private var heightDp = DEFAULT_HEIGHT_DP
    private var layoutParams: WindowManager.LayoutParams? = null

    init {
        view.configurationListener = ::onViewConfigurationChanged
    }

    /** Whether the overlay window is currently added to WindowManager. */
    val isAttached: Boolean
        get() = attached

    /** Invoked after the window had to be removed because it could no longer be updated. */
    fun setWindowFailureListener(listener: (() -> Unit)?) {
        windowFailureListener = listener
    }

    fun show(heightDp: Int): Boolean {
        updateHeight(heightDp)
        if (attached) return true

        val params = createLayoutParams()
        return try {
            windowManager.addView(view, params)
            layoutParams = params
            attached = true
            true
        } catch (error: RuntimeException) {
            // WindowManager failures (bad token, invalid display, detached view) fail closed.
            Log.w(TAG, "Unable to add Pulse window: ${error.javaClass.simpleName}")
            false
        }
    }

    fun updateHeight(heightDp: Int): Boolean {
        this.heightDp = heightDp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        val params = layoutParams ?: return true
        params.height = heightPx()
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (error: RuntimeException) {
            // WindowManager failures (bad token, invalid display, detached view) fail closed.
            Log.w(TAG, "Unable to update Pulse window: ${error.javaClass.simpleName}")
            false
        }
    }

    fun setColor(argb: Int) = view.setColor(argb)

    fun setLevels(levels: FloatArray) = view.setLevels(levels)

    fun clear() = view.clear()

    fun hide() {
        if (!attached) return
        view.clear()
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to remove Pulse window: ${error.javaClass.simpleName}")
            // If the view is still attached the window is orphaned: forgetting it would make the
            // next show() fail with "already added". Stay attached so a later hide() retries.
            if (view.isAttachedToWindow) return
        }
        attached = false
        layoutParams = null
    }

    fun onConfigurationChanged(): Boolean {
        return updateHeight(heightDp)
    }

    fun destroy() = hide()

    private fun onViewConfigurationChanged() {
        if (!attached || onConfigurationChanged()) return
        hide()
        windowFailureListener?.invoke()
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx(),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT,
            )
            .apply {
                gravity = Gravity.BOTTOM
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                privateFlags =
                    privateFlags or
                        WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION or
                        WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION
                setFitInsetsTypes(0)
                setTrustedOverlay()
                title = windowTitle
                accessibilityTitle = ""
            }
    }

    private fun heightPx(): Int {
        return (heightDp * context.resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        const val TAG = "PulseWindowController"
        const val DEFAULT_HEIGHT_DP = 48
        const val MIN_HEIGHT_DP = 8
        const val MAX_HEIGHT_DP = 96
    }
}
