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

import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManagerInternal;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.internal.dragonite.AxDragoniteConstants;
import com.android.server.LocalServices;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public final class AxPerfEnhancer {
    private static final String TAG = "AxPerfEnhancer";

    public static final String PATH_CPU_SYSFS_PREFIX = "/sys/devices/system/cpu/cpu";
    public static final String PATH_SCALING_MIN_FREQ_SUFFIX = "/cpufreq/scaling_min_freq";
    public static final String PATH_STUNE_TOP_APP_BOOST = "/dev/stune/top-app/schedtune.boost";
    public static final String PATH_CPUCTL_TOP_APP_UCLAMP = "/dev/cpuctl/top-app/cpu.uclamp.min";
    public static final String PATH_CPUCTL_TOP_APP_LATENCY_SENSITIVE = "/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive";
    public static final String PATH_CPUCTL_TOP_APP_SHARES = "/dev/cpuctl/top-app/cpu.shares";
    public static final String PATH_CPUCTL_BG_SHARES = "/dev/cpuctl/background/cpu.shares";
    public static final String PATH_CPUCTL_TOP_APP_PROCS = "/dev/cpuctl/top-app/cgroup.procs";
    public static final String PATH_CPUCTL_RESTRICTED_UCLAMP = "/dev/cpuctl/restricted/cpu.uclamp.min";
    public static final String PATH_CPUCTL_RESTRICTED_PROCS = "/dev/cpuctl/restricted/cgroup.procs";
    public static final String PATH_AXD_BOOST = "/proc/ax_dragonite/boost";
    public static final String PATH_WALT_BOOST = "/proc/sys/walt/nt_sched_per_task_boost";
    public static final String PATH_AXD_KSWAPD_PIN = "/proc/ax_dragonite/kswapd_pin";
    public static final String PATH_DEV_CPUSET_PREFIX = "/dev/cpuset/";
    public static final String PATH_CPUS_SUFFIX = "/cpus";
    public static final String PATH_DEV_CPUSET_DEX2OAT = "/dev/cpuset/dex2oat/cpus";
    public static final String PATH_DEV_CPUSET_BACKGROUND = "/dev/cpuset/background/cpus";
    public static final String PATH_DEV_CPUSET_SYSTEM_BACKGROUND = "/dev/cpuset/system-background/cpus";
    public static final String PATH_DEV_CPUSET_TOP_APP = "/dev/cpuset/top-app/cpus";
    public static final String PATH_DEV_CPUSET_FOREGROUND = "/dev/cpuset/foreground/cpus";
    public static final String PATH_DEV_CPUSET_RESTRICTED = "/dev/cpuset/restricted/cpus";
    public static final String PATH_DEV_CPUSET_CAMERA_DAEMON = "/dev/cpuset/camera-daemon/cpus";
    public static final String PATH_DEV_CPUSET_AX_FOREGROUND = "/dev/cpuset/ax_foreground/cpus";
    public static final String PATH_DEV_CPUSET_NT_FOREGROUND = "/dev/cpuset/nt_foreground/cpus";
    public static final String PATH_DEV_CPUCTL_TOP_APP_PROCS = "/dev/cpuctl/top-app/cgroup.procs";
    public static final String PATH_DEV_CPUSET_TOP_APP_PROCS = "/dev/cpuset/top-app/cgroup.procs";

    public static final String SERVICE_SURFACE_FLINGER = "SurfaceFlinger";
    public static final String DESCRIPTOR_SURFACE_COMPOSER = "android.ui.ISurfaceComposer";

    public static final int TRANSACTION_SF_BOOST = 2007;

    public static final int SF_BOOST_ENABLE = 1;
    public static final int SF_BOOST_DISABLE = 0;

    public static final int DURATION_HEAVY_MS = 1200;
    public static final int DURATION_LIGHT_MS = 600;

    public static final int PERCENT_BOOST_LIGHT = 60;
    public static final int PERCENT_BOOST_HEAVY = 85;
    public static final int PERCENT_FULL = 100;

    public static final String VALUE_STUNE_HEAVY = "40";
    public static final String VALUE_STUNE_LIGHT = "20";
    public static final String VALUE_STUNE_ZERO = "0";

    public static final String VALUE_UCLAMP_HEAVY = "50";
    public static final String VALUE_UCLAMP_LIGHT = "25";
    public static final String VALUE_UCLAMP_ZERO = "0";

    public static final String VALUE_LATENCY_SENSITIVE_ON = "1";
    public static final String VALUE_LATENCY_SENSITIVE_OFF = "0";

    public static final String VALUE_SHARES_BOOST_HEAVY = "4096";
    public static final String VALUE_SHARES_BOOST_LIGHT = "2048";
    public static final String VALUE_SHARES_DEFAULT = "1024";
    public static final String VALUE_SHARES_BG_THROTTLE = "128";

    public static final int FIRST_CPU_INDEX = 0;
    public static final int BOOST_LEVEL_HEAVY_THRESHOLD = 1;

    private final AxCpuClusterManager mClusterManager;
    private final Map<Integer, String> mOriginalMinFreqs = new HashMap<>();

    private IBinder mSurfaceFlinger;
    private PowerManagerInternal mPowerManagerInternal;

    public AxPerfEnhancer(AxCpuClusterManager clusterManager) {
        this.mClusterManager = clusterManager;
        backupDefaultFreqs();
        initBootCpusets();
    }

    private PowerManagerInternal getPowerManagerInternal() {
        if (mPowerManagerInternal == null) {
            try {
                mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            } catch (Throwable ignored) {
            }
        }
        return mPowerManagerInternal;
    }

    private void getSurfaceFlinger() {
        if (mSurfaceFlinger == null) {
            mSurfaceFlinger = ServiceManager.getService(SERVICE_SURFACE_FLINGER);
        }
    }

    public void sendSurfaceFlingerBoost(boolean enable) {
        getSurfaceFlinger();
        if (mSurfaceFlinger == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR_SURFACE_COMPOSER);
            data.writeInt(enable ? SF_BOOST_ENABLE : SF_BOOST_DISABLE);
            data.writeInt(enable ? (int) mClusterManager.getBoostMask() : 0xff);
            mSurfaceFlinger.transact(TRANSACTION_SF_BOOST, data, reply, 0);
        } catch (Exception e) {
            Slog.e(TAG, "sendSurfaceFlingerBoost failed: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public void migrateToTopAppCgroup(int pid) {
        if (pid > 0) {
            writeNode(PATH_CPUCTL_TOP_APP_PROCS, String.valueOf(pid));
        }
    }

    private void backupDefaultFreqs() {
        List<AxCpuClusterManager.ClusterInfo> clusters = mClusterManager.getClusters();
        for (AxCpuClusterManager.ClusterInfo cluster : clusters) {
            if (!cluster.cpus.isEmpty()) {
                int firstCpu = cluster.cpus.get(FIRST_CPU_INDEX);
                String path = PATH_CPU_SYSFS_PREFIX + firstCpu + PATH_SCALING_MIN_FREQ_SUFFIX;
                String val = readNode(path);
                if (val != null) {
                    mOriginalMinFreqs.put(firstCpu, val.trim());
                }
            }
        }
    }

    public void applyCpuBoost(int level) {
        PowerManagerInternal pmi = getPowerManagerInternal();
        if (pmi != null) {
            int duration = level > BOOST_LEVEL_HEAVY_THRESHOLD ? DURATION_HEAVY_MS : DURATION_LIGHT_MS;
            try {
                pmi.setPowerBoost(Boost.INTERACTION, duration);
                if (level > BOOST_LEVEL_HEAVY_THRESHOLD) {
                    pmi.setPowerMode(Mode.LAUNCH, true);
                }
            } catch (Throwable t) {
                Slog.w(TAG, "PowerManagerInternal boost failed: " + t.getMessage());
            }
        }

        List<AxCpuClusterManager.ClusterInfo> clusters = mClusterManager.getClusters();
        for (AxCpuClusterManager.ClusterInfo cluster : clusters) {
            if (!cluster.cpus.isEmpty()) {
                int firstCpu = cluster.cpus.get(FIRST_CPU_INDEX);
                long targetFreq = cluster.maxFreq;
                if (level == AxDragoniteConstants.BOOST_LEVEL_LIGHT) {
                    targetFreq = (cluster.maxFreq * PERCENT_BOOST_LIGHT) / PERCENT_FULL;
                } else if (level == AxDragoniteConstants.BOOST_LEVEL_HEAVY) {
                    targetFreq = (cluster.maxFreq * PERCENT_BOOST_HEAVY) / PERCENT_FULL;
                }
                String path = PATH_CPU_SYSFS_PREFIX + firstCpu + PATH_SCALING_MIN_FREQ_SUFFIX;
                writeNode(path, String.valueOf(targetFreq));
            }
        }

        writeNode(PATH_STUNE_TOP_APP_BOOST, level > BOOST_LEVEL_HEAVY_THRESHOLD ? VALUE_STUNE_HEAVY : VALUE_STUNE_LIGHT);
        writeNode(PATH_CPUCTL_TOP_APP_UCLAMP, level > BOOST_LEVEL_HEAVY_THRESHOLD ? VALUE_UCLAMP_HEAVY : VALUE_UCLAMP_LIGHT);
        writeNode(PATH_CPUCTL_TOP_APP_LATENCY_SENSITIVE, VALUE_LATENCY_SENSITIVE_ON);
        writeNode(PATH_CPUCTL_TOP_APP_SHARES, level > BOOST_LEVEL_HEAVY_THRESHOLD ? VALUE_SHARES_BOOST_HEAVY : VALUE_SHARES_BOOST_LIGHT);
        if (level > BOOST_LEVEL_HEAVY_THRESHOLD) {
            writeNode(PATH_CPUCTL_BG_SHARES, VALUE_SHARES_BG_THROTTLE);
        }
        sendSurfaceFlingerBoost(true);
    }

    public void restoreCpuBoost() {
        PowerManagerInternal pmi = getPowerManagerInternal();
        if (pmi != null) {
            try {
                pmi.setPowerMode(Mode.LAUNCH, false);
            } catch (Throwable t) {
                Slog.w(TAG, "PowerManagerInternal restore failed: " + t.getMessage());
            }
        }

        for (Map.Entry<Integer, String> entry : mOriginalMinFreqs.entrySet()) {
            String path = PATH_CPU_SYSFS_PREFIX + entry.getKey() + PATH_SCALING_MIN_FREQ_SUFFIX;
            writeNode(path, entry.getValue());
        }

        writeNode(PATH_STUNE_TOP_APP_BOOST, VALUE_STUNE_ZERO);
        writeNode(PATH_CPUCTL_TOP_APP_UCLAMP, VALUE_UCLAMP_ZERO);
        writeNode(PATH_CPUCTL_TOP_APP_LATENCY_SENSITIVE, VALUE_LATENCY_SENSITIVE_OFF);
        writeNode(PATH_CPUCTL_TOP_APP_SHARES, VALUE_SHARES_DEFAULT);
        writeNode(PATH_CPUCTL_BG_SHARES, VALUE_SHARES_DEFAULT);
        sendSurfaceFlingerBoost(false);
    }

    public void setTaskBoost(int pid, int level) {
        String boostVal = pid + " " + level;
        if (!writeNode(PATH_AXD_BOOST, boostVal)) {
            writeNode(PATH_WALT_BOOST, boostVal);
        }
    }

    public void pinKswapd(long mask) {
        writeNode(PATH_AXD_KSWAPD_PIN, Long.toHexString(mask));
    }

    public void writeCpusetCpus(String group, String cpus) {
        writeNode(PATH_DEV_CPUSET_PREFIX + group + PATH_CPUS_SUFFIX, cpus);
    }

    public void initBootCpusets() {
        File restrictedDir = new File(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED);
        if (!restrictedDir.exists()) {
            restrictedDir.mkdirs();
        }
        writeNode(PATH_DEV_CPUSET_TOP_APP, mClusterManager.getTopAppCpusString());
        writeNode(PATH_DEV_CPUSET_FOREGROUND, mClusterManager.getForegroundCpusString());
        writeNode(PATH_DEV_CPUSET_SYSTEM_BACKGROUND, mClusterManager.getSystemBackgroundCpusString());
        writeNode(PATH_DEV_CPUSET_BACKGROUND, mClusterManager.getBackgroundCpusString());
        writeNode(PATH_DEV_CPUSET_DEX2OAT, mClusterManager.getBackgroundCpusString());
        writeNode(PATH_DEV_CPUSET_RESTRICTED, mClusterManager.getRestrictedBackgroundCpusString());
        writeNode(PATH_DEV_CPUSET_CAMERA_DAEMON, mClusterManager.getBoostCpusString());
        writeNode(PATH_DEV_CPUSET_AX_FOREGROUND, mClusterManager.getForegroundCpusString());
        writeNode(PATH_DEV_CPUSET_NT_FOREGROUND, mClusterManager.getForegroundCpusString());
    }

    public void restrictBackgroundCpusets(boolean restrict) {
        if (restrict) {
            writeNode(PATH_DEV_CPUSET_DEX2OAT, mClusterManager.getRestrictedDex2oatCpusString());
            writeNode(PATH_DEV_CPUSET_BACKGROUND, mClusterManager.getRestrictedBackgroundCpusString());
        } else {
            writeNode(PATH_DEV_CPUSET_DEX2OAT, mClusterManager.getBackgroundCpusString());
            writeNode(PATH_DEV_CPUSET_BACKGROUND, mClusterManager.getBackgroundCpusString());
            writeNode(PATH_DEV_CPUSET_SYSTEM_BACKGROUND, mClusterManager.getSystemBackgroundCpusString());
        }
    }

    public void limitAxForeground(boolean limit) {
        if (limit) {
            String restrictedFg = mClusterManager.getAxForegroundInputCpusString();
            String restrictedBg = mClusterManager.getRestrictedBackgroundCpusString();
            String restrictedDex = mClusterManager.getRestrictedDex2oatCpusString();
            writeNode(PATH_DEV_CPUSET_AX_FOREGROUND, restrictedFg);
            writeNode(PATH_DEV_CPUSET_NT_FOREGROUND, restrictedFg);
            writeNode(PATH_DEV_CPUSET_BACKGROUND, restrictedBg);
            writeNode(PATH_DEV_CPUSET_DEX2OAT, restrictedDex);
        } else {
            String normalFg = mClusterManager.getForegroundCpusString();
            String normalBg = mClusterManager.getBackgroundCpusString();
            writeNode(PATH_DEV_CPUSET_AX_FOREGROUND, normalFg);
            writeNode(PATH_DEV_CPUSET_NT_FOREGROUND, normalFg);
            writeNode(PATH_DEV_CPUSET_BACKGROUND, normalBg);
            writeNode(PATH_DEV_CPUSET_DEX2OAT, normalBg);
        }
    }

    public static boolean writeNode(String path, String value) {
        if (path == null || value == null) {
            return false;
        }
        File file = new File(path);
        if (!file.exists()) {
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(value.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void migrateToRestrictedCpuctl(int pid, boolean enable) {
        if (pid <= 0) return;
        if (enable) {
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED_PROCS, String.valueOf(pid));
            String uclampMin = SystemProperties.get(AxDragoniteConstants.PROPERTY_ANIMATIONBOOST_UCLAMP_MIN, AxDragoniteConstants.DEFAULT_UCLAMP_MIN_RESTRICTED);
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED_UCLAMP_MIN, uclampMin);
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED_UCLAMP_MAX, AxDragoniteConstants.DEFAULT_UCLAMP_MAX_RESTRICTED);
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED_LATENCY_SENSITIVE, VALUE_LATENCY_SENSITIVE_ON);
            writeNode(AxDragoniteConstants.PATH_DEV_CPUSET_RESTRICTED_CPUS, mClusterManager.getBoostCpusString());
        } else {
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_ROOT_PROCS, String.valueOf(pid));
            writeNode(AxDragoniteConstants.PATH_DEV_CPUCTL_RESTRICTED_UCLAMP_MIN, VALUE_UCLAMP_ZERO);
            writeNode(AxDragoniteConstants.PATH_DEV_CPUSET_RESTRICTED_CPUS, mClusterManager.getAllCpusString());
        }
    }

    public static String readNode(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Throwable t) {
            return null;
        }
    }
}
