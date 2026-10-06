package com.android.systemui.navigationbar

import android.app.ActivityManager
import android.app.StatusBarManager.WINDOW_NAVIGATION_BAR
import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.LauncherProxyService
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.model.SysUiState
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler
import com.android.systemui.navigationbar.pulse.PulseHost
import com.android.systemui.navigationbar.pulse.PulseHostState
import com.android.systemui.navigationbar.pulse.PulseHostStateRepository
import com.android.systemui.navigationbar.pulse.PulseHostStateRepositoryStore
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.phone.AutoHideController
import com.android.systemui.statusbar.phone.LightBarController
import com.android.systemui.statusbar.phone.LightBarTransitionsController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.wm.shell.back.BackAnimation
import com.android.wm.shell.pip.Pip
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class TaskbarDelegateTest : SysuiTestCase() {
    val DISPLAY_ID = 0

    val MODE_GESTURE = 0

    val MODE_THREE_BUTTON = 1

    private lateinit var mTaskStackChangeListeners: TaskStackChangeListeners
    private lateinit var mTaskbarDelegate: TaskbarDelegate
    @Mock lateinit var mEdgeBackGestureHandler: EdgeBackGestureHandler
    @Mock lateinit var mLightBarControllerFactory: LightBarTransitionsController.Factory
    @Mock lateinit var mLightBarTransitionController: LightBarTransitionsController
    @Mock lateinit var mCommandQueue: CommandQueue
    @Mock lateinit var mLauncherProxyService: LauncherProxyService
    @Mock lateinit var mNavBarHelper: NavBarHelper
    @Mock lateinit var mNavigationModeController: NavigationModeController
    @Mock lateinit var mSysUiState: SysUiState
    @Mock lateinit var mDumpManager: DumpManager
    @Mock lateinit var mAutoHideController: AutoHideController
    @Mock lateinit var mLightBarController: LightBarController
    @Mock lateinit var mOptionalPip: Optional<Pip>
    @Mock lateinit var mBackAnimation: BackAnimation
    @Mock lateinit var mCurrentSysUiState: NavBarHelper.CurrentSysuiState
    @Mock lateinit var mStatusBarKeyguardViewManager: StatusBarKeyguardViewManager
    @Mock lateinit var mStatusBarStateController: StatusBarStateController
    @Mock lateinit var mDisplayTracker: DisplayTracker
    @Mock lateinit var mHandler: Handler
    @Mock lateinit var mPulseHostStateRepositoryStore: PulseHostStateRepositoryStore
    private val mPulseHostStateRepository = PulseHostStateRepository()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        `when`(mNavBarHelper.edgeBackGestureHandler).thenReturn(mEdgeBackGestureHandler)
        `when`(mLightBarControllerFactory.create(any())).thenReturn(mLightBarTransitionController)
        `when`(mNavBarHelper.currentSysuiState).thenReturn(mCurrentSysUiState)
        `when`(mSysUiState.setFlag(anyLong(), anyBoolean())).thenReturn(mSysUiState)
        `when`(mHandler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }
        `when`(mPulseHostStateRepositoryStore.forDisplay(DISPLAY_ID))
            .thenReturn(mPulseHostStateRepository)

        mTaskStackChangeListeners = TaskStackChangeListeners.getTestInstance()
        mTaskbarDelegate =
            TaskbarDelegate(
                context,
                mLightBarControllerFactory,
                mStatusBarKeyguardViewManager,
                mStatusBarStateController,
                mHandler,
                mPulseHostStateRepositoryStore,
            )
        mTaskbarDelegate.setDependencies(
            mCommandQueue,
            mLauncherProxyService,
            mNavBarHelper,
            mNavigationModeController,
            mSysUiState,
            mDumpManager,
            mAutoHideController,
            mLightBarController,
            mOptionalPip,
            mBackAnimation,
            mTaskStackChangeListeners,
            mDisplayTracker,
        )
    }

    @Test
    fun navigationModeInitialized() {
        `when`(mNavigationModeController.addListener(any())).thenReturn(MODE_THREE_BUTTON)
        assert(mTaskbarDelegate.navigationMode == -1)
        mTaskbarDelegate.init(DISPLAY_ID)
        assert(mTaskbarDelegate.navigationMode == MODE_THREE_BUTTON)
    }

    @Test
    fun navigationModeInitialized_notifyEdgeBackHandler() {
        `when`(mNavigationModeController.addListener(any())).thenReturn(MODE_GESTURE)
        mTaskbarDelegate.init(DISPLAY_ID)
        verify(mEdgeBackGestureHandler, times(1)).onNavigationModeChanged(MODE_GESTURE)
    }

    @Test
    fun screenPinningEnabled_updatesSysuiState() {
        mTaskbarDelegate.init(DISPLAY_ID)
        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_PINNED
        )
        verify(mSysUiState, times(1))
            .setFlag(
                ArgumentMatchers.eq(QuickStepContract.SYSUI_STATE_SCREEN_PINNING),
                ArgumentMatchers.eq(true),
            )
    }

    @Test
    fun pulse_windowStateShowing_publishesVisible() {
        initAsActivePulseHost()
        mTaskbarDelegate.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_HIDDEN)

        mTaskbarDelegate.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_SHOWING)

        assertThat(pulseState()).isEqualTo(PulseHostState(PulseHost.TASKBAR, true, false))
    }

    @Test
    fun pulse_windowStateHidden_publishesHidden() {
        initAsActivePulseHost()

        mTaskbarDelegate.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_HIDDEN)

        assertThat(pulseState()).isEqualTo(PulseHostState(PulseHost.TASKBAR, false, false))
    }

    @Test
    fun pulse_stashedTaskbar_doesNotOverrideShownWindow() {
        initAsActivePulseHost()

        mTaskbarDelegate.onTaskbarStatusUpdated(/* visible= */ false, /* stashed= */ true)

        assertThat(pulseState().navigationVisible).isTrue()
    }

    @Test
    fun pulse_visibleTaskbar_doesNotOverrideHiddenWindow() {
        initAsActivePulseHost()
        mTaskbarDelegate.setWindowState(DISPLAY_ID, WINDOW_NAVIGATION_BAR, WINDOW_STATE_HIDDEN)

        mTaskbarDelegate.onTaskbarStatusUpdated(/* visible= */ true, /* stashed= */ false)

        assertThat(pulseState().navigationVisible).isFalse()
    }

    @Test
    fun pulse_taskbarStatusUpdate_republishesRetainedState() {
        initAsActivePulseHost()
        mPulseHostStateRepository.updateNavigationVisible(PulseHost.TASKBAR, false)

        mTaskbarDelegate.onTaskbarStatusUpdated(/* visible= */ false, /* stashed= */ true)

        assertThat(pulseState().navigationVisible).isTrue()
    }

    @Test
    fun pulse_screenPinning_publishesCurrentValue() {
        initAsActivePulseHost()

        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_PINNED
        )
        assertThat(pulseState().screenPinningActive).isTrue()

        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_NONE
        )
        assertThat(pulseState().screenPinningActive).isFalse()
    }

    @Test
    fun pulse_publishCurrentPulseState_republishesRetainedState() {
        initAsActivePulseHost()
        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_PINNED
        )
        // A host transition resets the repository to its defaults.
        mPulseHostStateRepository.deactivate()
        mPulseHostStateRepository.activate(PulseHost.TASKBAR)
        assertThat(pulseState()).isEqualTo(PulseHostState(PulseHost.TASKBAR, false, false))

        mTaskbarDelegate.publishCurrentPulseState()

        assertThat(pulseState()).isEqualTo(PulseHostState(PulseHost.TASKBAR, true, true))
    }

    @Test
    fun pulse_inactiveHost_publishesNothing() {
        mTaskbarDelegate.init(DISPLAY_ID)

        mTaskbarDelegate.publishCurrentPulseState()
        mTaskStackChangeListeners.listenerImpl.onLockTaskModeChanged(
            ActivityManager.LOCK_TASK_MODE_PINNED
        )

        assertThat(pulseState()).isEqualTo(PulseHostState())
    }

    private fun initAsActivePulseHost() {
        mTaskbarDelegate.init(DISPLAY_ID)
        mPulseHostStateRepository.activate(PulseHost.TASKBAR)
        mTaskbarDelegate.publishCurrentPulseState()
    }

    private fun pulseState(): PulseHostState = mPulseHostStateRepository.state.value
}
