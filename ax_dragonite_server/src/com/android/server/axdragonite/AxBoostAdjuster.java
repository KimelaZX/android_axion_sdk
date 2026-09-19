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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.util.Slog;

import java.util.Set;

/**
 * @hide
 */
public final class AxBoostAdjuster {
    private static final String TAG = "AxBoostAdjuster";

    public static final String THREAD_NAME_ADJUSTER = "AxBoostAdjusterTimer";

    public static final int BOOST_LEVEL_NONE = 0;
    public static final int BOOST_LEVEL_LIGHT = 1;
    public static final int BOOST_LEVEL_HEAVY = 2;

    public static final int SCHED_NORMAL = 0;
    public static final int SCHED_RR_RESET_ON_FORK = Process.SCHED_RR | Process.SCHED_RESET_ON_FORK;
    public static final int SCHED_REALTIME_PRIO = 1;
    public static final int SCHED_DEFAULT_PRIO = 0;

    public static final long DEFAULT_ANIMATION_BOOST_MS = 500L;
    public static final long MIN_BOOST_DURATION_MS = 50L;
    public static final long MAX_BOOST_DURATION_MS = 5000L;
    public static final long DURATION_INPUT_BOOST_MS = 800L;
    public static final long DURATION_COLD_START_RESTRICT_MS = 1000L;
    public static final long DURATION_ZERO_MS = 0L;

    public static final int THERMAL_THRESHOLD_THROTTLE = PowerManager.THERMAL_STATUS_SEVERE;
    public static final int PERCENT_THROTTLE_FACTOR = 50;
    public static final int PERCENT_FULL = 100;
    public static final int INVALID_PID = 0;

    private final AxPerfEnhancer mPerfEnhancer;
    private final AxCpuClusterManager mClusterManager;
    private final AxFreezerController mFreezerController;
    private final HandlerThread mTimerThread;
    private final Handler mTimerHandler;

    private final Runnable mRestoreInputBoostRunnable;
    private boolean mInputBoostActive = false;

    public AxBoostAdjuster(AxPerfEnhancer perfEnhancer, AxCpuClusterManager clusterManager) {
        this.mPerfEnhancer = perfEnhancer;
        this.mClusterManager = clusterManager;
        this.mFreezerController = new AxFreezerController();
        this.mRestoreInputBoostRunnable = this::restoreInputBoost;

        mTimerThread = new HandlerThread(THREAD_NAME_ADJUSTER, Process.THREAD_PRIORITY_URGENT_DISPLAY);
        mTimerThread.start();
        mTimerHandler = new Handler(mTimerThread.getLooper());
    }

    public void setThreadAffinity(int tid, int affinityType) {
        if (tid <= 0) return;
        long mask = mClusterManager.getMaskForType(affinityType);
        if (mask == 0) return;
        try {
            Process.setThreadAffinity(tid, (int) mask);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to set thread affinity for tid " + tid + " mask 0x" + Long.toHexString(mask) + ": " + t.getMessage());
        }
    }

    public void adjustCpusetCpus(String group, String cpus, long durationMs) {
        if (group == null || cpus == null) {
            return;
        }

        String path = AxPerfEnhancer.PATH_DEV_CPUSET_PREFIX + group + AxPerfEnhancer.PATH_CPUS_SUFFIX;
        String prev = AxPerfEnhancer.readNode(path);
        mPerfEnhancer.writeCpusetCpus(group, cpus);

        if (durationMs > DURATION_ZERO_MS && prev != null) {
            mTimerHandler.postDelayed(() -> {
                mPerfEnhancer.writeCpusetCpus(group, prev);
            }, durationMs);
        }
    }

    private synchronized void restoreInputBoost() {
        mInputBoostActive = false;
        mPerfEnhancer.limitAxForeground(false);
        mPerfEnhancer.restrictBackgroundCpusets(false);
    }

    public void inputBoost() {
        synchronized (this) {
            if (mInputBoostActive) {
                mTimerHandler.removeCallbacks(mRestoreInputBoostRunnable);
                mTimerHandler.postDelayed(mRestoreInputBoostRunnable, DURATION_INPUT_BOOST_MS);
                return;
            }
            mInputBoostActive = true;
            mPerfEnhancer.limitAxForeground(true);
            mPerfEnhancer.restrictBackgroundCpusets(true);
            mTimerHandler.postDelayed(mRestoreInputBoostRunnable, DURATION_INPUT_BOOST_MS);
        }
    }

    public void onProcessStarted(int pid, String pkg, String processName, int uid) {
        if (pid <= INVALID_PID) {
            return;
        }
        mPerfEnhancer.setTaskBoost(pid, BOOST_LEVEL_LIGHT);
    }

    public void onProcessKilled(int pid, String pkg) {
        if (pid <= INVALID_PID) {
            return;
        }
        mFreezerController.unfreezeApp(-1, pid);
    }

    public void onActivityStart(String pkg, String component, int uid, boolean isCold) {
        if (isCold) {
            mPerfEnhancer.restrictBackgroundCpusets(true);
            mFreezerController.freezeBackgroundProcesses(true);
            mTimerHandler.postDelayed(() -> {
                mPerfEnhancer.restrictBackgroundCpusets(false);
                mFreezerController.freezeBackgroundProcesses(false);
            }, DURATION_COLD_START_RESTRICT_MS);
        }
    }

    public void freezeBackgroundProcesses(boolean freeze) {
        mFreezerController.freezeBackgroundProcesses(freeze, null);
    }

    public void freezeBackgroundProcesses(boolean freeze, Set<Integer> exemptPids) {
        mFreezerController.freezeBackgroundProcesses(freeze, exemptPids);
    }

    public void freezeApp(int uid, int pid) {
        mFreezerController.freezeApp(uid, pid);
    }

    public void unfreezeApp(int uid, int pid) {
        mFreezerController.unfreezeApp(uid, pid);
    }

    public void migrateToRestrictedCpuctl(int pid, boolean enable) {
        mPerfEnhancer.migrateToRestrictedCpuctl(pid, enable);
    }

    public void onReportResumedActivity(int pid, String pkg, String component) {
        mPerfEnhancer.restrictBackgroundCpusets(true);
        mTimerHandler.postDelayed(() -> {
            mPerfEnhancer.restrictBackgroundCpusets(false);
        }, 500L);
    }

    public void onAppDied(String pkg, String component, int uid) {
    }

    public void onSetVisibility(String pkg, String component, int uid, boolean visible) {
    }
}
