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

package com.android.axion.dragonite

import android.app.ActivityManager
import android.app.IActivityManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.android.internal.dragonite.AxDragoniteConstants.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

object AxDragonite {
    private val activeHandles = ConcurrentHashMap<Int, Int>()

    val amService: IActivityManager by lazy { ActivityManager.getService() }

    @JvmOverloads
    @JvmStatic
    fun acquire(sceneId: Int, durationMs: Int = DEFAULT_TIMEOUT_MS, bundle: Bundle? = null): Int {
        val existingHandle = activeHandles[sceneId]
        if (existingHandle != null && existingHandle > MIN_VALID_HANDLE) {
            return existingHandle
        }
        if (sceneId == SCENE_ANIMATION && (activeHandles.containsKey(SCENE_NOTIFICATION_EXPAND) || activeHandles.containsKey(SCENE_UNLOCK))) {
            return activeHandles[SCENE_NOTIFICATION_EXPAND] ?: activeHandles[SCENE_UNLOCK] ?: 0
        }
        val data = bundle ?: Bundle().apply {
            val pid = Process.myPid()
            val tid = Process.myTid()
            putInt(KEY_PID, pid)
            putInt(KEY_TARGET_PID, pid)
            putInt(KEY_CALLING_PID, pid)
            putInt(KEY_DURATION, durationMs)
            putString(KEY_PARAMS, "$OPCODE_CPU_AFFINITY:$pid,$tid;$OPCODE_SCHED_PRIORITY:$pid;$OPCODE_BOOST_SCHED:$tid")
        }
        val handle = try {
            amService.sceneBoostAcquire(sceneId, data)
        } catch (t: Throwable) {
            Log.w(TAG, "acquire failed for scene $sceneId: ${t.message}")
            INVALID_HANDLE
        }
        if (handle <= MIN_VALID_HANDLE) return handle
        val oldHandle = activeHandles.put(sceneId, handle)
        if (oldHandle != null && oldHandle > MIN_VALID_HANDLE && oldHandle != handle) {
            releaseHandle(oldHandle)
        }
        return handle
    }

    @JvmStatic
    fun release(sceneId: Int) {
        val handle = activeHandles.remove(sceneId) ?: return
        releaseHandle(handle)
    }

    @JvmStatic
    fun releaseHandle(handle: Int) {
        if (handle <= MIN_VALID_HANDLE) return
        removeActiveHandle(handle)
        try {
            amService.sceneBoostRelease(handle)
        } catch (t: Throwable) {
            Log.w(TAG, "release failed for handle $handle: ${t.message}")
        }
    }

    private fun removeActiveHandle(handle: Int) {
        val entry = activeHandles.entries.firstOrNull { it.value == handle } ?: return
        activeHandles.remove(entry.key)
    }

    @JvmStatic
    fun isSceneIdExist(sceneId: Int): Boolean {
        try {
            return amService.isSceneIdExist(sceneId)
        } catch (t: Throwable) {
            return false
        }
    }

    private fun buildLaunchBundle(pkg: String?, durationMs: Int): Bundle {
        val pid = Process.myPid()
        val tid = Process.myTid()
        return Bundle().apply {
            putInt(KEY_PID, pid)
            putInt(KEY_TARGET_PID, pid)
            putInt(KEY_CALLING_PID, pid)
            putInt(KEY_DURATION, durationMs)
            putString(KEY_PARAMS, "$OPCODE_CPU_AFFINITY:$pid,$tid;$OPCODE_SCHED_PRIORITY:$pid;$OPCODE_BOOST_SCHED:$tid")
            if (pkg != null) {
                putString(KEY_PKG, pkg)
                putString(KEY_PACKAGE_NAME, pkg)
            }
        }
    }

    @JvmOverloads
    @JvmStatic
    fun onAppLaunch(pkg: String? = null, durationMs: Int = DURATION_APP_LAUNCH_MS): Int =
        acquire(SCENE_APP_LAUNCH_COLD, durationMs, buildLaunchBundle(pkg, durationMs))

    @JvmStatic
    fun onAppLaunchEnd(): Unit = release(SCENE_APP_LAUNCH_COLD)

    @JvmOverloads
    @JvmStatic
    fun onGestureStart(durationMs: Int = DURATION_GESTURE_MS): Int =
        acquire(SCENE_RECENT_TASK_SLIDE, durationMs, buildLaunchBundle(null, durationMs))

    @JvmStatic
    fun onGestureEnd(): Unit = release(SCENE_RECENT_TASK_SLIDE)

    @JvmOverloads
    @JvmStatic
    fun onBackHome(durationMs: Int = DURATION_BACK_HOME_MS): Int =
        acquire(SCENE_APP_EXIT_ANIM, durationMs, buildLaunchBundle(null, durationMs))

    @JvmStatic
    fun onBackHomeEnd(): Unit = release(SCENE_APP_EXIT_ANIM)

