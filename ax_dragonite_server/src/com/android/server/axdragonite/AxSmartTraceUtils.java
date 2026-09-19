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

import android.os.Trace;

public final class AxSmartTraceUtils {
    public static final String TRACE_TAG_PREFIX = "AxDragonite:";

    public static void traceBegin(String tag) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, TRACE_TAG_PREFIX + tag);
    }

    public static void traceEnd() {
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
    }

    public static void traceCounter(String counterName, int value) {
        Trace.traceCounter(Trace.TRACE_TAG_ACTIVITY_MANAGER, TRACE_TAG_PREFIX + counterName, value);
    }
}
