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

import android.os.Process;
import android.util.Slog;

import com.android.internal.dragonite.AxDragoniteConstants;
import static com.android.internal.dragonite.AxDragoniteConstants.*;

/**
 * @hide
 */
public final class AxOpcodeDispatcher {
    private static final String TAG = "AxOpcodeDispatcher";

    private final AxPerfEnhancer mPerfEnhancer;
    private final AxBoostAdjuster mBoostAdjuster;
    private final AxNamedThreadAffinityFeature mAffinityFeature;

    public AxOpcodeDispatcher(AxPerfEnhancer perfEnhancer,
                              AxBoostAdjuster boostAdjuster,
                              AxNamedThreadAffinityFeature affinityFeature) {
        this.mPerfEnhancer = perfEnhancer;
        this.mBoostAdjuster = boostAdjuster;
        this.mAffinityFeature = affinityFeature;
    }

    public void parseAndApply(String params, AxBoostSessionManager.BoostSession session) {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (String part : params.split(PARAM_DELIMITER)) {
            applyParamOpcode(part, session);
        }
    }

    private void applyParamOpcode(String part, AxBoostSessionManager.BoostSession session) {
        String[] kv = part.split(OPCODE_DELIMITER);
        if (kv.length != 2) return;
        int opcode = parseOpcode(kv[0]);
        if (opcode <= 0) return;
        for (String tidStr : kv[1].split(TID_DELIMITER)) {
            applyTidOpcode(opcode, tidStr.trim(), session);
        }
    }

    private int parseOpcode(String opcodeStr) {
        try {
            return Integer.parseInt(opcodeStr.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void applyTidOpcode(int opcode, String tidStr, AxBoostSessionManager.BoostSession session) {
        int tid = parseTid(tidStr);
        if (tid <= 0) return;

        if (opcode == OPCODE_THREAD_BOOST || opcode == OPCODE_LEGACY_BOOST_SCHED) {
            if (session != null) session.boostedTids.add(tid);
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY);
            applyBoostSched(tid);
            return;
        }
        if (opcode == OPCODE_THREAD_AFFINITY || opcode == OPCODE_LEGACY_CPU_AFFINITY) {
            if (session != null) session.boostedTids.add(tid);
            mBoostAdjuster.setThreadAffinity(tid, AFFINITY_TYPE_BIG_CORES);
            return;
        }
        if (opcode == OPCODE_PROCESS_AFFINITY || opcode == OPCODE_LEGACY_SCHED_PRIORITY) {
            if (session != null) session.boostedTids.add(tid);
            mAffinityFeature.applyNamedAffinityForPid(tid);
            return;
        }
        if (opcode == OPCODE_CPUCTL_TOP_APP || opcode == OPCODE_LEGACY_CPUCTL_TOP_APP) {
            mPerfEnhancer.writeNode(PATH_DEV_CPUCTL_TOP_APP_PROCS, String.valueOf(tid));
            return;
        }
        if (opcode == OPCODE_CPUSET_TOP_APP || opcode == OPCODE_LEGACY_CPUSET_TOP_APP) {
            mPerfEnhancer.writeNode(PATH_DEV_CPUSET_TOP_APP_PROCS, String.valueOf(tid));
            return;
        }
        if (opcode == OPCODE_BACKGROUND_FREEZE || opcode == OPCODE_LEGACY_FREEZE_PROCESS) {
            mBoostAdjuster.freezeApp(0, tid);
        }
    }

    private void applyBoostSched(int tid) {
        try {
            Process.setThreadScheduler(tid, BOOST_SCHED_POLICY, BOOST_SCHED_PRIORITY);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to set BOOST_SCHED for tid " + tid + ": " + t.getMessage());
        }
    }

    private int parseTid(String tidStr) {
        try {
            return Integer.parseInt(tidStr);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
