/*
 * Copyright (C) 2026 AxionOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.shared.clocks.depth.domain.interactor

import android.content.res.Resources
import android.util.MathUtils
import com.android.systemui.shared.clocks.depth.data.repository.DepthWallpaperRepository
import com.android.systemui.shared.clocks.depth.shared.model.DepthMaskModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Singleton
class DepthWallpaperInteractorImpl
@Inject
constructor(
    private val repository: DepthWallpaperRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : DepthWallpaperInteractor {

    private val wallpaperMinScale: Float
    private val wallpaperMaxScale: Float

    init {
        val minId = Resources.getSystem().getIdentifier("config_wallpaperMinScale", "dimen", "android")
        wallpaperMinScale = if (minId != 0) {
            try { Resources.getSystem().getFloat(minId) } catch (_: Exception) { 1f }
        } else 1f

        val maxId = Resources.getSystem().getIdentifier("config_wallpaperMaxScale", "dimen", "android")
        wallpaperMaxScale = if (maxId != 0) {
            try { Resources.getSystem().getFloat(maxId) } catch (_: Exception) { 1.30f }
        } else 1.30f
    }

    override val isDepthAvailable: StateFlow<Boolean> =
        combine(repository.isDepthEnabled, repository.depthMask) { enabled, mask ->
            enabled && mask != null
        }.stateIn(scope, SharingStarted.Eagerly, false)

    override val wallpaperScale: StateFlow<Float> =
        repository.wallpaperZoom
            .map { zoom -> MathUtils.lerp(wallpaperMinScale, wallpaperMaxScale, 1f - zoom) }
            .stateIn(scope, SharingStarted.Eagerly, 1f)

    private val _activeDepthMask = MutableStateFlow<DepthMaskModel?>(null)
    override val activeDepthMask: StateFlow<DepthMaskModel?> = _activeDepthMask.asStateFlow()

    @Volatile private var displayState = DepthDisplayState()
    @Volatile private var settleJob: Job? = null

    init {
        scope.launch(mainDispatcher) {
            repository.depthMask.collect {
                updateActiveState()
            }
        }
    }

    override fun setWallpaperZoom(zoom: Float) {
        repository.setWallpaperZoom(zoom)
    }

    override fun setVisible(visible: Boolean) {
        if (displayState.isVisible == visible) return
        displayState = displayState.copy(isVisible = visible)
        updateActiveState()
    }

    override fun setDozing(dozing: Boolean) {
        if (displayState.isDozing == dozing) return
        displayState = displayState.copy(isDozing = dozing)
        updateActiveState()
    }

    override fun setDozeAmount(amount: Float) {
        if (displayState.dozeAmount == amount) return
        displayState = displayState.copy(dozeAmount = amount)
        updateActiveState()
    }

    private fun updateActiveState() {
        settleJob?.cancel()
        settleJob = null

        val currentMask = repository.depthMask.value
        val state = displayState

        if (state.isSuppressed || currentMask == null) {
            _activeDepthMask.value = null
            return
        }

        settleJob = scope.launch(mainDispatcher) {
            delay(SETTLE_DELAY_MS)
            if (!displayState.isSuppressed) {
                _activeDepthMask.value = repository.depthMask.value
            }
        }
    }

    private data class DepthDisplayState(
        val isVisible: Boolean = true,
        val isDozing: Boolean = false,
        val dozeAmount: Float = 0f,
    ) {
        val isSuppressed: Boolean
            get() = isDozing || dozeAmount > 0f || !isVisible
    }

    companion object {
        private const val SETTLE_DELAY_MS = 350L
    }
}
