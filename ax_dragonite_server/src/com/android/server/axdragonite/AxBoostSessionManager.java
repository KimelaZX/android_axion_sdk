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
import android.os.Process;
import android.util.SparseArray;

import com.android.internal.dragonite.AxDragoniteConstants;
import static com.android.internal.dragonite.AxDragoniteConstants.*;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * @hide
 */
public final class AxBoostSessionManager {

    public static final class BoostSession {
        public final int handle;
        public final int sceneId;
        public final int callingPid;
        public final int callingUid;
        public final String packageName;
        public final long acquireTimeMs;
        public final int targetPid;
        public final AxSceneRegistry.ScenarioConfig config;
        public final Runnable timeoutRunnable;
        public final Set<Integer> boostedTids = new HashSet<>();

        public BoostSession(int handle, int sceneId, int callingPid, int callingUid,
                            String pkg, long acquireTime, int targetPid,
                            AxSceneRegistry.ScenarioConfig config, Runnable timeoutRunnable) {
            this.handle = handle;
            this.sceneId = sceneId;
            this.callingPid = callingPid;
            this.callingUid = callingUid;
            this.packageName = pkg;
            this.acquireTimeMs = acquireTime;
            this.targetPid = targetPid;
            this.config = config;
            this.timeoutRunnable = timeoutRunnable;
        }
    }

    private final Object mLock = new Object();
    private final AtomicInteger mNextHandle = new AtomicInteger(INITIAL_HANDLE_VALUE);
    private final SparseArray<BoostSession> mActiveSessions = new SparseArray<>();

    private final HandlerThread mTimerThread;
    private final Handler mTimerHandler;

    public AxBoostSessionManager() {
        mTimerThread = new HandlerThread(TIMER_THREAD_NAME, Process.THREAD_PRIORITY_URGENT_DISPLAY);
        mTimerThread.start();
        try {
            Process.setThreadScheduler(mTimerThread.getThreadId(), BOOST_SCHED_POLICY, BOOST_SCHED_PRIORITY);
        } catch (Throwable ignored) {
        }
        mTimerHandler = new Handler(mTimerThread.getLooper());
    }

    public boolean extendSession(int handle, int durationMs) {
        if (handle <= 0) {
            return false;
        }
        synchronized (mLock) {
            BoostSession existing = mActiveSessions.get(handle);
            if (existing != null) {
                mTimerHandler.removeCallbacks(existing.timeoutRunnable);
                mTimerHandler.postDelayed(existing.timeoutRunnable, durationMs);
                return true;
            }
        }
        return false;
    }

    public int startSession(int sceneId, int callingPid, int callingUid, String pkg,
                            int targetPid, AxSceneRegistry.ScenarioConfig config,
                            int durationMs, Consumer<Integer> onTimeout) {
        final int handle = mNextHandle.getAndIncrement();
        Runnable timeoutRunnable = () -> onTimeout.accept(handle);

        BoostSession session = new BoostSession(handle, sceneId, callingPid, callingUid,
                pkg, System.currentTimeMillis(), targetPid, config, timeoutRunnable);

        synchronized (mLock) {
            mActiveSessions.put(handle, session);
        }

        mTimerHandler.postDelayed(timeoutRunnable, durationMs);
        return handle;
    }

    public BoostSession endSession(int handle) {
        BoostSession session;
        synchronized (mLock) {
            session = mActiveSessions.get(handle);
            if (session != null) {
                mActiveSessions.remove(handle);
            }
        }
        if (session != null) {
            mTimerHandler.removeCallbacks(session.timeoutRunnable);
        }
        return session;
    }

    public int getActiveMaxBoostLevel() {
        synchronized (mLock) {
            int maxLevel = BOOST_LEVEL_NONE;
            for (int i = 0; i < mActiveSessions.size(); i++) {
                int level = mActiveSessions.valueAt(i).config.boostLevel;
                if (level > maxLevel) {
                    maxLevel = level;
                }
            }
            return maxLevel;
        }
    }

    public boolean hasActivePinKswapd() {
        synchronized (mLock) {
            for (int i = 0; i < mActiveSessions.size(); i++) {
                if (mActiveSessions.valueAt(i).config.pinKswapd) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean hasActiveSurfaceFlingerBoost(AxSceneRegistry sceneRegistry) {
        synchronized (mLock) {
            for (int i = 0; i < mActiveSessions.size(); i++) {
                BoostSession session = mActiveSessions.valueAt(i);
                if (sceneRegistry.isSurfaceFlingerBoostScene(session.sceneId) || session.config.boostRenderThread) {
                    return true;
                }
            }
            return false;
        }
    }

    public void forEachActiveSession(Consumer<BoostSession> consumer) {
        synchronized (mLock) {
            for (int i = 0; i < mActiveSessions.size(); i++) {
                consumer.accept(mActiveSessions.valueAt(i));
            }
        }
    }

    public int getActiveSessionCount() {
        synchronized (mLock) {
            return mActiveSessions.size();
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  Active Boost Sessions: " + mActiveSessions.size());
            for (int i = 0; i < mActiveSessions.size(); i++) {
                BoostSession s = mActiveSessions.valueAt(i);
                pw.println("    Handle=" + s.handle + " Scene=" + s.sceneId + " PID=" + s.targetPid + " Pkg=" + s.packageName);
            }
        }
    }
}
