/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.systemui.media

import android.graphics.drawable.Drawable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val MEDIA_ART_STYLE_BLUR = 0
const val MEDIA_ART_STYLE_CONCEPT = 1

data class MediaArtUiState(
    val isEnabled: Boolean = false,
    val isVisible: Boolean = false,
    val isDozing: Boolean = false,
    val artworkDrawable: Drawable? = null,
    val blurLevel: Int = 0,
    val artStyle: Int = MEDIA_ART_STYLE_BLUR
)

@SysUISingleton
class MediaArtInteractor @Inject constructor(
    @Application private val scope: CoroutineScope,
    private val repository: MediaArtSettingsRepository,
    private val mediaSessionManager: MediaSessionManager,
    private val keyguardStateController: KeyguardStateController,
    private val statusBarStateController: StatusBarStateController,
    private val shadeInteractor: ShadeInteractor,
    private val dumpManager: DumpManager,
) : MediaSessionManager.MediaDataListener,
    KeyguardStateController.Callback,
    StatusBarStateController.StateListener,
    Dumpable {

    private val _uiState = MutableStateFlow(MediaArtUiState())
    val uiState: StateFlow<MediaArtUiState> = _uiState.asStateFlow()

    private var artworkDrawable: Drawable? = null
    private var featureEnabled = false

    init {
        dumpManager.registerNormalDumpable(TAG, this)
        observeSettings()
        observeShade()
        keyguardStateController.addCallback(this)
        statusBarStateController.addCallback(this)
        mediaSessionManager.addListener(this)
        _uiState.update { it.copy(isDozing = statusBarStateController.isDozing) }
    }

    private fun observeSettings() {
        scope.launch {
            repository.settingsFlow.collect { settings ->
                featureEnabled = settings.isEnabled
                _uiState.update {
                    it.copy(
                        isEnabled = settings.isEnabled,
                        blurLevel = settings.blurLevel,
                        artStyle = settings.artStyle
                    )
                }
                updateVisibility()
            }
        }
    }

    private fun observeShade() {
        scope.launch {
            combine(
                shadeInteractor.isQsExpanded,
                shadeInteractor.qsExpansion
            ) { isQsExpanded, qsExpansion ->
                isQsExpanded || qsExpansion > 0.1f
            }.distinctUntilChanged().collect {
                updateVisibility()
            }
        }
    }

    override fun onAlbumArtChanged(drawable: Drawable?) {
        artworkDrawable = drawable
        _uiState.update { it.copy(artworkDrawable = drawable) }
        updateVisibility()
    }

    override fun onPlaybackStateChanged(state: Int) {
        updateVisibility()
    }

    override fun onKeyguardShowingChanged() {
        updateVisibility()
    }

    override fun onPrimaryBouncerShowingChanged() {
        updateVisibility()
    }

    override fun onKeyguardGoingAwayChanged() {
        updateVisibility()
    }

    override fun onKeyguardFadingAwayChanged() {
        updateVisibility()
    }

    override fun onKeyguardDismissAmountChanged() {
        updateVisibility()
    }

    override fun onDozingChanged(isDozing: Boolean) {
        _uiState.update { it.copy(isDozing = isDozing) }
        updateVisibility()
    }

    override fun onStateChanged(newState: Int) {
        updateVisibility()
    }

    private fun shouldShowMediaArt(): Boolean {
        val isDozing = statusBarStateController.isDozing
        val isKeyguardShowing = (keyguardStateController.isShowing || isDozing) &&
            !keyguardStateController.isOccluded &&
            !keyguardStateController.isKeyguardGoingAway &&
            !keyguardStateController.isKeyguardFadingAway
        val isBouncerShowing = keyguardStateController.isPrimaryBouncerShowing
        val isQsCollapsed = !shadeInteractor.isQsExpanded.value && shadeInteractor.qsExpansion.value <= 0.1f

        return featureEnabled &&
            isKeyguardShowing &&
            !isBouncerShowing &&
            isQsCollapsed &&
            mediaSessionManager.isMediaPlaying &&
            artworkDrawable != null
    }

    private fun updateVisibility() {
        val shouldShow = shouldShowMediaArt()
        _uiState.update { it.copy(isVisible = shouldShow) }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val isDozing = statusBarStateController.isDozing
        val isKeyguardShowing = (keyguardStateController.isShowing || isDozing) &&
            !keyguardStateController.isOccluded &&
            !keyguardStateController.isKeyguardGoingAway &&
            !keyguardStateController.isKeyguardFadingAway
        val isOccluded = keyguardStateController.isOccluded
        val isGoingAway = keyguardStateController.isKeyguardGoingAway
        val isFadingAway = keyguardStateController.isKeyguardFadingAway
        val isBouncer = keyguardStateController.isPrimaryBouncerShowing
        val isQsExpanded = shadeInteractor.isQsExpanded.value
        val qsExpansion = shadeInteractor.qsExpansion.value
        val isQsCollapsed = !isQsExpanded && qsExpansion <= 0.1f
        val isPlaying = mediaSessionManager.isMediaPlaying
        val hasArt = artworkDrawable != null
        val shouldShow = shouldShowMediaArt()

        pw.println("MediaArtInteractor:")
        pw.println("  shouldShowMediaArt: $shouldShow")
        pw.println("  uiState.isVisible: ${_uiState.value.isVisible}")
        pw.println("  Decision Checklist:")
        pw.println("    [${if (featureEnabled) "X" else " "}] featureEnabled: $featureEnabled")
        pw.println("    [${if (isKeyguardShowing) "X" else " "}] isKeyguardShowing: $isKeyguardShowing (rawShowing=${keyguardStateController.isShowing}, isDozing=$isDozing)")
        pw.println("    [${if (!isOccluded) "X" else " "}] !isOccluded: ${!isOccluded}")
        pw.println("    [${if (!isGoingAway) "X" else " "}] !isKeyguardGoingAway: ${!isGoingAway}")
        pw.println("    [${if (!isFadingAway) "X" else " "}] !isKeyguardFadingAway: ${!isFadingAway}")
        pw.println("    [${if (!isBouncer) "X" else " "}] !isPrimaryBouncerShowing: ${!isBouncer}")
        pw.println("    [${if (isQsCollapsed) "X" else " "}] isQsCollapsed: $isQsCollapsed (isQsExpanded=$isQsExpanded, qsExpansion=$qsExpansion)")
        pw.println("    [${if (isPlaying) "X" else " "}] isMediaPlaying: $isPlaying")
        pw.println("    [${if (hasArt) "X" else " "}] hasArtworkDrawable: $hasArt")
        artworkDrawable?.let {
            pw.println("  Artwork:")
            pw.println("    type: ${it::class.java.simpleName}")
            pw.println("    intrinsicDimensions: ${it.intrinsicWidth}x${it.intrinsicHeight}")
        }
        pw.println("  StatusBar & Doze:")
        pw.println("    isDozing: $isDozing")
        pw.println("    dozeAmount: ${statusBarStateController.dozeAmount}")
        pw.println("    statusBarState: ${statusBarStateController.state}")
        pw.println("  Current uiState: ${_uiState.value}")
    }

    companion object {
        private const val TAG = "MediaArtInteractor"
    }
}
