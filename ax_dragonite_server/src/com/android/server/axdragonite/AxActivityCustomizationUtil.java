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

package com.android.server.axdragonite;

import com.android.internal.dragonite.AxDragoniteConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * @hide
 */
public final class AxActivityCustomizationUtil {
    public static final String PKG_CAMERA2 = "com.android.camera2";
    public static final String PKG_GCAM = "com.google.android.GoogleCamera";
    public static final String PKG_PHOTOS = "com.google.android.apps.photos";
    public static final String PKG_LAUNCHER3 = "com.android.launcher3";
    public static final String PKG_NOTHING_LAUNCHER = "com.nothing.launcher";

    public static final String KEYWORD_OVERLAY = "overlay";
    public static final String KEYWORD_MINUS_ONE = "minus_one";
    public static final String KEYWORD_SHELF = "shelf";

    public static final int DURATION_CAMERA_LAUNCH_MS = 1500;
    public static final int DURATION_PHOTOS_LAUNCH_MS = 1200;
    public static final int DURATION_LAUNCHER_FLING_MS = 600;
    public static final int FLING_VELOCITY_FAST_THRESHOLD = 2000;

    private static final Map<String, Integer> sLaunchDurationOverrides = new HashMap<>();
    private static final Map<String, Integer> sFlingDurationOverrides = new HashMap<>();

    static {
        sLaunchDurationOverrides.put(PKG_CAMERA2, DURATION_CAMERA_LAUNCH_MS);
        sLaunchDurationOverrides.put(PKG_GCAM, DURATION_CAMERA_LAUNCH_MS);
        sLaunchDurationOverrides.put(PKG_PHOTOS, DURATION_PHOTOS_LAUNCH_MS);

        sFlingDurationOverrides.put(PKG_LAUNCHER3, DURATION_LAUNCHER_FLING_MS);
        sFlingDurationOverrides.put(PKG_NOTHING_LAUNCHER, DURATION_LAUNCHER_FLING_MS);
    }

    public static int getLaunchDuration(String pkg, int defaultDuration) {
        if (pkg == null) {
            return defaultDuration;
        }
        Integer override = sLaunchDurationOverrides.get(pkg);
        return override != null ? override : defaultDuration;
    }

    public static int getFlingDuration(String pkg, int defaultDuration) {
        if (pkg == null) {
            return defaultDuration;
        }
        Integer override = sFlingDurationOverrides.get(pkg);
        return override != null ? override : defaultDuration;
    }

    public static int getFlingSceneId(int velocity) {
        if (Math.abs(velocity) > FLING_VELOCITY_FAST_THRESHOLD) {
            return AxDragoniteConstants.SCENE_FLING_LEVEL_1;
        }
        return AxDragoniteConstants.SCENE_FLING;
    }

    public static void handleActivityResumed(int pid, String pkg, String component) {
    }

    public static boolean isNegativeScreen(String activityName) {
        if (activityName == null) {
            return false;
        }
        return activityName.contains(KEYWORD_OVERLAY)
                || activityName.contains(KEYWORD_MINUS_ONE)
                || activityName.contains(KEYWORD_SHELF);
    }
}
