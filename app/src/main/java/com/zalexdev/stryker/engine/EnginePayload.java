package com.zalexdev.stryker.engine;

import android.content.Context;

import com.zalexdev.stryker.utils.Core;
import com.stryker.terminal.bridge.StrykerLog;

import java.io.File;

public final class EnginePayload {

    private static final String TAG = "EnginePayload";

    public static final int REQUIRED = 650;

    private static final int LEGACY = 600;

    private static final String PREF_REVISION = "payload_revision_rootless";

    private EnginePayload() {
    }

    public static int installed(Core core) {
        if (core == null || !EngineType.isRootless(core)) return 0;
        if (!Engines.active(core).isInstalled()) return 0;
        int stored = core.getInt(PREF_REVISION, 0);
        return stored > 0 ? stored : LEGACY;
    }

    public static boolean outdated(Core core) {
        int have = installed(core);
        return have > 0 && have < REQUIRED;
    }

    public static void mark(Core core) {
        if (core == null) return;
        core.putInt(PREF_REVISION, REQUIRED);
    }

    public static String describe(Core core) {
        if (core == null) return "unknown";
        if (!EngineType.isRootless(core)) return "chroot tree, generation in its marker";
        int have = installed(core);
        if (have == 0) return "none installed";
        return have + (have < REQUIRED ? " — outdated, needs " + REQUIRED : " — current");
    }

    public static long wipe(Context app) {
        File base = RootlessPaths.base(app);
        long freed = 0;
        freed += delete(RootlessPaths.rootfs(app));
        freed += delete(RootlessPaths.rootfsGz(app));
        freed += delete(new File(base, "rootfs.download"));
        freed += delete(RootlessPaths.kernel(app));
        freed += delete(RootlessPaths.initrd(app));

        delete(RootlessPaths.qmpSock(app));
        delete(RootlessPaths.serialSock(app));
        delete(RootlessPaths.serialLog(app));
        delete(RootlessPaths.termSock(app));
        delete(RootlessPaths.bootLog(app));
        delete(Engines.uml(app).console());

        forgetGuestIdentity(app);
        StrykerLog.i(TAG, "wiped the previous payload, freed " + freed + " bytes");
        return freed;
    }

    private static void forgetGuestIdentity(Context app) {
        java.util.LinkedHashSet<File> shares = new java.util.LinkedHashSet<>();
        File reachable = RootlessEngine.get(app).resolveShareDir();
        if (reachable != null) shares.add(reachable);
        shares.add(new File(Engines.uml(app).base(), "share"));

        for (File share : shares) {
            File ssh = new File(share, ".ssh");
            if (!ssh.isDirectory()) continue;
            for (String name : new String[]{"ready", "host_keys.pub", "host_fingerprint"}) {
                delete(new File(ssh, name));
            }
        }
    }

    private static long delete(File f) {
        if (f == null || !f.exists()) return 0;
        long size = f.isFile() ? f.length() : 0;
        return f.delete() ? size : 0;
    }
}
