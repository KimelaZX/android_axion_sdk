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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.ScreenLifecycle
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.LightRevealScrimInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@SysUISingleton
class AxWallpaperZoomAnimator
@Inject
constructor(
    private val wallpaperZoomController: AxWallpaperZoomController,
    private val wallpaperRepository: WallpaperRepository,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val screenLifecycle: ScreenLifecycle,
    private val lightRevealScrimInteractor: LightRevealScrimInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
) {
    private var animator: ValueAnimator? = null
    private var state = AnimationState()
    private var fullAodWakeJob: Job? = null
    private val wakeGeneration = AtomicInteger(0)

    val isHolding: Boolean
        get() = state.isHolding

    val isAnimating: Boolean
        get() = animator?.isRunning == true

    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedWakingUp() {
            onStartedWaking()
        }

        override fun onFinishedWakingUp() {
            triggerRevealIfPrepped("finishedWaking")
        }

        override fun onStartedGoingToSleep() {
            onGoingToSleep()
        }
    }

    private val screenLifecycleObserver = object : ScreenLifecycle.Observer {
        override fun onScreenTurnedOn() {
            if (isWakingOrAwake()) {
                if (state.isPrepped) {
                    triggerRevealIfPrepped("screenTurnedOn")
                } else if (state.zoomOut < LOCKSCREEN_RESTING_ZOOM && animator?.isRunning != true) {
                    playReveal("screenTurnedOnFallback")
                }
            }
        }

        override fun onScreenTurningOff() {
            onGoingToSleep()
        }
    }

    fun start() {
        wakefulnessLifecycle.addObserver(wakefulnessObserver)
        screenLifecycle.addObserver(screenLifecycleObserver)

        scope.launch(context = mainDispatcher) {
            lightRevealScrimInteractor.revealAmount.collect { progress ->
                if (progress >= REVEAL_START_THRESHOLD && screenLifecycle.screenState != ScreenLifecycle.SCREEN_OFF) {
                    triggerRevealIfPrepped("lightReveal:$progress")
                }
            }
        }

        scope.launch(context = mainDispatcher) {
            keyguardTransitionInteractor.startedKeyguardTransitionStep.collect { step ->
                onKeyguardTransitionStarted(step.to)
            }
        }

        scope.launch(context = mainDispatcher) {
            combine(
                wallpaperRepository.wallpaperSupportsAmbientMode.distinctUntilChanged(),
                wallpaperRepository.lockscreenWallpaperInfo,
                ::Pair,
            ).collect { (aod, lockInfo) ->
                state = state.copy(isAodWallpaper = aod)
                if (lockInfo != null) {
                    fullAodWakeJob?.cancel()
                    fullAodWakeJob = null
                    state = state.copy(isPrepped = false, hasRevealed = false)
                    clear()
                }
            }
        }
    }

    private fun onStartedWaking() {
        if (!canUseWallpaper()) {
            clear()
            return
        }
        val gen = wakeGeneration.incrementAndGet()
        fullAodWakeJob?.cancel()
        stop()
        state = state.copy(isPrepped = true, hasRevealed = false)
        apply(WAKE_START_ZOOM, "wakePrep")

        if (state.isAodWallpaper) {
            fullAodWakeJob = scope.launch(mainDispatcher) {
                delay(FULL_AOD_SYNC_DELAY_MS)
                if (wakeGeneration.get() == gen && isWakingOrAwake()) {
                    triggerRevealIfPrepped("fullAodSyncWake")
                }
            }
        }
    }

    private fun triggerRevealIfPrepped(reason: String) {
        if (!state.isPrepped || state.hasRevealed) return
        if (state.isAodWallpaper && !reason.startsWith("fullAodSyncWake") && reason != "finishedWaking") {
            return
        }
        fullAodWakeJob?.cancel()
        fullAodWakeJob = null
        state = state.copy(isPrepped = false, hasRevealed = true)
        playReveal(reason)
    }

    private fun onGoingToSleep() {
        if (isWakingOrAwake()) return
        wakeGeneration.incrementAndGet()
        fullAodWakeJob?.cancel()
        fullAodWakeJob = null
        val wasAod = state.isAodWallpaper
        state = state.copy(isPrepped = false, hasRevealed = false)
        if (wasAod && canUseWallpaper()) {
            animate(state.zoomOut, WAKE_START_ZOOM, TO_AOD_DURATION_MS, "fullAodSleepZoomIn")
        } else {
            clear()
        }
    }

    fun onDozingChanged(isDozing: Boolean) {
        if (isDozing && !isWakingOrAwake()) {
            onGoingToSleep()
        }
    }

    private fun isWakingOrAwake(): Boolean {
        val wakefulness = wakefulnessLifecycle.wakefulness
        return wakefulness == WakefulnessLifecycle.WAKEFULNESS_WAKING ||
            wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE
    }

    private fun onKeyguardTransitionStarted(toState: KeyguardState) {
        when (toState) {
            KeyguardState.GONE -> {
                if (!isWakingOrAwake() || !state.isPrepped) {
                    wakeGeneration.incrementAndGet()
                    fullAodWakeJob?.cancel()
                    fullAodWakeJob = null
                    state = state.copy(isPrepped = false, hasRevealed = false)
                    if (isHolding || isAnimating) {
                        animateToResting()
                    } else {
                        clear()
                    }
                }
            }
            KeyguardState.AOD,
            KeyguardState.DOZING,
            KeyguardState.OFF -> {
                if (!isWakingOrAwake()) {
                    onGoingToSleep()
                }
            }
            KeyguardState.PRIMARY_BOUNCER,
            KeyguardState.ALTERNATE_BOUNCER,
            KeyguardState.OCCLUDED -> {
                wakeGeneration.incrementAndGet()
                fullAodWakeJob?.cancel()
                fullAodWakeJob = null
                state = state.copy(isPrepped = false, hasRevealed = false)
                clear()
            }
            else -> {}
        }
    }

    fun onUnlockProgress(progress: Float) {
        if (progress > 0f && progress < 1f) {
            if (!state.isPrepped && (isHolding || isAnimating)) {
                animateToResting()
            }
        }
    }

    fun playReveal(reason: String = "wakeReveal") {
        if (!canUseWallpaper()) {
            clear()
            return
        }
        val current = (animator?.animatedValue as? Float) ?: state.zoomOut
        animate(current, LOCKSCREEN_RESTING_ZOOM, REVEAL_DURATION_MS, reason)
    }

    fun clear() {
        fullAodWakeJob?.cancel()
        fullAodWakeJob = null
        stop()
        apply(LOCKSCREEN_RESTING_ZOOM, "clear")
    }

    fun animateToResting(durationMs: Long = UNLOCK_TRANSITION_DURATION_MS) {
        val current = (animator?.animatedValue as? Float) ?: state.zoomOut
        if (current == LOCKSCREEN_RESTING_ZOOM) {
            clear()
            return
        }
        animate(current, LOCKSCREEN_RESTING_ZOOM, durationMs, "unlockTransition")
    }

    private fun animate(from: Float, to: Float, durationMs: Long, reason: String) {
        val startVal = (animator?.animatedValue as? Float) ?: from
        stop()
        if (startVal == to) {
            apply(to, reason)
            return
        }

        val distance = abs(to - startVal).coerceIn(0f, 1f)
        val scaledDuration = (durationMs * distance.coerceAtLeast(0.85f)).toLong().coerceAtLeast(500L)

        animator =
            ValueAnimator.ofFloat(startVal, to).apply {
                duration = scaledDuration
                interpolator = Interpolators.FAST_OUT_SLOW_IN
                addUpdateListener { apply(it.animatedValue as Float, "animating:$reason") }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (animator == animation) {
                                animator = null
                                apply(to, "animEnd:$reason")
                            }
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            if (animator == animation) {
                                animator = null
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

    private fun apply(value: Float, reason: String = "") {
        if (state.zoomOut == value && animator == null) {
            return
        }
        wallpaperZoomController.setZoom(WallpaperZoomOwner.KEYGUARD_WAKE_ANIM, value, reason)
        state = state.copy(zoomOut = value)
    }

    private fun canUseWallpaper(): Boolean =
        !wallpaperZoomController.wallpaperZoomDisabled &&
            wallpaperRepository.lockscreenWallpaperInfo.value == null

    private data class AnimationState(
        val zoomOut: Float = LOCKSCREEN_RESTING_ZOOM,
        val isAodWallpaper: Boolean = false,
        val isPrepped: Boolean = false,
        val hasRevealed: Boolean = false,
    ) {
        val isHolding: Boolean
            get() = zoomOut != LOCKSCREEN_RESTING_ZOOM
    }

    companion object {
        private const val FULL_AOD_SYNC_DELAY_MS = 240L
        private const val TO_AOD_DURATION_MS = 500L
        private const val REVEAL_START_THRESHOLD = 0.1f
        private const val REVEAL_DURATION_MS = 800L
        private const val UNLOCK_TRANSITION_DURATION_MS = 650L
        const val WAKE_START_ZOOM = 0f
        const val LOCKSCREEN_RESTING_ZOOM = 1f
    }
}
