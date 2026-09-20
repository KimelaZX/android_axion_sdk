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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.WallpaperController
import javax.inject.Inject

@SysUISingleton
class AxWallpaperDepthAnimator
@Inject
constructor(
    private val wallpaperController: WallpaperController,
) {
    private var animator: ValueAnimator? = null
    private var zoomOut = HOME_ZOOM

    fun clear() {
        stop()
        apply(HOME_ZOOM)
    }

    fun holdDepth() {
        stop()
        apply(WAKE_START_ZOOM)
    }

    fun prep() {
        holdDepth()
    }

    fun playReveal() {
        if (zoomOut == HOME_ZOOM || animator?.isRunning == true) {
            return
        }
        animate(zoomOut, HOME_ZOOM)
    }

    private fun animate(from: Float, to: Float) {
        stop()
        if (from == to) {
            apply(to)
            return
        }

        animator =
            ValueAnimator.ofFloat(from, to).apply {
                duration = REVEAL_DURATION_MS
                interpolator = Interpolators.FAST_OUT_SLOW_IN
                addUpdateListener { apply(it.animatedValue as Float) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (animator == animation) {
                                animator = null
                                apply(to)
                            }
                        }
                    }
                )
                start()
            }
    }

    private fun stop() {
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private fun apply(value: Float) {
        if (zoomOut == value && animator == null) {
            return
        }
        wallpaperController.setScreenOnZoom(value)
        zoomOut = value
    }

    companion object {
        private const val REVEAL_DURATION_MS = 800L
        private const val HOME_ZOOM = 0f
        private const val WAKE_START_ZOOM = 1f
    }
}
