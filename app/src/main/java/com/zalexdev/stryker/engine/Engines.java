package com.zalexdev.stryker.engine;

import android.content.Context;

import com.zalexdev.stryker.utils.Core;
import com.stryker.terminal.bridge.StrykerLog;

public final class Engines {

    private Engines() {}

    private static volatile UmlEngine umlInstance;

    private static volatile EngineType lastReported;

    public static GuestEngine active(Context context, EngineType type) {
        if (type != lastReported) {
            lastReported = type;
            StrykerLog.i("Engines", "active engine: " + type);
        }
        if (type == EngineType.UML) return uml(context);
        return RootlessEngine.get(context);
    }

    public static GuestEngine active(Core core) {
        return active(core.context, EngineType.active(core));
    }

    public static UmlEngine uml(Context context) {
        if (umlInstance == null) {
            synchronized (Engines.class) {
                if (umlInstance == null) umlInstance = new UmlEngine(context);
            }
        }
        return umlInstance;
    }

    public static GuestEngine running(Context context) {
        UmlEngine u = umlInstance;
        if (u != null && u.isRunning()) return u;
        RootlessEngine q = RootlessEngine.get(context);
        if (q.isRunning()) return q;
        return null;
    }

    public static void stopAll(Context context) {
        UmlEngine u = umlInstance;
        if (u != null && u.isRunning()) u.stop();
        RootlessEngine q = RootlessEngine.get(context);
        if (q.isRunning()) q.stop();
    }
}
