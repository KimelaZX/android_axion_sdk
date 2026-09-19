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

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Slog;

import com.android.internal.dragonite.AxDragoniteConstants;
import static com.android.internal.dragonite.AxDragoniteConstants.*;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public final class AxDragonite {
    private static final String TAG = AxDragoniteConstants.TAG;

    public static final int SCENE_FLING = AxDragoniteConstants.SCENE_FLING;
    public static final int SCENE_SCROLL = AxDragoniteConstants.SCENE_SCROLL;
    public static final int SCENE_APP_LAUNCH_COLD = AxDragoniteConstants.SCENE_APP_LAUNCH_COLD;
    public static final int SCENE_APP_LAUNCH_WARM = AxDragoniteConstants.SCENE_APP_LAUNCH_WARM;
    public static final int SCENE_ROTATION = AxDragoniteConstants.SCENE_ROTATION;
    public static final int SCENE_RECENT_TASK_SLIDE = AxDragoniteConstants.SCENE_RECENT_TASK_SLIDE;
    public static final int SCENE_CAMERA_OPEN = AxDragoniteConstants.SCENE_CAMERA_OPEN;
    public static final int SCENE_GAME_MODE = AxDragoniteConstants.SCENE_GAME_MODE;
    public static final int SCENE_AX_APP_START = AxDragoniteConstants.SCENE_AX_APP_START;
    public static final int SCENE_AX_FLING = AxDragoniteConstants.SCENE_AX_FLING;

    public static final int BOOST_LEVEL_NONE = AxDragoniteConstants.BOOST_LEVEL_NONE;
    public static final int BOOST_LEVEL_LIGHT = AxDragoniteConstants.BOOST_LEVEL_LIGHT;
    public static final int BOOST_LEVEL_HEAVY = AxDragoniteConstants.BOOST_LEVEL_HEAVY;

    private static AxDragonite sInstance;

    private final AxCpuClusterManager mClusterManager;
    private final AxPerfEnhancer mPerfEnhancer;
    private final AxNamedThreadAffinityFeature mAffinityFeature;
    private final AxUIBooster mUIBooster;
    private final AxFrameInsertManager mFrameInsertManager;
    private final AxPerfTraceManager mTraceManager;
    private final AxBoostAdjuster mBoostAdjuster;

    private final AxSceneRegistry mSceneRegistry;
    private final AxProcessTracker mProcessTracker;
    private final AxBoostSessionManager mSessionManager;
    private final AxOpcodeDispatcher mOpcodeDispatcher;

    private final HandlerThread mWorkerThread;
    private final Handler mWorkerHandler;

    private int mGameModeHandle = 0;

    public static synchronized AxDragonite getInstance() {
        if (sInstance == null) {
            sInstance = new AxDragonite();
        }
        return sInstance;
    }

    private AxDragonite() {
        mClusterManager = AxCpuClusterManager.getInstance();
        mPerfEnhancer = new AxPerfEnhancer(mClusterManager);
        mAffinityFeature = new AxNamedThreadAffinityFeature(mClusterManager);
        mUIBooster = new AxUIBooster(mPerfEnhancer, mClusterManager);
        mFrameInsertManager = new AxFrameInsertManager();
        mTraceManager = new AxPerfTraceManager();
        mBoostAdjuster = new AxBoostAdjuster(mPerfEnhancer, mClusterManager);

        mSceneRegistry = new AxSceneRegistry();
        mProcessTracker = new AxProcessTracker();
        mSessionManager = new AxBoostSessionManager();
        mOpcodeDispatcher = new AxOpcodeDispatcher(mPerfEnhancer, mBoostAdjuster, mAffinityFeature);

        mWorkerThread = new HandlerThread(WORKER_THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        mWorkerThread.start();
        try {
            Process.setThreadScheduler(mWorkerThread.getThreadId(), BOOST_SCHED_POLICY, BOOST_SCHED_PRIORITY);
        } catch (Throwable ignored) {
        }
        mWorkerHandler = new Handler(mWorkerThread.getLooper());

        Slog.i(TAG, "AxDragonite subsystem initialized");
    }

    public int sceneBoostAcquire(int sceneId, Bundle data) {
        AxSceneRegistry.ScenarioConfig config = mSceneRegistry.getConfig(sceneId);

        int targetPid = INVALID_PID;
        String pkgName = null;
        int customDuration = config.defaultTimeoutMs;

        if (data != null) {
            targetPid = data.getInt(KEY_TARGET_PID, data.getInt(KEY_PID, INVALID_PID));
            pkgName = data.getString(KEY_PACKAGE, data.getString(KEY_PACKAGE_NAME, data.getString(KEY_PKG, null)));
            int reqDuration = data.getInt(KEY_DURATION, INVALID_DURATION);
            if (reqDuration > 0) {
                customDuration = reqDuration;
            }
            int reqHandle = data.getInt(KEY_HANDLE, INVALID_HANDLE);
            if (reqHandle > 0 && mSessionManager.extendSession(reqHandle, customDuration)) {
                return reqHandle;
            }
        }
        if (targetPid <= 0) {
            targetPid = Binder.getCallingPid();
        }

        customDuration = mSceneRegistry.resolveDuration(sceneId, pkgName, customDuration);

        final int finalTargetPid = targetPid;
        final String finalPkgName = pkgName;
        final String params = data != null ? data.getString(KEY_PARAMS) : null;

        int handle = mSessionManager.startSession(
                sceneId, Binder.getCallingPid(), Binder.getCallingUid(),
                pkgName, targetPid, config, customDuration,
                this::sceneBoostRelease
        );

        mWorkerHandler.post(() -> {
            mTraceManager.reportSceneAcquire(sceneId, handle);
            mFrameInsertManager.onSceneStart(sceneId);

            mSessionManager.forEachActiveSession(s -> {
                if (s.handle == handle) {
                    mOpcodeDispatcher.parseAndApply(params, s);
                }
            });

            mPerfEnhancer.applyCpuBoost(config.boostLevel);
            if (mSceneRegistry.isTransitionScene(sceneId)) {
                applyAnimationBoost(finalTargetPid, config.boostLevel, true);
            } else if (config.boostRenderThread && finalTargetPid > 0) {
                mUIBooster.boostProcess(finalTargetPid, config.boostLevel);
                mAffinityFeature.applyNamedAffinityForPid(finalTargetPid);
            }

            if (config.pinKswapd) {
                mPerfEnhancer.pinKswapd(mClusterManager.getLittleMask());
            }

            if (sceneId == SCENE_APP_LAUNCH_COLD || sceneId == SCENE_CAMERA_OPEN || sceneId == SCENE_AX_APP_START) {
                Set<Integer> exempt = new HashSet<>();
                if (finalTargetPid > 0) exempt.add(finalTargetPid);
                int lPid = mProcessTracker.getLauncherPid();
                int sPid = mProcessTracker.getSystemUiPid();
                if (lPid > 0) exempt.add(lPid);
                if (sPid > 0) exempt.add(sPid);
                mBoostAdjuster.freezeBackgroundProcesses(true, exempt);
            }

            if (sceneId == SCENE_GAME_MODE) {
                applyGameMode(true);
            } else if (sceneId == SCENE_CAMERA_OPEN) {
                mPerfEnhancer.limitAxForeground(true);
            }

            if (mSceneRegistry.isSurfaceFlingerBoostScene(sceneId) || config.boostRenderThread) {
                mPerfEnhancer.sendSurfaceFlingerBoost(true);
            }
        });

        return handle;
    }

    public void sceneBoostRelease(int handle) {
        AxBoostSessionManager.BoostSession session = mSessionManager.endSession(handle);
        if (session == null) {
            return;
        }

        mWorkerHandler.post(() -> {
            mTraceManager.reportSceneRelease(session.sceneId, handle);
            mFrameInsertManager.onSceneEnd(session.sceneId);

            if (mSceneRegistry.isTransitionScene(session.sceneId)) {
                applyAnimationBoost(session.targetPid, session.config.boostLevel, false);
            } else if (session.config.boostRenderThread && session.targetPid > 0) {
                mUIBooster.restoreProcess(session.targetPid);
                mAffinityFeature.resetAffinityForPid(session.targetPid);
            }

            for (int tid : session.boostedTids) {
                try {
                    Process.setThreadScheduler(tid, Process.SCHED_OTHER, 0);
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_DEFAULT);
                    Process.setThreadAffinity(tid, (int) mClusterManager.getAllMask());
                } catch (Throwable ignored) {
                }
            }

            if (session.sceneId == SCENE_APP_LAUNCH_COLD || session.sceneId == SCENE_CAMERA_OPEN || session.sceneId == SCENE_AX_APP_START) {
                mBoostAdjuster.freezeBackgroundProcesses(false);
            }

            if (session.sceneId == SCENE_GAME_MODE) {
                applyGameMode(false);
            } else if (session.sceneId == SCENE_CAMERA_OPEN) {
                mPerfEnhancer.limitAxForeground(false);
            }

            int maxBoostLevel = mSessionManager.getActiveMaxBoostLevel();
            if (maxBoostLevel > BOOST_LEVEL_NONE) {
                mPerfEnhancer.applyCpuBoost(maxBoostLevel);
            } else {
                mPerfEnhancer.restoreCpuBoost();
            }

            if (!mSessionManager.hasActivePinKswapd()) {
                mPerfEnhancer.pinKswapd(mClusterManager.getAllMask());
            }

            if (!mSessionManager.hasActiveSurfaceFlingerBoost(mSceneRegistry)) {
                mPerfEnhancer.sendSurfaceFlingerBoost(false);
            }
        });
    }

    public void animationBoost(int pid, long boostDurationMs) {
        Bundle bundle = new Bundle();
        bundle.putInt(AxDragoniteConstants.KEY_PID, pid);
        bundle.putInt(AxDragoniteConstants.KEY_TARGET_PID, pid);
        bundle.putInt(AxDragoniteConstants.KEY_DURATION, (int) Math.min(boostDurationMs, AxDragoniteConstants.MAX_BOOST_DURATION_MS));
        sceneBoostAcquire(AxDragoniteConstants.SCENE_APP_EXIT_ANIM, bundle);
    }

    private void applyAnimationBoost(int targetPid, int boostLevel, boolean enable) {
        int sysUiPid = mProcessTracker.getSystemUiPid();
        int launcherPid = mProcessTracker.getLauncherPid();

        if (enable) {
            if (targetPid > 0) {
                mUIBooster.boostProcess(targetPid, boostLevel);
                mAffinityFeature.applyNamedAffinityForPid(targetPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(targetPid, true);
            }
            if (sysUiPid > 0 && sysUiPid != targetPid) {
                mUIBooster.boostProcess(sysUiPid, boostLevel);
                mAffinityFeature.applyNamedAffinityForPid(sysUiPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(sysUiPid, true);
            }
            if (launcherPid > 0 && launcherPid != targetPid) {
                mUIBooster.boostProcess(launcherPid, boostLevel);
                mAffinityFeature.applyNamedAffinityForPid(launcherPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(launcherPid, true);
            }
        } else {
            if (targetPid > 0) {
                mUIBooster.restoreProcess(targetPid);
                mAffinityFeature.resetAffinityForPid(targetPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(targetPid, false);
            }
            if (sysUiPid > 0 && sysUiPid != targetPid) {
                mUIBooster.restoreProcess(sysUiPid);
                mAffinityFeature.resetAffinityForPid(sysUiPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(sysUiPid, false);
            }
            if (launcherPid > 0 && launcherPid != targetPid) {
                mUIBooster.restoreProcess(launcherPid);
                mAffinityFeature.resetAffinityForPid(launcherPid);
                mBoostAdjuster.migrateToRestrictedCpuctl(launcherPid, false);
            }
        }
    }

    public boolean isSceneIdExist(int sceneId) {
        return mSceneRegistry.isSceneIdExist(sceneId);
    }

    public int getFlingSceneId(int velocity) {
        return mSceneRegistry.getFlingSceneId(velocity);
    }

    public int getFlingSceneId() {
        return getFlingSceneId(DEFAULT_FLING_VELOCITY);
    }

    public int getScrollSceneId() {
        return SCENE_SCROLL;
    }

    public int getAppLaunchSceneId() {
        return SCENE_APP_LAUNCH_COLD;
    }

    public void adjustCpusetCpus(String group, String cpus, long durationMs) {
        if ("dex2oat".equals(group) && cpus == null) {
            applyDex2oatCpusetAdjustment(durationMs);
            return;
        }
        if (group == null || !ALLOWED_CPUSET_GROUPS.contains(group)) {
            return;
        }
        if (cpus != null && !CPUSET_CPUS_PATTERN.matcher(cpus).matches()) {
            return;
        }
        mBoostAdjuster.adjustCpusetCpus(group, cpus, durationMs);
    }

    private void applyDex2oatCpusetAdjustment(long durationMs) {
        if (durationMs == 0L) {
            mPerfEnhancer.writeCpusetCpus("dex2oat", mClusterManager.getRestrictedDex2oatCpusString());
            return;
        }
        if (durationMs == -1L) {
            mPerfEnhancer.writeCpusetCpus("dex2oat", mClusterManager.getBackgroundCpusString());
        }
    }

    public void onProcessForked(int pid, boolean isTopApp) {
        if (isTopApp) {
            mPerfEnhancer.migrateToTopAppCgroup(pid);
        }
    }

    public void inputBoost() {
        mWorkerHandler.post(mBoostAdjuster::inputBoost);
    }

    public void onProcessStarted(int pid, String pkg, String processName, int uid) {
        mWorkerHandler.post(() -> {
            mProcessTracker.onProcessStarted(pid, pkg, processName);
            mBoostAdjuster.onProcessStarted(pid, pkg, processName, uid);
            mAffinityFeature.applyNamedAffinityForPid(pid);

            mSessionManager.forEachActiveSession(s -> {
                if (mSceneRegistry.isTransitionScene(s.sceneId) && pkg != null && pkg.equals(s.packageName)) {
                    mUIBooster.boostProcess(pid, s.config.boostLevel);
                }
            });
        });
    }

    public void onProcessKilled(int pid, String pkg) {
        mWorkerHandler.post(() -> {
            mProcessTracker.onProcessKilled(pid);
            mBoostAdjuster.onProcessKilled(pid, pkg);
            mUIBooster.restoreProcess(pid);
            mAffinityFeature.resetAffinityForPid(pid);
        });
    }

    public void onActivityStart(String pkg, String component, int uid, boolean isCold) {
        mWorkerHandler.post(() -> {
            mBoostAdjuster.onActivityStart(pkg, component, uid, isCold);
            boolean isCamera = pkg != null && pkg.toLowerCase().contains(KEYWORD_CAMERA);
            int scene = isCamera ? SCENE_CAMERA_OPEN : (isCold ? SCENE_APP_LAUNCH_COLD : SCENE_APP_LAUNCH_WARM);
            Bundle bundle = new Bundle();
            bundle.putString(KEY_PKG, pkg);
            bundle.putString(KEY_PACKAGE_NAME, pkg);
            bundle.putBoolean(KEY_IS_COLD, isCold);
            bundle.putInt(KEY_UID, uid);
            sceneBoostAcquire(scene, bundle);
        });
    }

    public void onSetFocusedApp(String pkg) {
        mProcessTracker.onSetFocusedApp(pkg);
    }

    public String getFocusedPackage() {
        return mProcessTracker.getFocusedPackage();
    }

    public void onReportResumedActivity(int pid, String pkg, String component) {
        mWorkerHandler.post(() -> {
            AxActivityCustomizationUtil.handleActivityResumed(pid, pkg, component);
            mBoostAdjuster.onReportResumedActivity(pid, pkg, component);
        });
    }

    public void onAppDied(String pkg, String component, int uid) {
        mWorkerHandler.post(() -> {
            mBoostAdjuster.onAppDied(pkg, component, uid);
        });
    }

    public void onSetVisibility(String pkg, String component, int uid, boolean visible) {
        mWorkerHandler.post(() -> {
            mBoostAdjuster.onSetVisibility(pkg, component, uid, visible);
        });
    }

    public int onSystemFling(int duration) {
        return onSystemFling(duration, mProcessTracker.getFocusedPackage());
    }

    public int onSystemFling(int duration, String pkg) {
        String targetPkg = pkg != null ? pkg : mProcessTracker.getFocusedPackage();
        Bundle bundle = new Bundle();
        bundle.putInt(AxDragoniteConstants.KEY_DURATION, duration);
        if (targetPkg != null) {
            bundle.putString(AxDragoniteConstants.KEY_PKG, targetPkg);
            bundle.putString(AxDragoniteConstants.KEY_PACKAGE_NAME, targetPkg);
        }
        return sceneBoostAcquire(SCENE_FLING, bundle);
    }

    public synchronized void updateGameModeBoost(boolean enable) {
        if (enable) {
            if (mGameModeHandle <= 0) {
                mGameModeHandle = sceneBoostAcquire(SCENE_GAME_MODE, null);
            }
        } else {
            if (mGameModeHandle > 0) {
                sceneBoostRelease(mGameModeHandle);
                mGameModeHandle = 0;
            }
        }
    }

    private void applyGameMode(boolean enabled) {
        mPerfEnhancer.limitAxForeground(enabled);
        if (enabled) {
            mPerfEnhancer.applyCpuBoost(BOOST_LEVEL_HEAVY);
            return;
        }
        mPerfEnhancer.restoreCpuBoost();
    }

    public void dump(PrintWriter pw) {
        pw.println("AxDragonite Subsystem State:");
        pw.println("  Cores: " + mClusterManager.getNumCores() + ", Clusters: " + mClusterManager.getNumClusters());
        pw.println("  Little: 0x" + Long.toHexString(mClusterManager.getLittleMask()));
        pw.println("  Big: 0x" + Long.toHexString(mClusterManager.getBigMask()));
        pw.println("  Prime: 0x" + Long.toHexString(mClusterManager.getPrimeMask()));
        pw.println("  Boost: 0x" + Long.toHexString(mClusterManager.getBoostMask()));
        pw.println("  Launcher PID: " + mProcessTracker.getLauncherPid() + ", SystemUI PID: " + mProcessTracker.getSystemUiPid());
        mSessionManager.dump(pw);
    }
}
