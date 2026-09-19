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
import static com.android.internal.dragonite.AxDragoniteConstants.*;

/**
 * @hide
 */
public final class AxProcessTracker {

    private int mSystemUiPid = INVALID_PID;
    private int mLauncherPid = INVALID_PID;
    private volatile String mFocusedPkg;

    public void onProcessStarted(int pid, String pkg, String processName) {
        if (PKG_SYSTEMUI.equals(pkg) || PKG_SYSTEMUI.equals(processName)) {
            mSystemUiPid = pid;
        } else if (pkg != null && (pkg.contains(KEYWORD_LAUNCHER) || pkg.equals(AxActivityCustomizationUtil.PKG_LAUNCHER3))) {
            mLauncherPid = pid;
        }
    }

    public void onProcessKilled(int pid) {
        if (pid == mSystemUiPid) {
            mSystemUiPid = INVALID_PID;
        }
        if (pid == mLauncherPid) {
            mLauncherPid = INVALID_PID;
        }
    }

    public void onSetFocusedApp(String pkg) {
        mFocusedPkg = pkg;
    }

    public int getSystemUiPid() {
        return mSystemUiPid;
    }

    public int getLauncherPid() {
        return mLauncherPid;
    }

    public String getFocusedPackage() {
        return mFocusedPkg;
    }
}
