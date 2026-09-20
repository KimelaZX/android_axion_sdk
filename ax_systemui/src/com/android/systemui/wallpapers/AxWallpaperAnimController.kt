/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.wallpapers

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class AxWallpaperAnimController
@Inject
constructor(
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val wallpaperZoomController: AxWallpaperZoomController,
    private val zoomAnimator: AxWallpaperZoomAnimator,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) : CoreStartable {

    override fun start() {
        zoomAnimator.start()
        onStateChanged(keyguardTransitionInteractor.currentKeyguardState.value)

        scope.launch(context = mainDispatcher) {
            keyguardInteractor.isDozing.collect { isDozing ->
                zoomAnimator.onDozingChanged(isDozing)
                if (isDozing) {
                    setLauncherZoom(false)
                }
            }
        }

        scope.launch(context = mainDispatcher) {
            keyguardTransitionInteractor.currentKeyguardState.collect { state ->
                onStateChanged(state)
            }
        }

        scope.launch(context = mainDispatcher) {
            keyguardTransitionInteractor.transitionValue(KeyguardState.GONE).collect { progress ->
                zoomAnimator.onUnlockProgress(progress)
            }
        }
    }

    private fun onStateChanged(state: KeyguardState) {
        setLauncherZoom(state == KeyguardState.GONE)
    }

    private fun setLauncherZoom(enabled: Boolean) {
        wallpaperZoomController.setLauncherZoomEnabled(enabled)
    }
}
