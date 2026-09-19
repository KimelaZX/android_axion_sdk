package com.android.internal.dragonite;

import android.os.Process;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @hide
 */
public final class AxDragoniteConstants {
    private AxDragoniteConstants() {
    }

    public static final String TAG = "AxDragonite";

    public static final String KEY_PID = "pid";
    public static final String KEY_TARGET_PID = "target_pid";
    public static final String KEY_CALLING_PID = "calling_pid";
    public static final String KEY_PKG = "pkg";
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_PACKAGE = "package";
    public static final String KEY_HANDLE = "handle";
    public static final String KEY_COMPONENT_NAME = "componentName";
    public static final String KEY_DURATION = "duration";
    public static final String KEY_PARAMS = "params";
    public static final String KEY_IS_COLD = "isCold";
    public static final String KEY_UID = "uid";

    public static final String PKG_SYSTEMUI = "com.android.systemui";
    public static final String KEYWORD_LAUNCHER = "launcher";
    public static final String KEYWORD_CAMERA = "camera";
    public static final int DEFAULT_FLING_VELOCITY = 0;

    public static final String WORKER_THREAD_NAME = "AxDragoniteWorker";
    public static final String TIMER_THREAD_NAME = "AxDragoniteTimer";

    public static final int BOOST_LEVEL_NONE = 0;
    public static final int BOOST_LEVEL_LIGHT = 1;
    public static final int BOOST_LEVEL_HEAVY = 2;

    public static final int SCENE_AX_APP_START = 1;
    public static final int SCENE_FLING = 2;
    public static final int SCENE_AX_FLING = 2;
    public static final int SCENE_DATA_LOADING = 3;
    public static final int SCENE_FOLDER_ANIMATION = 4;
    public static final int SCENE_DRAG_AND_DROP = 5;
    public static final int SCENE_SCROLL = 6;
    public static final int SCENE_AX_NOTIFICATION_EXPAND = 100;
    public static final int SCENE_NOTIFICATION_EXPAND = SCENE_AX_NOTIFICATION_EXPAND;
    public static final int SCENE_AX_UNLOCK = 101;
    public static final int SCENE_UNLOCK = SCENE_AX_UNLOCK;
    public static final int SCENE_AX_SYSTEMUI_ANIMATION = 102;
    public static final int SCENE_ANIMATION = SCENE_AX_SYSTEMUI_ANIMATION;
    public static final int SCENE_APP_LAUNCH_COLD = 103;
    public static final int SCENE_AX_APP_LAUNCH_COLD = 103;
    public static final int SCENE_APP_LAUNCH = 103;
    public static final int SCENE_AX_APP_LAUNCH = 103;
    public static final int SCENE_APP_LAUNCH_WARM = 104;
    public static final int SCENE_AX_APP_LAUNCH_WARM = 104;
    public static final int SCENE_APP_EXIT_ANIM = 105;
    public static final int SCENE_AX_BACK_HOME = 105;
    public static final int SCENE_ROTATION = 106;
    public static final int SCENE_CAMERA_OPEN = 201;
    public static final int SCENE_CAMERA_CAPTURE = 202;
    public static final int SCENE_GAME_MODE = 203;
    public static final int SCENE_BIOMETRIC_UNLOCK = 301;
    public static final int SCENE_RECENT_TASK_SLIDE = 401;
    public static final int SCENE_AX_LAUNCHER_GESTURE_START = 401;
    public static final int SCENE_QUICK_SWITCH_APP = 402;
    public static final int SCENE_FLING_LEVEL_1 = 500;
    public static final int SCENE_DISABLE_INPUT_BOOST = 600;

    public static final int DEFAULT_TIMEOUT_MS = 500;
    public static final int DURATION_DEFAULT_TIMEOUT_MS = 500;
    public static final int DURATION_APP_LAUNCH_MS = 1200;
    public static final int DURATION_GESTURE_START_MS = 300;
    public static final int DURATION_BACK_HOME_MS = 500;
    public static final int DURATION_LIGHT_REVEAL_MS = 500;
    public static final int DURATION_DOZE_MS = 500;
    public static final int DURATION_SHADE_EXPAND_MS = 500;
    public static final int DURATION_UNLOCK_MS = 800;
    public static final int DURATION_NOTIFICATION_STACK_SCROLL_MS = 400;
    public static final int DURATION_FLING_MS = 600;
    public static final int DURATION_SCROLL_MS = 400;
    public static final int DURATION_APP_LAUNCH_COLD_MS = 1200;
    public static final int DURATION_APP_LAUNCH_WARM_MS = 800;
    public static final int DURATION_APP_EXIT_ANIM_MS = 400;
    public static final int DURATION_ROTATION_MS = 600;
    public static final int DURATION_SPLIT_SCREEN_RESIZE_MS = 500;
    public static final int DURATION_CAMERA_OPEN_MS = 1500;
    public static final int DURATION_CAMERA_CAPTURE_MS = 800;
    public static final int DURATION_BIOMETRIC_UNLOCK_MS = 600;
    public static final int DURATION_FACE_UNLOCK_MS = 600;
    public static final int DURATION_RECENT_TASK_SLIDE_MS = 400;
    public static final int DURATION_QUICK_SWITCH_APP_MS = 600;
    public static final int DURATION_GC_COMPACTION_SUPPRESS_MS = 1000;
    public static final int DURATION_KSWAPD_AFFINITY_PIN_MS = 1000;
    public static final int DURATION_DEFAULT_FALLBACK_MS = 500;
    public static final int DURATION_AX_APP_START_MS = 1200;
    public static final int DURATION_AX_SPEED_UP_APP_START_MS = 1000;
    public static final int DURATION_AX_HOME_ANIM_MS = 500;
    public static final int DURATION_AX_USER_PRESENT_MS = 800;
    public static final int DURATION_AX_NOTIFICATION_EXPAND_MS = 600;
    public static final int DURATION_AX_UNLOCK_MS = 800;
    public static final int DURATION_AX_SYSTEMUI_ANIMATION_MS = 500;
    public static final int DURATION_GESTURE_MS = 400;
    public static final int DURATION_QUICK_SWITCH_MS = 600;
    public static final int DURATION_BIOMETRIC_AUTH_MS = 2000;
    public static final int DURATION_ANIMATION_MS = 2000;
    public static final int DURATION_VOLUME_DIALOG_MS = 1500;

