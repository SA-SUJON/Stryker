package com.zalexdev.stryker.engine;

import android.content.Context;
import android.os.Build;

import com.zalexdev.stryker.utils.Core;

public enum EngineType {
    CHROOT,
    ROOTLESS,
    UML;

    public static final String PREF_KEY = "engine_type";

    public static final String PREF_VERIFIED = "engine_boot_verified";

    public static final String PREF_FORCED = "engine_forced_unsupported";

    public static EngineType active(Core core) {
        if (core == null) return CHROOT;
        EngineType override = core.engineOverride();
        if (override != null) return override;
        String v = core.getString(PREF_KEY);
        if (v != null && v.equals(ROOTLESS.name())) return ROOTLESS;
        if (v != null && v.equals(UML.name())) return UML;
        return CHROOT;
    }

    public static boolean isRootless(Core core) {
        EngineType t = active(core);
        return t == ROOTLESS || t == UML;
    }

    public static boolean isVm(Core core) {
        return active(core) == ROOTLESS;
    }

    public static boolean isUml(Core core) {
        return active(core) == UML;
    }

    public static boolean isChosen(Core core) {
        if (core == null) return false;
        String v = core.getString(PREF_KEY);
        return v != null && !v.isEmpty();
    }

    public static EngineType configured(Core core) {
        if (core == null) return CHROOT;
        String v = core.getString(PREF_KEY);
        if (v != null && v.equals(ROOTLESS.name())) return ROOTLESS;
        if (v != null && v.equals(UML.name())) return UML;
        return CHROOT;
    }

    public static void persist(Core core, EngineType type) {
        core.putString(PREF_KEY, type.name());
        try {
            java.io.File flag = RootlessPaths.activeFlag(core.context);
            if (type == ROOTLESS || type == UML) {
                java.io.File dir = flag.getParentFile();
                if (dir != null && !dir.exists())
                    dir.mkdirs();
                try (java.io.FileWriter w = new java.io.FileWriter(flag, false)) { w.write("1"); }
            } else if (flag.exists()) {
                flag.delete();
            }
        } catch (Exception ignored) {}
    }

    public static boolean rootlessSupported(Context context) {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
}
