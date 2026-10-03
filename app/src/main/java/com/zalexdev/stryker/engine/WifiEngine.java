package com.zalexdev.stryker.engine;

import android.content.Context;

import com.zalexdev.stryker.utils.Core;

public final class WifiEngine {

    public static final String PREF = "wifi_engine";

    public static final String PREF_VERIFIED = "wifi_engine_verified";

    public static final String PREF_ADAPTER = "wifi_engine_adapter";

    public static final String PREF_IFACE = "wifi_engine_iface";

    private WifiEngine() {}

    public static boolean armed(Core core) {
        return core != null && core.getBoolean(PREF_VERIFIED) && selected(core) != null;
    }

    public static EngineType selected(Core core) {
        if (core == null) return null;
        if (EngineType.configured(core) != EngineType.CHROOT) return null;
        EngineType t = configured(core);
        if (t == null) return null;
        try {
            return Engines.active(core.context, t).isInstalled() ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static EngineType configured(Core core) {
        if (core == null) return null;
        String v = core.getString(PREF);
        if (EngineType.ROOTLESS.name().equals(v)) return EngineType.ROOTLESS;
        if (EngineType.UML.name().equals(v)) return EngineType.UML;
        return null;
    }

    public static Core bind(Core core) {
        if (core == null) return null;
        EngineType t = armed(core) ? configured(core) : null;
        return t == null ? core : core.overrideEngine(t);
    }

    public static Core bindForBoot(Core core) {
        if (core == null) return null;
        EngineType t = selected(core);
        return t == null ? core : core.overrideEngine(t);
    }

    public static void choose(Core core, EngineType type) {
        if (core == null || type == null) return;
        core.putString(PREF, type.name());
        core.putBoolean(PREF_VERIFIED, false);
    }

    public static Core bindFor(Core core, String iface) {
        if (core == null) return null;
        if (!armed(core)) return core;
        if (!isGuestInterface(core, iface)) return core;
        return core.overrideEngine(configured(core));
    }

    public static Core core(Context context) {
        return bind(new Core(context));
    }

    public static void arm(Core core, EngineType type, String adapterVidPid, String iface) {
        if (core == null || type == null) return;
        core.putString(PREF, type.name());
        core.putBoolean(PREF_VERIFIED, true);
        core.putString(PREF_ADAPTER, adapterVidPid == null ? "" : adapterVidPid);
        core.putString(PREF_IFACE, iface == null ? "" : iface);
    }

    public static String iface(Core core) {
        return core == null ? "" : core.getString(PREF_IFACE);
    }

    public static boolean isGuestInterface(Core core, String iface) {
        String own = iface(core);
        if (own.isEmpty() || iface == null || iface.isEmpty()) return false;
        return iface.equals(own) || iface.equals(own + "mon") || own.equals(iface + "mon");
    }

    public static void disarm(Core core) {
        if (core == null) return;
        core.putString(PREF, "");
        core.putBoolean(PREF_VERIFIED, false);
        core.putString(PREF_ADAPTER, "");
        core.putString(PREF_IFACE, "");
    }

    public static String adapter(Core core) {
        return core == null ? "" : core.getString(PREF_ADAPTER);
    }

    public static GuestEngine ensureStarting(Context context) {
        EngineType t = selected(new Core(context));
        if (t == null) return null;
        GuestEngine engine = Engines.active(context, t);
        if (!engine.isRunning()) RootlessService.start(context);
        return engine;
    }
}