    public static final int OPCODE_THREAD_BOOST = 501;
    public static final int OPCODE_BOOST_SCHED = 501;
    public static final int OPCODE_THREAD_AFFINITY = 600;
    public static final int OPCODE_CPU_AFFINITY = 600;
    public static final int OPCODE_PROCESS_AFFINITY = 601;
    public static final int OPCODE_SCHED_PRIORITY = 601;
    public static final int OPCODE_CPUCTL_TOP_APP = 302;
    public static final int OPCODE_CPUSET_TOP_APP = 100;
    public static final int OPCODE_BACKGROUND_FREEZE = 700;
    public static final int OPCODE_FREEZE_PROCESS = 700;

    public static final int OPCODE_LEGACY_CPU_AFFINITY = 1;
    public static final int OPCODE_LEGACY_SCHED_PRIORITY = 2;
    public static final int OPCODE_LEGACY_BOOST_SCHED = 3;
    public static final int OPCODE_LEGACY_CPUCTL_TOP_APP = 4;
    public static final int OPCODE_LEGACY_CPUSET_TOP_APP = 800;
    public static final int OPCODE_LEGACY_FREEZE_PROCESS = 1000;

    public static final int AFFINITY_TYPE_BIG_CORES = 1;
    public static final int BOOST_SCHED_POLICY = Process.SCHED_RESET_ON_FORK | Process.SCHED_RR;
    public static final int BOOST_SCHED_PRIORITY = 1;

    public static final String PARAM_DELIMITER = ";";
    public static final String OPCODE_DELIMITER = ":";
    public static final String TID_DELIMITER = ",";

    public static final String PATH_DEV_CPUCTL_RESTRICTED = "/dev/cpuctl/restricted";
    public static final String PATH_DEV_CPUCTL_RESTRICTED_PROCS = "/dev/cpuctl/restricted/cgroup.procs";
    public static final String PATH_DEV_CPUCTL_RESTRICTED_UCLAMP_MIN = "/dev/cpuctl/restricted/cpu.uclamp.min";
    public static final String PATH_DEV_CPUCTL_RESTRICTED_UCLAMP_MAX = "/dev/cpuctl/restricted/cpu.uclamp.max";
    public static final String PATH_DEV_CPUCTL_RESTRICTED_LATENCY_SENSITIVE = "/dev/cpuctl/restricted/cpu.uclamp.latency_sensitive";
    public static final String PATH_DEV_CPUCTL_ROOT_PROCS = "/dev/cpuctl/cgroup.procs";
    public static final String PATH_DEV_CPUSET_RESTRICTED_CPUS = "/dev/cpuset/restricted/cpus";
    public static final String PROPERTY_ANIMATIONBOOST_UCLAMP_MIN = "persist.sys.animationboost_uclamp_min";
    public static final String DEFAULT_UCLAMP_MIN_RESTRICTED = "50";
    public static final String DEFAULT_UCLAMP_MAX_RESTRICTED = "100";

    public static final Set<String> ALLOWED_CPUSET_GROUPS = Set.of(
            "top-app", "foreground", "background", "system-background",
            "restricted", "camera-daemon", "nnapi-hal"
    );
    public static final Pattern CPUSET_CPUS_PATTERN = Pattern.compile("^[0-9,-]+$");
    public static final long MAX_BOOST_DURATION_MS = 30000L;
    public static final long MIN_BOOST_DURATION_MS = 0L;

    public static final String PATH_DEV_CPUCTL_TOP_APP_PROCS = "/dev/cpuctl/top-app/cgroup.procs";
    public static final String PATH_DEV_CPUSET_TOP_APP_PROCS = "/dev/cpuset/top-app/cgroup.procs";

    public static final int INVALID_PID = -1;
    public static final int INVALID_DURATION = -1;
    public static final int INVALID_HANDLE = -1;
    public static final int MIN_VALID_HANDLE = 0;
    public static final int INITIAL_HANDLE_VALUE = 1;
    public static final int EMPTY_COUNT = 0;
}