    @JvmOverloads
    @JvmStatic
    fun onQuickSwitch(durationMs: Int = DURATION_QUICK_SWITCH_MS): Int =
        acquire(SCENE_QUICK_SWITCH_APP, durationMs, buildLaunchBundle(null, durationMs))

    @JvmStatic
    fun onQuickSwitchEnd(): Unit = release(SCENE_QUICK_SWITCH_APP)

    @JvmOverloads
    @JvmStatic
    fun onFling(durationMs: Int = DURATION_FLING_MS): Int =
        acquire(SCENE_FLING, durationMs)

    @JvmStatic
    fun onFlingEnd(): Unit = release(SCENE_FLING)

    @JvmOverloads
    @JvmStatic
    fun onScroll(durationMs: Int = DURATION_SCROLL_MS): Int =
        acquire(SCENE_SCROLL, durationMs)

    @JvmStatic
    fun onScrollEnd(): Unit = release(SCENE_SCROLL)

    @JvmOverloads
    @JvmStatic
    fun onDataLoading(durationMs: Int = DEFAULT_TIMEOUT_MS): Int =
        acquire(SCENE_DATA_LOADING, durationMs)

    @JvmStatic
    fun onDataLoadingEnd(): Unit = release(SCENE_DATA_LOADING)

    @JvmOverloads
    @JvmStatic
    fun onDragAndDrop(durationMs: Int = DEFAULT_TIMEOUT_MS): Int =
        acquire(SCENE_DRAG_AND_DROP, durationMs)

    @JvmStatic
    fun onDragAndDropEnd(): Unit = release(SCENE_DRAG_AND_DROP)

    @JvmStatic
    fun onShadeExpand(): Int =
        acquire(SCENE_NOTIFICATION_EXPAND, DURATION_SHADE_EXPAND_MS)

    @JvmStatic
    fun onShadeCollapse(): Unit = release(SCENE_NOTIFICATION_EXPAND)

    @JvmStatic
    fun onUnlock(): Int =
        acquire(SCENE_UNLOCK, DURATION_UNLOCK_MS)

    @JvmStatic
    fun onUnlockEnd(): Unit = release(SCENE_UNLOCK)

    @JvmOverloads
    @JvmStatic
    fun onBiometricAuth(durationMs: Int = DURATION_BIOMETRIC_AUTH_MS): Int =
        acquire(SCENE_BIOMETRIC_UNLOCK, durationMs)

    @JvmStatic
    fun onBiometricAuthEnd(): Unit = release(SCENE_BIOMETRIC_UNLOCK)

    @JvmStatic
    fun onGameMode(enabled: Boolean): Int {
        return if (enabled) {
            acquire(SCENE_GAME_MODE, 0)
        } else {
            release(SCENE_GAME_MODE)
            0
        }
    }

    @JvmOverloads
    @JvmStatic
    fun onCameraOpen(durationMs: Int = DURATION_CAMERA_OPEN_MS): Int =
        acquire(SCENE_CAMERA_OPEN, durationMs)

    @JvmStatic
    fun onCameraOpenEnd(): Unit = release(SCENE_CAMERA_OPEN)

    @JvmOverloads
    @JvmStatic
    fun onCameraCapture(durationMs: Int = DURATION_CAMERA_CAPTURE_MS): Int =
        acquire(SCENE_CAMERA_CAPTURE, durationMs)

    @JvmStatic
    fun onCameraCaptureEnd(): Unit = release(SCENE_CAMERA_CAPTURE)

    @JvmStatic
    fun onNotificationStackScroll(): Int =
        acquire(SCENE_ANIMATION, DURATION_ANIMATION_MS)

    @JvmStatic
    fun onNotificationStackScrollEnd(): Unit = release(SCENE_ANIMATION)

    @JvmStatic
    fun onDozeTransition(): Int =
        acquire(SCENE_ANIMATION, DURATION_DOZE_MS)

    @JvmStatic
    fun onDozeTransitionEnd(): Unit = release(SCENE_ANIMATION)

    @JvmStatic
    fun onLightReveal(): Int =
        acquire(SCENE_ANIMATION, DURATION_LIGHT_REVEAL_MS)

    @JvmStatic
    fun onLightRevealEnd(): Unit = release(SCENE_ANIMATION)

    @JvmStatic
    fun onLightRevealAmount(value: Float) {
        if (value in 0.001f..0.999f) {
            onLightReveal()
        } else {
            onLightRevealEnd()
        }
    }

    @JvmStatic
    fun onVolumeDialog(): Int =
        acquire(SCENE_ANIMATION, DURATION_VOLUME_DIALOG_MS)

    @JvmStatic
    fun onVolumeDialogEnd(): Unit = release(SCENE_ANIMATION)

    @JvmStatic
    fun onKeyguardDismiss(): Int = onUnlock()

    @JvmStatic
    fun onKeyguardDismissEnd(): Unit = onUnlockEnd()

    @JvmStatic
    fun onWakeUp(): Int = onUnlock()

    @JvmStatic
    fun onWakeUpEnd(): Unit = onUnlockEnd()
}
