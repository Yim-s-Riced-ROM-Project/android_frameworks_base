/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.animation.ValueAnimator
import android.os.Handler
import android.os.PowerManager
import android.platform.test.annotations.RequiresFlagsEnabled
import android.provider.Settings
import android.testing.TestableLooper.RunWithLooper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF
import com.android.internal.jank.InteractionJankMonitor.CUJ_SCREEN_OFF_SHOW_AOD
import com.android.server.display.feature.flags.Flags as displayManagerFlags
import com.android.server.power.feature.flags.Flags as powerManagerFlags
import com.android.systemui.DejankUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.domain.interactor.DisplayStateInteractor
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor
import com.android.systemui.statusbar.LiftReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.StatusBarStateControllerImpl
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.GlobalSettings
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertFalse
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class UnlockedScreenOffAnimationControllerTest : SysuiTestCase() {

    private lateinit var controller: UnlockedScreenOffAnimationController
    @Mock private lateinit var keyguardViewMediator: KeyguardViewMediator
    @Mock private lateinit var dozeParameters: DozeParameters
    @Mock private lateinit var globalSettings: GlobalSettings
    @Mock private lateinit var centralSurfaces: CentralSurfaces
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var shadeLockscreenInteractor: ShadeLockscreenInteractor
    @Mock private lateinit var panelExpansionInteractor: PanelExpansionInteractor
    @Mock private lateinit var notifShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var lightRevealScrim: LightRevealScrim
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var revealEffect: LightRevealEffect
    @Mock private lateinit var statusBarStateController: StatusBarStateControllerImpl
    @Mock private lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var displayStateInteractor: DisplayStateInteractor
    @Mock private lateinit var handler: Handler
    @Mock private lateinit var crtCoordinator: CrtScreenOffAnimationCoordinator

    val kosmos = testKosmos()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        `when`(lightRevealScrim.revealEffect).thenReturn(revealEffect)
        controller =
            UnlockedScreenOffAnimationController(
                context,
                wakefulnessLifecycle,
                statusBarStateController,
                { keyguardViewMediator },
                { dozeParameters },
                globalSettings,
                { notifShadeWindowController },
                interactionJankMonitor,
                powerManager,
                { shadeLockscreenInteractor },
                { panelExpansionInteractor },
                { displayStateInteractor },
                handler,
                crtCoordinator,
            )
        controller.initialize(centralSurfaces, shadeViewController, lightRevealScrim)
    }

    @After
    fun cleanUp() {
        // Tell the screen off controller to cancel the animations and clean up its state, or
        // subsequent tests will act unpredictably as the animator continues running.
        controller.onStartedWakingUp()
        DejankUtils.setImmediate(false)
    }

    /**
     * The AOD UI is shown during the screen off animation, after a delay to allow the light reveal
     * animation to start. If the device is woken up during the screen off, we should *never* do
     * this.
     *
     * This test confirms that we do show the AOD UI when the device is not woken up
     * (PowerManager#isInteractive = false).
     */
    @Test
    fun testAodUiShownIfNotInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive).thenReturn(false)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())

        callbackCaptor.value.run()

        verify(shadeLockscreenInteractor, times(1)).showAodUi()
    }

    @Test
    fun testAodUiShowNotInvokedIfWakingUp() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive).thenReturn(false)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()
        controller.onStartedWakingUp()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())

        callbackCaptor.value.run()

        verify(shadeLockscreenInteractor, never()).showAodUi()
    }

    /**
     * The AOD UI is shown during the screen off animation, after a delay to allow the light reveal
     * animation to start. If the device is woken up during the screen off, we should *never* do
     * this.
     *
     * This test confirms that we do not show the AOD UI when the device is woken up during screen
     * off (PowerManager#isInteractive = true).
     */
    @Test
    fun testAodUiNotShownIfInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive(eq(Display.DEFAULT_DISPLAY))).thenReturn(true)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())
        callbackCaptor.value.run()

        verify(shadeLockscreenInteractor, never()).showAodUi()
    }

    @Test
    fun testAodUiShownIfGloballyInteractiveButDefaultDisplayNotInteractive() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(powerManager.isInteractive()).thenReturn(false)
        `when`(powerManager.isInteractive(eq(Display.DEFAULT_DISPLAY))).thenReturn(false)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        controller.startAnimation()

        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())
        callbackCaptor.value.run()

        verify(shadeLockscreenInteractor).showAodUi()
    }

    @Test
    fun testNoAnimationPlaying_dozeParamsCanNotControlScreenOff() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(false)

        assertFalse(controller.shouldPlayUnlockedScreenOffAnimation())
        controller.startAnimation()
        assertFalse(controller.isAnimationPlaying())
    }

    @RequiresFlagsEnabled(
        displayManagerFlags.FLAG_SEPARATE_TIMEOUTS,
        powerManagerFlags.FLAG_SEPARATE_TIMEOUTS_FLICKER,
    )
    @Test
    fun testNoAnimationPlaying_whenDefaultDisplayIsOff() {
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(true))
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)

        assertFalse(controller.shouldPlayUnlockedScreenOffAnimation())
        controller.startAnimation()
        assertFalse(controller.isAnimationPlaying())
    }

    @Test
    fun testMinMode_noAodUi() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(dozeParameters.isMinModeActive()).thenReturn(true)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        controller.startAnimation()

        assertFalse(controller.shouldAnimateInKeyguard())

        val callbackCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(handler).postDelayed(callbackCaptor.capture(), anyLong())
        callbackCaptor.value.run()

        verify(shadeLockscreenInteractor, never()).showAodUi()
    }

    @Test
    fun testMinMode_usesLiftReveal() {
        DejankUtils.setImmediate(true)
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(dozeParameters.isMinModeActive()).thenReturn(true)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))

        controller.startAnimation()

        verify(lightRevealScrim).revealEffect = LiftReveal

        // Clean up
        DejankUtils.setImmediate(false)
    }

    @Test
    fun rejectedStart_reportsTypedDecision() {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(false)

        assertFalse(controller.startAnimation())

        verify(crtCoordinator)
            .onStockDecision(
                ScreenOffAnimationDecision.blocked(
                    ScreenOffAnimationBlockedReason.CANNOT_CONTROL_UNLOCKED_SCREEN_OFF
                )
            )
    }

    @Test
    fun crtSelected_blocksWithCrtOwnedByDisplay() {
        givenAcceptedState()
        `when`(crtCoordinator.isCrtOwnedByDisplay()).thenReturn(true)

        assertFalse(controller.shouldPlayUnlockedScreenOffAnimation())

        verify(crtCoordinator)
            .onStockDecision(
                ScreenOffAnimationDecision.blocked(
                    ScreenOffAnimationBlockedReason.CRT_OWNED_BY_DISPLAY
                )
            )
    }

    @Test
    fun crtSelected_reportsCrtOwnershipBeforePreviousRejection() {
        // The lockscreen path used to stick on PREVIOUSLY_REJECTED; CRT ownership wins first.
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(false)
        controller.startAnimation()
        `when`(crtCoordinator.isCrtOwnedByDisplay()).thenReturn(true)

        controller.shouldPlayUnlockedScreenOffAnimation()

        verify(crtCoordinator)
            .onStockDecision(
                ScreenOffAnimationDecision.blocked(
                    ScreenOffAnimationBlockedReason.CRT_OWNED_BY_DISPLAY
                )
            )
    }

    @Test
    fun stockSelected_keepsStockGates() {
        givenAcceptedState()
        `when`(crtCoordinator.isCrtOwnedByDisplay()).thenReturn(false)

        assertThat(controller.shouldPlayUnlockedScreenOffAnimation()).isTrue()
    }

    @Test
    fun normalRevealDuration_remains500Ms() {
        givenAcceptedState()

        controller.startAnimation()

        assertThat(lightRevealAnimator().duration).isEqualTo(500L)
    }

    @Test
    fun aodSchedulingDelay_remains600Ms() {
        givenAcceptedState()

        controller.startAnimation()

        verify(handler).postDelayed(any(Runnable::class.java), eq(600L))
    }

    @Test
    fun existingScreenOffCuj_stillBeginsAndEnds() {
        val rootView = mock(ViewGroup::class.java)
        `when`(notifShadeWindowController.windowRootView).thenReturn(rootView)
        givenAcceptedState()
        DejankUtils.setImmediate(true)

        controller.startAnimation()
        verify(interactionJankMonitor).begin(rootView, CUJ_SCREEN_OFF)

        lightRevealAnimator().end()
        verify(interactionJankMonitor).end(CUJ_SCREEN_OFF)
    }

    @Test
    fun existingShowAodCuj_stillBeginsAndEnds() {
        `when`(notifShadeWindowController.windowRootView).thenReturn(mock(ViewGroup::class.java))
        // Alpha already at its end value makes PropertyAnimator run the end action immediately.
        val keyguardView = mock(View::class.java)
        `when`(keyguardView.alpha).thenReturn(1f)
        var afterRan = false

        controller.animateInKeyguard(keyguardView) { afterRan = true }

        verify(interactionJankMonitor).cancel(CUJ_SCREEN_OFF_SHOW_AOD)
        verify(interactionJankMonitor)
            .begin(any(InteractionJankMonitor.Configuration.Builder::class.java))
        verify(interactionJankMonitor).end(CUJ_SCREEN_OFF_SHOW_AOD)
        assertThat(afterRan).isTrue()
    }

    /** Stubs every stock gate open, with a real 1x animator duration scale. */
    private fun givenAcceptedState(minMode: Boolean = false) {
        `when`(dozeParameters.canControlUnlockedScreenOff()).thenReturn(true)
        `when`(dozeParameters.isMinModeActive()).thenReturn(minMode)
        `when`(globalSettings.getFloat(eq(Settings.Global.ANIMATOR_DURATION_SCALE), anyFloat()))
            .thenReturn(1f)
        `when`(displayStateInteractor.isDefaultDisplayOff).thenReturn(MutableStateFlow(false))
        controller.updateAnimatorDurationScale()
    }

    private fun lightRevealAnimator(): ValueAnimator {
        val field = controller.javaClass.getDeclaredField("lightRevealAnimator")
        field.isAccessible = true
        return field.get(controller) as ValueAnimator
    }
}
