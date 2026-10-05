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

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PulsePlaybackRepositoryTest {
    private val audioManager = mock<AudioManager>()
    private val underTest: PulsePlaybackRepository

    init {
        whenever(audioManager.activePlaybackConfigurations).thenReturn(emptyList())
        underTest = PulsePlaybackRepository(audioManager)
    }

    @Test
    fun target_emitsCurrentStateAndChangedCallbackStateOnly() = runTest {
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(configuration(1, 11)))
        callback.onPlaybackConfigChanged(listOf(configuration(1, 11)))
        runCurrent()

        assertThat(values)
            .containsExactly(PulsePlaybackTarget.INACTIVE, PulsePlaybackTarget(true, 11))
            .inOrder()
    }

    @Test
    fun target_acceptsMediaGameAndUnknownUsage() = runTest {
        listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            )
            .forEach { usage ->
                val values = collectInitial(listOf(configuration(1, 11, usage = usage)))
                assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 11))
            }
    }

    @Test
    fun target_excludesInactivePlayback() = runTest {
        val values = collectInitial(listOf(configuration(1, 11, active = false)))
        assertThat(values.last()).isEqualTo(PulsePlaybackTarget.INACTIVE)
    }

    @Test
    fun target_excludesIneligibleUsage() = runTest {
        val values =
            collectInitial(listOf(configuration(1, 11, usage = AudioAttributes.USAGE_ALARM)))
        assertThat(values.last()).isEqualTo(PulsePlaybackTarget.INACTIVE)
    }

    @Test
    fun target_excludesRemoteSubmixOnlyPlayback() = runTest {
        val values =
            collectInitial(
                listOf(configuration(1, 11, devices = listOf(device(TYPE_REMOTE_SUBMIX))))
            )
        assertThat(values.last()).isEqualTo(PulsePlaybackTarget.INACTIVE)
    }

    @Test
    fun target_keepsPlaybackWhenRemoteSubmixIsNotTheOnlyOutput() = runTest {
        val devices = listOf(device(TYPE_REMOTE_SUBMIX), device(TYPE_BUILTIN_SPEAKER))
        val values = collectInitial(listOf(configuration(1, 11, devices = devices)))
        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 11))
    }

    @Test
    fun selectedPlayer_remainsSelectedWhenCallbackOrderChanges() = runTest {
        val first = configuration(playerId = 1, sessionId = 11)
        val second = configuration(playerId = 2, sessionId = 22)
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(first, second))
        callback.onPlaybackConfigChanged(listOf(second, first))
        runCurrent()

        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 11))
    }

    @Test
    fun newlyActivePlayer_isSelectedWhenCurrentSelectionStops() = runTest {
        val first = configuration(playerId = 1, sessionId = 11)
        val second = configuration(playerId = 2, sessionId = 22)
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(first))
        callback.onPlaybackConfigChanged(listOf(second, first))
        runCurrent()
        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 11))
        callback.onPlaybackConfigChanged(listOf(second))
        runCurrent()

        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 22))
    }

    @Test
    fun newlyActivePlayer_isPreferredOverExistingPlayerWhenSelectionStops() = runTest {
        val first = configuration(playerId = 1, sessionId = 11)
        val second = configuration(playerId = 2, sessionId = 22)
        val third = configuration(playerId = 3, sessionId = 33)
        val (values, callback) = startCollecting()
        callback.onPlaybackConfigChanged(listOf(first, second))

        callback.onPlaybackConfigChanged(listOf(second, third))
        runCurrent()

        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 33))
    }

    @Test
    fun positiveSession_isPreferredOverNonPositiveSession() = runTest {
        val noSession = configuration(playerId = 1, sessionId = 0)
        val withSession = configuration(playerId = 2, sessionId = 22)
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(noSession, withSession))
        runCurrent()

        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, 22))
    }

    @Test
    fun selectedSessionChange_isEmitted() = runTest {
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(configuration(1, 11)))
        callback.onPlaybackConfigChanged(listOf(configuration(1, 33)))
        runCurrent()

        assertThat(values.takeLast(2))
            .containsExactly(PulsePlaybackTarget(true, 11), PulsePlaybackTarget(true, 33))
            .inOrder()
    }

    @Test
    fun noPositiveSession_emitsActiveTargetWithNullSession() = runTest {
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(configuration(1, 0)))
        runCurrent()

        assertThat(values.last()).isEqualTo(PulsePlaybackTarget(true, null))
    }

    @Test
    fun stop_emitsInactive() = runTest {
        val (values, callback) = startCollecting()

        callback.onPlaybackConfigChanged(listOf(configuration(1, 11)))
        callback.onPlaybackConfigChanged(emptyList())
        runCurrent()

        assertThat(values.takeLast(2))
            .containsExactly(PulsePlaybackTarget(true, 11), PulsePlaybackTarget.INACTIVE)
            .inOrder()
    }

    @Test
    fun selection_isIndependentPerCollector() = runTest {
        val first = configuration(playerId = 1, sessionId = 11)
        val second = configuration(playerId = 2, sessionId = 22)
        val (valuesA, callbackA) = startCollecting()
        callbackA.onPlaybackConfigChanged(listOf(first))
        val (valuesB, callbackB) = startCollecting()

        callbackB.onPlaybackConfigChanged(listOf(second, first))
        runCurrent()

        assertThat(valuesA.last()).isEqualTo(PulsePlaybackTarget(true, 11))
        assertThat(valuesB.last()).isEqualTo(PulsePlaybackTarget(true, 22))
    }

    @Test
    fun collection_registersAndUnregistersSameCallback() = runTest {
        val job = backgroundScope.launch { underTest.target.toList() }
        runCurrent()
        val callback = registeredCallback()
        verify(audioManager, never()).unregisterAudioPlaybackCallback(any())

        job.cancelAndJoin()

        verify(audioManager).unregisterAudioPlaybackCallback(eq(callback))
    }

    private fun TestScope.startCollecting():
        Pair<MutableList<PulsePlaybackTarget>, AudioManager.AudioPlaybackCallback> {
        val values = mutableListOf<PulsePlaybackTarget>()
        backgroundScope.launch { underTest.target.toList(values) }
        runCurrent()
        return values to registeredCallback()
    }

    private fun TestScope.collectInitial(
        initial: List<AudioPlaybackConfiguration>
    ): List<PulsePlaybackTarget> {
        whenever(audioManager.activePlaybackConfigurations).thenReturn(initial)
        val values = mutableListOf<PulsePlaybackTarget>()
        val job = backgroundScope.launch { underTest.target.toList(values) }
        runCurrent()
        job.cancel()
        runCurrent()
        whenever(audioManager.activePlaybackConfigurations).thenReturn(emptyList())
        return values
    }

    private fun device(type: Int): AudioDeviceInfo =
        mock<AudioDeviceInfo>().also { whenever(it.type).thenReturn(type) }

    private fun configuration(
        playerId: Int,
        sessionId: Int,
        usage: Int = AudioAttributes.USAGE_MEDIA,
        active: Boolean = true,
        devices: List<AudioDeviceInfo> = emptyList(),
    ): AudioPlaybackConfiguration {
        // AudioAttributes.Builder is a framework stub on a plain JVM, so mock the attributes.
        val attributes = mock<AudioAttributes>()
        whenever(attributes.usage).thenReturn(usage)
        return mock<AudioPlaybackConfiguration>().also {
            whenever(it.playerInterfaceId).thenReturn(playerId)
            whenever(it.sessionId).thenReturn(sessionId)
            whenever(it.isActive).thenReturn(active)
            whenever(it.audioAttributes).thenReturn(attributes)
            whenever(it.audioDeviceInfos).thenReturn(devices)
        }
    }

    private fun registeredCallback(): AudioManager.AudioPlaybackCallback {
        val captor = argumentCaptor<AudioManager.AudioPlaybackCallback>()
        verify(audioManager).registerAudioPlaybackCallback(captor.capture(), isNull())
        clearInvocations(audioManager)
        whenever(audioManager.activePlaybackConfigurations).thenReturn(emptyList())
        return captor.lastValue
    }

    private companion object {
        const val TYPE_REMOTE_SUBMIX = AudioDeviceInfo.TYPE_REMOTE_SUBMIX
        const val TYPE_BUILTIN_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }
}
