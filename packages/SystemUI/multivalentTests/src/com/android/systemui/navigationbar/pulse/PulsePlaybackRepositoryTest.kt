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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull

@SmallTest
@RunWith(AndroidJUnit4::class)
class PulsePlaybackRepositoryTest : SysuiTestCase() {
    @Mock private lateinit var audioManager: AudioManager
    @Mock private lateinit var playbackConfig: AudioPlaybackConfiguration
    @Mock private lateinit var audioDeviceInfo: AudioDeviceInfo

    private lateinit var underTest: PulsePlaybackRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(audioManager.activePlaybackConfigurations).thenReturn(emptyList())
        whenever(playbackConfig.isActive).thenReturn(true)
        whenever(playbackConfig.audioDeviceInfos).thenReturn(emptyList())
        underTest = PulsePlaybackRepository(audioManager)
    }

    @Test
    fun playbackActive_emitsCurrentStateAndChangedCallbackStateOnly() = runTest {
        val values = mutableListOf<Boolean>()
        val collectJob = backgroundScope.launch { underTest.isPlaybackActive.toList(values) }
        runCurrent()
        val callback = registeredCallback()

        callback.onPlaybackConfigChanged(listOf(configForUsage(AudioAttributes.USAGE_MEDIA)))
        callback.onPlaybackConfigChanged(listOf(configForUsage(AudioAttributes.USAGE_MEDIA)))
        runCurrent()

        assertThat(values).containsExactly(false, true).inOrder()
        collectJob.cancelAndJoin()
    }

    @Test
    fun playbackActive_acceptsMediaGameAndUnknownUsage() = runTest {
        val acceptedUsages =
            listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_UNKNOWN,
            )

        acceptedUsages.forEach { usage ->
            whenever(audioManager.activePlaybackConfigurations)
                .thenReturn(listOf(configForUsage(usage)))
            assertThat(underTest.isPlaybackActive.firstValue()).isTrue()
        }
    }

    @Test
    fun playbackActive_excludesInactiveAndNonVisualizableUsage() = runTest {
        whenever(playbackConfig.isActive).thenReturn(false)
        whenever(audioManager.activePlaybackConfigurations)
            .thenReturn(listOf(configForUsage(AudioAttributes.USAGE_MEDIA)))
        assertThat(underTest.isPlaybackActive.firstValue()).isFalse()

        whenever(playbackConfig.isActive).thenReturn(true)
        whenever(audioManager.activePlaybackConfigurations)
            .thenReturn(listOf(configForUsage(AudioAttributes.USAGE_ALARM)))
        assertThat(underTest.isPlaybackActive.firstValue()).isFalse()
    }

    @Test
    fun playbackActive_excludesRemoteSubmixOnlyPlayback() = runTest {
        whenever(audioDeviceInfo.type).thenReturn(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)
        whenever(playbackConfig.audioDeviceInfos).thenReturn(listOf(audioDeviceInfo))
        whenever(audioManager.activePlaybackConfigurations)
            .thenReturn(listOf(configForUsage(AudioAttributes.USAGE_MEDIA)))

        assertThat(underTest.isPlaybackActive.firstValue()).isFalse()
    }

    @Test
    fun collection_registersAndUnregistersSameCallback() = runTest {
        val collectJob = backgroundScope.launch { underTest.isPlaybackActive.toList() }
        runCurrent()
        val callback = registeredCallback()
        verify(audioManager, never()).unregisterAudioPlaybackCallback(any())

        collectJob.cancelAndJoin()

        verify(audioManager).unregisterAudioPlaybackCallback(eq(callback))
    }

    private fun configForUsage(usage: Int): AudioPlaybackConfiguration {
        whenever(playbackConfig.audioAttributes)
            .thenReturn(AudioAttributes.Builder().setUsage(usage).build())
        return playbackConfig
    }

    private fun registeredCallback(): AudioManager.AudioPlaybackCallback {
        val captor = kotlinArgumentCaptor<AudioManager.AudioPlaybackCallback>()
        verify(audioManager).registerAudioPlaybackCallback(captor.capture(), isNull())
        clearInvocations(audioManager)
        return captor.value
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.firstValue(): Boolean {
        return first()
    }
}
