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

/**
 * CRT-style screen-off effect: black masks close vertically toward the center, then the remaining
 * thin aperture becomes a white beam with a phosphor trail and red/blue fringe that contracts
 * horizontally to the center.
 *
 * Stateless: every value is derived from the reveal amount and the scrim's current dimensions. It
 * draws only masks and beam rects over live content and never reads display pixels.
 */
object CrtCollapseReveal : LightRevealEffect {
    /** Share of the transition spent closing the aperture vertically. */
    private const val VERTICAL_PHASE_END = 0.5f

    /** Horizontal aperture overscan per side, so rounded corners and cutouts never show. */
    private const val HORIZONTAL_OVERSCAN_FRACTION = 0.03f

    private const val MAX_FRINGE_ALPHA = 0.22f
    private const val MAX_TRAIL_ALPHA = 0.30f

    override fun setRevealAmountOnScrim(amount: Float, scrim: LightRevealScrim) {
        val clampedAmount = amount.coerceIn(0f, 1f)
        val progress = 1f - clampedAmount
        val verticalProgress = (progress / VERTICAL_PHASE_END).coerceIn(0f, 1f)
        val beamProgress =
            ((progress - VERTICAL_PHASE_END) / (1f - VERTICAL_PHASE_END)).coerceIn(0f, 1f)
        val easedVertical = verticalProgress * verticalProgress
        val easedBeam = beamProgress * beamProgress

        val centerX = scrim.width / 2f
        val centerY = scrim.height / 2f
        val overscan = scrim.width * HORIZONTAL_OVERSCAN_FRACTION
        val beamHalfHeight = maxOf(1f, scrim.crtDensity)
        // Never closes below the beam, so the closure stays monotonic into the beam phase.
        val halfApertureHeight = maxOf(beamHalfHeight, centerY * (1f - easedVertical))
        val halfApertureWidth = (centerX + overscan) * (1f - easedBeam)
        val emissionEnvelope =
            if (beamProgress == 0f || beamProgress == 1f) 0f
            else 4f * beamProgress * (1f - beamProgress)

        scrim.interpolatedRevealAmount = clampedAmount
        scrim.setCrtRevealState(
            left = centerX - halfApertureWidth,
            top = centerY - halfApertureHeight,
            right = centerX + halfApertureWidth,
            bottom = centerY + halfApertureHeight,
            beamHalfHeight = beamHalfHeight,
            glowHalfHeight = maxOf(4f, 5f * scrim.crtDensity),
            fringeOffset = maxOf(1f, 2f * scrim.crtDensity),
            coreAlpha = emissionEnvelope,
            trailAlpha = emissionEnvelope * MAX_TRAIL_ALPHA,
            redFringeAlpha = emissionEnvelope * MAX_FRINGE_ALPHA,
            blueFringeAlpha = emissionEnvelope * MAX_FRINGE_ALPHA,
        )
    }
}
