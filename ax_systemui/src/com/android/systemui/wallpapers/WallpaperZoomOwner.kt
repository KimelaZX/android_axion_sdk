/*
 * Copyright (C) 2026 AxionOS
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

enum class WallpaperZoomOwner(
    val priority: Int,
    val description: String,
    val restingZoom: Float = 0f,
    val maxHoldingDurationMs: Long = DEFAULT_MAX_HOLDING_DURATION_MS,
) {
    KEYGUARD_WAKE_ANIM(
        priority = 50,
        description = "Keyguard Wake Reveal",
        restingZoom = 1.0f,
        maxHoldingDurationMs = 3_000L,
    ),

    NOTIFICATION_SHADE(
        priority = 40,
        description = "Notification Shade",
        restingZoom = 0.0f,
        maxHoldingDurationMs = 60_000L,
    ),

    UNFOLD(
        priority = 30,
        description = "Unfold Transition",
        restingZoom = 0.0f,
        maxHoldingDurationMs = 5_000L,
    ),

    LAUNCHER_ANIM(
        priority = 20,
        description = "Launcher App/Home Anim",
        restingZoom = 0.0f,
        maxHoldingDurationMs = 5_000L,
    ),

    SYSTEM_OVERRIDE(
        priority = 100,
        description = "System Override",
        restingZoom = 0.0f,
        maxHoldingDurationMs = 10_000L,
    );

    companion object {
        const val DEFAULT_MAX_HOLDING_DURATION_MS = 10_000L
    }
}
