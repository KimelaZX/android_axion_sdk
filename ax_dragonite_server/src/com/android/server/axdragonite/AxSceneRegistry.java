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

import android.util.SparseArray;
import com.android.internal.dragonite.AxDragoniteConstants;
import static com.android.internal.dragonite.AxDragoniteConstants.*;

/**
 * @hide
 */
public final class AxSceneRegistry {

    public static final class ScenarioConfig {
        public final int sceneId;
        public final int defaultTimeoutMs;
        public final int boostLevel;
        public final boolean boostRenderThread;
        public final boolean pinKswapd;

        public ScenarioConfig(int id, int timeout, int level, boolean boostRt, boolean pinKs) {
            this.sceneId = id;
            this.defaultTimeoutMs = timeout;
            this.boostLevel = level;
            this.boostRenderThread = boostRt;
            this.pinKswapd = pinKs;
        }
    }

    private final SparseArray<ScenarioConfig> mScenarios = new SparseArray<>();

    public AxSceneRegistry() {
        initScenarios();
    }

    private void initScenarios() {
        mScenarios.put(SCENE_AX_APP_START, new ScenarioConfig(SCENE_AX_APP_START, DURATION_AX_APP_START_MS, BOOST_LEVEL_HEAVY, true, true));
        mScenarios.put(SCENE_FLING, new ScenarioConfig(SCENE_FLING, DURATION_FLING_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_SCROLL, new ScenarioConfig(SCENE_SCROLL, DURATION_SCROLL_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_DATA_LOADING, new ScenarioConfig(SCENE_DATA_LOADING, DURATION_DEFAULT_FALLBACK_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_FOLDER_ANIMATION, new ScenarioConfig(SCENE_FOLDER_ANIMATION, DURATION_DEFAULT_FALLBACK_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_DRAG_AND_DROP, new ScenarioConfig(SCENE_DRAG_AND_DROP, DURATION_DEFAULT_FALLBACK_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_AX_NOTIFICATION_EXPAND, new ScenarioConfig(SCENE_AX_NOTIFICATION_EXPAND, DURATION_AX_NOTIFICATION_EXPAND_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_AX_UNLOCK, new ScenarioConfig(SCENE_AX_UNLOCK, DURATION_AX_UNLOCK_MS, BOOST_LEVEL_HEAVY, true, true));
        mScenarios.put(SCENE_AX_SYSTEMUI_ANIMATION, new ScenarioConfig(SCENE_AX_SYSTEMUI_ANIMATION, DURATION_AX_SYSTEMUI_ANIMATION_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_APP_LAUNCH_COLD, new ScenarioConfig(SCENE_APP_LAUNCH_COLD, DURATION_APP_LAUNCH_COLD_MS, BOOST_LEVEL_HEAVY, true, true));
        mScenarios.put(SCENE_APP_LAUNCH_WARM, new ScenarioConfig(SCENE_APP_LAUNCH_WARM, DURATION_APP_LAUNCH_WARM_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_APP_EXIT_ANIM, new ScenarioConfig(SCENE_APP_EXIT_ANIM, DURATION_APP_EXIT_ANIM_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_ROTATION, new ScenarioConfig(SCENE_ROTATION, DURATION_ROTATION_MS, BOOST_LEVEL_LIGHT, true, false));
        mScenarios.put(SCENE_CAMERA_OPEN, new ScenarioConfig(SCENE_CAMERA_OPEN, DURATION_CAMERA_OPEN_MS, BOOST_LEVEL_HEAVY, true, true));
        mScenarios.put(SCENE_CAMERA_CAPTURE, new ScenarioConfig(SCENE_CAMERA_CAPTURE, DURATION_CAMERA_CAPTURE_MS, BOOST_LEVEL_HEAVY, false, false));
        mScenarios.put(SCENE_GAME_MODE, new ScenarioConfig(SCENE_GAME_MODE, 0, BOOST_LEVEL_HEAVY, true, true));
        mScenarios.put(SCENE_BIOMETRIC_UNLOCK, new ScenarioConfig(SCENE_BIOMETRIC_UNLOCK, DURATION_BIOMETRIC_UNLOCK_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_RECENT_TASK_SLIDE, new ScenarioConfig(SCENE_RECENT_TASK_SLIDE, DURATION_RECENT_TASK_SLIDE_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_QUICK_SWITCH_APP, new ScenarioConfig(SCENE_QUICK_SWITCH_APP, DURATION_QUICK_SWITCH_APP_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_FLING_LEVEL_1, new ScenarioConfig(SCENE_FLING_LEVEL_1, DURATION_FLING_MS, BOOST_LEVEL_HEAVY, true, false));
        mScenarios.put(SCENE_DISABLE_INPUT_BOOST, new ScenarioConfig(SCENE_DISABLE_INPUT_BOOST, DURATION_DEFAULT_FALLBACK_MS, BOOST_LEVEL_NONE, false, false));
    }

    public ScenarioConfig getConfig(int sceneId) {
        ScenarioConfig config = mScenarios.get(sceneId);
        if (config == null) {
            return new ScenarioConfig(sceneId, DURATION_DEFAULT_FALLBACK_MS, BOOST_LEVEL_LIGHT, true, false);
        }
        return config;
    }

    public boolean isSceneIdExist(int sceneId) {
        return mScenarios.indexOfKey(sceneId) >= 0;
    }

    public boolean isTransitionScene(int sceneId) {
        return sceneId == SCENE_APP_LAUNCH_COLD
                || sceneId == SCENE_APP_LAUNCH_WARM
                || sceneId == SCENE_APP_EXIT_ANIM
                || sceneId == SCENE_RECENT_TASK_SLIDE
                || sceneId == SCENE_QUICK_SWITCH_APP
                || sceneId == SCENE_FLING_LEVEL_1
                || sceneId == SCENE_AX_APP_START;
    }

    public boolean isSurfaceFlingerBoostScene(int sceneId) {
        return sceneId == SCENE_APP_LAUNCH_COLD
                || sceneId == SCENE_APP_LAUNCH_WARM
                || sceneId == SCENE_APP_EXIT_ANIM
                || sceneId == SCENE_RECENT_TASK_SLIDE
                || sceneId == SCENE_QUICK_SWITCH_APP
                || sceneId == SCENE_FLING
                || sceneId == SCENE_SCROLL
                || sceneId == SCENE_AX_FLING
                || sceneId == SCENE_FLING_LEVEL_1
                || sceneId == SCENE_AX_APP_START
                || sceneId == SCENE_FOLDER_ANIMATION
                || sceneId == SCENE_ROTATION
                || sceneId == SCENE_AX_NOTIFICATION_EXPAND
                || sceneId == SCENE_AX_UNLOCK;
    }

    public int resolveDuration(int sceneId, String pkgName, int customDuration) {
        if (sceneId == SCENE_APP_LAUNCH_COLD || sceneId == SCENE_APP_LAUNCH_WARM) {
            return AxActivityCustomizationUtil.getLaunchDuration(pkgName, customDuration);
        }
        if (sceneId == SCENE_FLING || sceneId == SCENE_FLING_LEVEL_1) {
            return AxActivityCustomizationUtil.getFlingDuration(pkgName, customDuration);
        }
        return customDuration;
    }

    public int getFlingSceneId(int velocity) {
        return AxActivityCustomizationUtil.getFlingSceneId(velocity);
    }
}
