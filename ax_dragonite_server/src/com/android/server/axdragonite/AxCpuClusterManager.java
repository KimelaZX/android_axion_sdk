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

import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public final class AxCpuClusterManager {
    private static final String TAG = "AxCpuClusterManager";

    public static final String PATH_CPU_POSSIBLE = "/sys/devices/system/cpu/possible";
    public static final String PATH_CPU_SYSFS_PREFIX = "/sys/devices/system/cpu/cpu";
    public static final String PATH_CPUINFO_MAX_FREQ_SUFFIX = "/cpufreq/cpuinfo_max_freq";
    public static final String RANGE_DELIMITER = "-";

    public static final int DEFAULT_CORE_COUNT = 8;
    public static final int SINGLE_CLUSTER = 1;
    public static final int DUAL_CLUSTER = 2;
    public static final int TRI_CLUSTER = 3;
    public static final int CLUSTER_INDEX_LITTLE = 0;
    public static final int CLUSTER_INDEX_BIG = 1;
    public static final int CLUSTER_INDEX_PRIME = 2;
    public static final long FALLBACK_FREQ_BASE = 100000L;

    public static final int AFFINITY_LITTLE = 1;
    public static final int AFFINITY_BIG = 2;
    public static final int AFFINITY_PRIME = 3;
    public static final int AFFINITY_BOOST = 4;
    public static final int AFFINITY_ALL = 5;
    public static final int AFFINITY_MID = 6;

    public static final class ClusterInfo {
        public final int clusterId;
        public final long maxFreq;
        public final List<Integer> cpus = new ArrayList<>();
        public long mask = 0;

        public ClusterInfo(int id, long freq) {
            this.clusterId = id;
            this.maxFreq = freq;
        }
    }

    private static AxCpuClusterManager sInstance;

    private int mNumCores = 0;
    private final List<ClusterInfo> mClusters = new ArrayList<>();
    private long mLittleMask = 0;
    private long mMidMask = 0;
    private long mBigMask = 0;
    private long mPrimeMask = 0;
    private long mBoostMask = 0;
    private long mAllMask = 0;
    private long mEfficiencyPoolMask = 0;
    private long mPerformancePoolMask = 0;

    public static synchronized AxCpuClusterManager getInstance() {
        if (sInstance == null) {
            sInstance = new AxCpuClusterManager();
        }
        return sInstance;
    }

    private AxCpuClusterManager() {
        detectTopology();
    }

    private void detectTopology() {
        mNumCores = Runtime.getRuntime().availableProcessors();
        File possibleFile = new File(PATH_CPU_POSSIBLE);
        if (possibleFile.exists()) {
            String line = readFirstLine(possibleFile);
            if (line != null && line.contains(RANGE_DELIMITER)) {
                try {
                    String[] parts = line.trim().split(RANGE_DELIMITER);
                    mNumCores = Integer.parseInt(parts[1]) + 1;
                } catch (Exception ignored) {
                }
            }
        }
        if (mNumCores <= 0) {
            mNumCores = DEFAULT_CORE_COUNT;
        }

        Map<Long, ClusterInfo> freqToCluster = new HashMap<>();
        for (int i = 0; i < mNumCores; i++) {
            long maxFreq = readCpuMaxFreq(i);
            ClusterInfo cluster = freqToCluster.get(maxFreq);
            if (cluster == null) {
                cluster = new ClusterInfo(freqToCluster.size(), maxFreq);
                freqToCluster.put(maxFreq, cluster);
            }
            cluster.cpus.add(i);
            cluster.mask |= (1L << i);
            mAllMask |= (1L << i);
        }

        mClusters.addAll(freqToCluster.values());
        Collections.sort(mClusters, Comparator.comparingLong(c -> c.maxFreq));

        int clusterCount = mClusters.size();
        if (clusterCount == SINGLE_CLUSTER) {
            mLittleMask = mClusters.get(CLUSTER_INDEX_LITTLE).mask;
            mMidMask = mLittleMask;
            mBigMask = mLittleMask;
            mPrimeMask = mLittleMask;
            mBoostMask = mLittleMask;
            mEfficiencyPoolMask = mAllMask;
            mPerformancePoolMask = mAllMask;
        } else if (clusterCount == DUAL_CLUSTER) {
            mLittleMask = mClusters.get(CLUSTER_INDEX_LITTLE).mask;
            mMidMask = mClusters.get(CLUSTER_INDEX_BIG).mask;
            mBigMask = mClusters.get(CLUSTER_INDEX_BIG).mask;
            mPrimeMask = mBigMask;
            mBoostMask = mBigMask;
            mEfficiencyPoolMask = mLittleMask;
            mPerformancePoolMask = mBigMask;
        } else if (clusterCount == TRI_CLUSTER) {
            mLittleMask = mClusters.get(CLUSTER_INDEX_LITTLE).mask;
            mMidMask = mClusters.get(CLUSTER_INDEX_BIG).mask;
            mPrimeMask = mClusters.get(CLUSTER_INDEX_PRIME).mask;
            mBigMask = mMidMask | mPrimeMask;
            mBoostMask = mMidMask | mPrimeMask;
            if (Long.bitCount(mLittleMask) >= 3) {
                mEfficiencyPoolMask = mLittleMask;
                mPerformancePoolMask = mBigMask;
            } else {
                int midShare = Math.max(1, Long.bitCount(mMidMask) / 2);
                mEfficiencyPoolMask = mLittleMask | getLowestNBits(mMidMask, midShare);
                mPerformancePoolMask = mAllMask & ~mEfficiencyPoolMask;
            }
        } else {
            mLittleMask = mClusters.get(CLUSTER_INDEX_LITTLE).mask;
            mMidMask = mClusters.get(1).mask;
            mPrimeMask = mClusters.get(clusterCount - 1).mask;
            long lowPool = mClusters.get(0).mask | mClusters.get(1).mask;
            long highPool = 0;
            for (int i = 2; i < clusterCount; i++) {
                highPool |= mClusters.get(i).mask;
            }
            mEfficiencyPoolMask = lowPool;
            mPerformancePoolMask = highPool;
            mBigMask = highPool;
            mBoostMask = highPool;
        }

        Slog.i(TAG, "CPU Topology detected: " + mNumCores + " cores, " + clusterCount + " clusters. "
                + "Little: 0x" + Long.toHexString(mLittleMask)
                + ", Mid: 0x" + Long.toHexString(mMidMask)
                + ", Big: 0x" + Long.toHexString(mBigMask)
                + ", Prime: 0x" + Long.toHexString(mPrimeMask)
                + ", Boost: 0x" + Long.toHexString(mBoostMask)
                + ", EffPool: 0x" + Long.toHexString(mEfficiencyPoolMask)
                + ", PerfPool: 0x" + Long.toHexString(mPerformancePoolMask));
    }

    private long readCpuMaxFreq(int cpu) {
        File freqFile = new File(PATH_CPU_SYSFS_PREFIX + cpu + PATH_CPUINFO_MAX_FREQ_SUFFIX);
        if (freqFile.exists()) {
            String val = readFirstLine(freqFile);
            if (val != null) {
                try {
                    return Long.parseLong(val.trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return (cpu + 1) * FALLBACK_FREQ_BASE;
    }

    private String readFirstLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    public int getNumCores() {
        return mNumCores;
    }

    public int getNumClusters() {
        return mClusters.size();
    }

    public List<ClusterInfo> getClusters() {
        return mClusters;
    }

    public long getLittleMask() {
        return mLittleMask;
    }

    public long getMidMask() {
        return mMidMask;
    }

    public long getBigMask() {
        return mBigMask;
    }

    public long getPrimeMask() {
        return mPrimeMask;
    }

    public long getBoostMask() {
        return mBoostMask;
    }

    public long getAllMask() {
        return mAllMask;
    }

    public long getMaskForType(int affinityType) {
        if (affinityType == 0 || affinityType == AFFINITY_BIG || affinityType == AFFINITY_BOOST) {
            return mBoostMask != 0 ? mBoostMask : mAllMask;
        }
        if (affinityType == AFFINITY_LITTLE) {
            return mLittleMask != 0 ? mLittleMask : mAllMask;
        }
        if (affinityType == AFFINITY_PRIME) {
            return resolvePrimeMask();
        }
        if (affinityType == AFFINITY_MID) {
            return resolveMidMask();
        }
        return mAllMask;
    }

    private long resolvePrimeMask() {
        if (mPrimeMask != 0) return mPrimeMask;
        if (mBigMask != 0) return mBigMask;
        return mAllMask;
    }

    private long resolveMidMask() {
        if (mMidMask != 0) return mMidMask;
        if (mBigMask != 0) return mBigMask;
        return mAllMask;
    }

    public static long getLowestNBits(long mask, int count) {
        if (count <= 0) return 0;
        long result = 0;
        int found = 0;
        for (int i = 0; i < 64 && found < count; i++) {
            if (((mask >> i) & 1L) != 0) {
                result |= (1L << i);
                found++;
            }
        }
        return result != 0 ? result : mask;
    }

    public static String toCpusetString(long mask) {
        if (mask == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        int start = -1;
        for (int i = 0; i < 64; i++) {
            boolean bitSet = ((mask >> i) & 1) != 0;
            if (bitSet) {
                if (start == -1) {
                    start = i;
                }
            } else {
                if (start != -1) {
                    appendRange(sb, start, i - 1);
                    start = -1;
                }
            }
        }
        if (start != -1) {
            appendRange(sb, start, 63);
        }
        return sb.length() > 0 ? sb.toString() : "0";
    }

    private static void appendRange(StringBuilder sb, int start, int end) {
        if (sb.length() > 0) {
            sb.append(",");
        }
        if (start == end) {
            sb.append(start);
        } else {
            sb.append(start).append("-").append(end);
        }
    }

    public String getAllCpusString() {
        return toCpusetString(mAllMask);
    }

    public String getLittleCpusString() {
        return toCpusetString(mLittleMask);
    }

    public String getBigCpusString() {
        return toCpusetString(mBigMask);
    }

    public long getEfficiencyPoolMask() {
        return mEfficiencyPoolMask;
    }

    public long getPerformancePoolMask() {
        return mPerformancePoolMask;
    }

    public String getPrimeCpusString() {
        return toCpusetString(mPrimeMask);
    }

    public String getBoostCpusString() {
        return toCpusetString(mPerformancePoolMask != 0 ? mPerformancePoolMask : mBoostMask);
    }

    public String getRestrictedBackgroundCpusString() {
        int poolCount = Long.bitCount(mEfficiencyPoolMask);
        if (poolCount <= 0) {
            int fallback = Math.max(1, mNumCores / 2);
            return toCpusetString(getLowestNBits(mAllMask, Math.max(1, fallback / 2)));
        }
        int restrictedCount = Math.max(1, (poolCount * 3) / 4);
        long restricted = getLowestNBits(mEfficiencyPoolMask, restrictedCount);
        return toCpusetString(restricted);
    }

    public String getRestrictedDex2oatCpusString() {
        int poolCount = Long.bitCount(mEfficiencyPoolMask);
        if (poolCount <= 0) {
            return toCpusetString(getLowestNBits(mAllMask, 1));
        }
        int dex2oatCount = Math.max(1, poolCount / 3);
        long dex2oatMask = getLowestNBits(mEfficiencyPoolMask, dex2oatCount);
        return toCpusetString(dex2oatMask);
    }

    public String getSystemBackgroundCpusString() {
        return toCpusetString(mAllMask);
    }

    public String getRestrictedSystemBgCpusString() {
        return toCpusetString(mAllMask);
    }

    public String getBackgroundCpusString() {
        return toCpusetString(mEfficiencyPoolMask != 0 ? mEfficiencyPoolMask : mAllMask);
    }

    public String getForegroundCpusString() {
        if (mClusters.size() >= 3 && Long.bitCount(mPrimeMask) == 1) {
            return toCpusetString(mAllMask & ~mPrimeMask);
        }
        return toCpusetString(mAllMask);
    }

    public String getTopAppCpusString() {
        return toCpusetString(mAllMask);
    }

    public String getAxForegroundCpusString() {
        return toCpusetString(mPerformancePoolMask != 0 ? mPerformancePoolMask : mBoostMask);
    }

    public String getAxForegroundInputCpusString() {
        return toCpusetString(mEfficiencyPoolMask != 0 ? mEfficiencyPoolMask : mAllMask);
    }
}
