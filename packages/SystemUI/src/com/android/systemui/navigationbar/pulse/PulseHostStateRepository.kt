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

import android.view.Display
import com.android.app.displaylib.PerDisplayRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The UI surface that currently owns Pulse on a display. */
enum class PulseHost {
    NONE,
    NAVIGATION_BAR,
    TASKBAR,
}

/** Host-reported state for one display. Only the [activeHost] may change the other fields. */
data class PulseHostState(
    val activeHost: PulseHost = PulseHost.NONE,
    val navigationVisible: Boolean = false,
    val screenPinningActive: Boolean = false,
)

/**
 * Per-display, source-aware state shared by Pulse hosts (navigation bar, taskbar). Scoped by its
 * provider in PerDisplaySystemUIModule; it has no @Inject constructor.
 */
class PulseHostStateRepository {
    private val mutableState = MutableStateFlow(PulseHostState())
    val state: StateFlow<PulseHostState> = mutableState.asStateFlow()

    /** Makes [host] the active host and resets all host-reported state. */
    fun activate(host: PulseHost) {
        require(host != PulseHost.NONE) { "PulseHost.NONE cannot be activated" }
        mutableState.value = PulseHostState(activeHost = host)
    }

    fun deactivate() {
        mutableState.value = PulseHostState()
    }

    /** Ignored unless [source] is the active host. */
    fun updateNavigationVisible(source: PulseHost, visible: Boolean) {
        mutableState.update { current ->
            if (current.isActive(source)) current.copy(navigationVisible = visible) else current
        }
    }

    /** Ignored unless [source] is the active host. */
    fun updateScreenPinning(source: PulseHost, active: Boolean) {
        mutableState.update { current ->
            if (current.isActive(source)) current.copy(screenPinningActive = active) else current
        }
    }

    // NONE is never a valid source, even while no host is active.
    private fun PulseHostState.isActive(source: PulseHost) =
        source != PulseHost.NONE && activeHost == source
}

/**
 * Gives SysUI-scoped hosts (navigation bar, taskbar) access to the default display's host state.
 *
 * Pulse runs only on the default display, so other displays get null. This also keeps
 * [PerDisplayRepository.get] from creating a whole display component for a secondary display as a
 * side effect of a host callback. The default display's component lives as long as the process, so
 * its repository is cached after the first successful lookup.
 */
@SysUISingleton
class PulseHostStateRepositoryStore
@Inject
constructor(private val displayComponents: PerDisplayRepository<SystemUIDisplaySubcomponent>) {
    @Volatile private var defaultDisplayRepository: PulseHostStateRepository? = null

    /** Returns null for non-default displays, or while the default display is unavailable. */
    fun forDisplay(displayId: Int): PulseHostStateRepository? {
        if (displayId != Display.DEFAULT_DISPLAY) return null
        defaultDisplayRepository?.let {
            return it
        }
        return displayComponents[displayId]?.pulseHostStateRepository?.also {
            defaultDisplayRepository = it
        }
    }
}
