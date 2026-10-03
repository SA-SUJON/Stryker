package com.zalexdev.stryker.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.StatFs;

import com.zalexdev.stryker.R;
import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.stryker.terminal.bridge.StrykerLog;

public final class DeviceCapabilities {

    private static final String TAG = "DeviceCapabilities";

    public static final String K_DONE = "setup_probe_done";
    public static final String K_RECOMMENDED = "setup_recommended_engine";
    public static final String K_ROOT_OK = "setup_root_granted";
    public static final String K_SUMMARY = "setup_probe_summary";
    public static final String K_SKIPPED = "setup_probe_skipped";

    public static final String K_PLAN = "setup_engine_plan";

    public static final String K_UML_NOTES = "setup_uml_probe_notes";

    private static final long ROOT_TIMEOUT_MS = 30_000;

    private static final long MIN_FREE_BYTES = 2L * VmSpecs.GB;
    private static final long COMFORTABLE_FREE_BYTES = 6L * VmSpecs.GB;
    private static final long MIN_RAM_BYTES = 1536L * 1024L * 1024L;
    private static final long COMFORTABLE_RAM_BYTES = 3L * VmSpecs.GB;

    private DeviceCapabilities() {}

    public enum Outcome {
        OK,
        WARN,
        FAIL,
        SKIPPED
    }

    public enum Check {
        ARCH(R.string.caps_check_arch),
        ROOT(R.string.caps_check_root),
        MEMORY(R.string.caps_check_memory),
        STORAGE(R.string.caps_check_storage),
        NETWORK(R.string.caps_check_network),
        UML(R.string.caps_check_uml),
        QEMU(R.string.caps_check_vm);

        public final int labelRes;

        Check(int labelRes) { this.labelRes = labelRes; }
    }

    public static final class Finding {
        public final Check check;
        public final Outcome outcome;
        public final String detail;

        Finding(Check check, Outcome outcome, String detail) {
            this.check = check;
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }
    }

    public interface Listener {
        void onCheckStarted(Check check);

        void onCheckFinished(Finding finding);
    }

    public static final class Report {
        public final List<EngineType> plan;
        public final List<Finding> findings;
        public final boolean rootGranted;
        public final String summary;
        public final List<String> umlNotes;

        Report(List<EngineType> plan, List<Finding> findings, boolean rootGranted,
               String summary, List<String> umlNotes) {
            this.plan = plan;
            this.findings = findings;
            this.rootGranted = rootGranted;
            this.summary = summary;
            this.umlNotes = umlNotes == null ? new ArrayList<>() : umlNotes;
        }

        public EngineType recommended() {
            return plan.isEmpty() ? null : plan.get(0);
        }

        public boolean needsManualInstall() {
            return plan.isEmpty();
        }
    }

    public static Report run(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Core core = new Core(app);
        List<Finding> findings = new ArrayList<>();

        Finding arch = step(listener, findings, Check.ARCH, DeviceCapabilities::probeArch);
        Finding root = step(listener, findings, Check.ROOT, () -> probeRoot(core));
        Finding memory = step(listener, findings, Check.MEMORY, () -> probeMemory(app));
        Finding storage = step(listener, findings, Check.STORAGE, () -> probeStorage(app));
        Finding network = step(listener, findings, Check.NETWORK, () -> probeNetwork(app));

        boolean rootGranted = root.outcome == Outcome.OK;
        boolean arm64 = arch.outcome != Outcome.FAIL;

        if (rootGranted) {
            String why = "not needed · root is available";
            skip(listener, findings, Check.UML, why);
            skip(listener, findings, Check.QEMU, why);
            List<EngineType> plan = new ArrayList<>();
            plan.add(EngineType.CHROOT);
            return new Report(plan, findings, true,
                    summarise(app, plan, true, memory, storage, network), null);
        }

        UmlProbe.Result[] umlProbe = new UmlProbe.Result[1];
        Finding uml = step(listener, findings, Check.UML, () -> {
            if (!arm64) return new Finding(Check.UML, Outcome.FAIL, "needs 64-bit ARM");
            umlProbe[0] = UmlProbe.run(app);
            return readUml(umlProbe[0]);
        });
        step(listener, findings, Check.QEMU, () -> probeQemu(arm64, storage.outcome));

        List<EngineType> plan = planFor(arm64, uml.outcome, storage.outcome);
        return new Report(plan, findings, false,
                summarise(app, plan, false, memory, storage, network),
                umlProbe[0] == null ? null : umlProbe[0].notes);
    }

    private static List<EngineType> planFor(boolean arm64, Outcome uml, Outcome storage) {
        List<EngineType> plan = new ArrayList<>();
        if (!arm64) return plan;
        if (uml != Outcome.FAIL) plan.add(EngineType.UML);
        if (storage != Outcome.FAIL) plan.add(EngineType.ROOTLESS);
        return plan;
    }

    public static void persist(Core core, Report report) {
        if (core == null || report == null) return;
        core.putBoolean(K_DONE, true);
        core.putBoolean(K_SKIPPED, false);
        core.putBoolean(K_ROOT_OK, report.rootGranted);
        EngineType first = report.recommended();
        core.putString(K_RECOMMENDED, first == null ? "" : first.name());
        core.putString(K_PLAN, join(report.plan));
        core.putString(K_SUMMARY, report.summary);
        core.putString(K_UML_NOTES, android.text.TextUtils.join("\n", report.umlNotes));
    }

    public static void rememberUmlNotes(Core core, UmlProbe.Result probe) {
        if (core == null || probe == null) return;
        core.putString(K_UML_NOTES, android.text.TextUtils.join("\n", probe.notes));
    }

    public static EngineType recommended(Core core) {
        if (core == null) return null;
        return parse(core.getString(K_RECOMMENDED));
    }

    public static List<EngineType> plan(Core core) {
        List<EngineType> out = new ArrayList<>();
        if (core == null) return out;
        String raw = core.getString(K_PLAN);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            EngineType t = parse(part.trim());
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static List<String> umlNotes(Core core) {
        List<String> out = new ArrayList<>();
        if (core == null) return out;
        String raw = core.getString(K_UML_NOTES);
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    private static String join(List<EngineType> plan) {
        StringBuilder sb = new StringBuilder();
        for (EngineType t : plan) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t.name());
        }
        return sb.toString();
    }

    private static EngineType parse(String name) {
        if (name == null || name.isEmpty()) return null;
        for (EngineType t : EngineType.values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }


    private static long hostPageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static Finding probeArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equals(abi)) {
                    long page = hostPageSize();
                    String pages = page > 0 && page != 4096L ? " · " + (page / 1024) + " KB pages" : "";
                    return new Finding(Check.ARCH, Outcome.OK,
                            "arm64-v8a · " + Build.MODEL + pages);
                }
            }
        }
        return new Finding(Check.ARCH, Outcome.FAIL,
                "needs 64-bit ARM · reports "
                        + (abis == null || abis.length == 0 ? "nothing" : abis[0]));
    }

    private static Finding probeMemory(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return new Finding(Check.MEMORY, Outcome.WARN, "could not be read");
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long total = info.totalMem;
            String text = gb(total) + " total · " + gb(info.availMem) + " free";
            if (total < MIN_RAM_BYTES) {
                return new Finding(Check.MEMORY, Outcome.FAIL, text + " · too little");
            }
            if (total < COMFORTABLE_RAM_BYTES) {
                return new Finding(Check.MEMORY, Outcome.WARN, text + " · the guest will be slow");
            }
            return new Finding(Check.MEMORY, Outcome.OK, text);
        } catch (Throwable t) {
            return new Finding(Check.MEMORY, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeStorage(Context app) {
        try {
            File dir = app.getFilesDir();
            if (dir == null) return new Finding(Check.STORAGE, Outcome.WARN, "could not be read");
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long free = fs.getAvailableBytes();
            String text = gb(free) + " free";
            if (free < MIN_FREE_BYTES) {
                return new Finding(Check.STORAGE, Outcome.FAIL,
                        text + " · at least " + gb(MIN_FREE_BYTES) + " needed");
            }
            if (free < COMFORTABLE_FREE_BYTES) {
                return new Finding(Check.STORAGE, Outcome.WARN,
                        text + " · the disk cannot grow much");
            }
            return new Finding(Check.STORAGE, Outcome.OK, text);
        } catch (Throwable t) {
            return new Finding(Check.STORAGE, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeNetwork(Context app) {
        if (QemuInstaller.assetsPresent(app)) {
            return new Finding(Check.NETWORK, Outcome.OK, "not needed · bundled in the app");
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return new Finding(Check.NETWORK, Outcome.WARN, "could not be read");
            android.net.Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return new Finding(Check.NETWORK, Outcome.FAIL,
                        "offline · 500 MB to download");
            }
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (wifi || ethernet) {
                return new Finding(Check.NETWORK, Outcome.OK, wifi ? "Wi-Fi" : "Ethernet");
            }
            return new Finding(Check.NETWORK, Outcome.WARN,
                    "mobile data · 500 MB to download");
        } catch (Throwable t) {
            return new Finding(Check.NETWORK, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeRoot(Core core) {
        Boolean granted = withDeadline(ROOT_TIMEOUT_MS, () -> {
            java.util.ArrayList<String> out = core.customCommand("id", ROOT_TIMEOUT_MS);
            return Core.contains(out, "uid=0");
        });

        if (granted == null) {
            return new Finding(Check.ROOT, Outcome.WARN,
                    "no answer in " + (ROOT_TIMEOUT_MS / 1000) + "s · treated as denied");
        }
        if (granted) {
            return new Finding(Check.ROOT, Outcome.OK, "granted");
        }
        if (core.suSpawnFailed()) {
            return new Finding(Check.ROOT, Outcome.WARN, "not rooted");
        }
        return new Finding(Check.ROOT, Outcome.WARN, "denied");
    }

    private static Finding readUml(UmlProbe.Result probe) {
        if (probe == null) {
            return new Finding(Check.UML, Outcome.WARN, "could not be tested");
        }
        switch (probe.verdict) {
            case KERNEL_STARTS:
                return new Finding(Check.UML, Outcome.OK,
                        probe.detail + " · confirmed by SSH at install");
            case BLOCKED:
                return new Finding(Check.UML, Outcome.FAIL, probe.detail);
            case UNPROVEN:
            default:
                return new Finding(Check.UML, Outcome.WARN,
                        probe.detail + " · confirmed at install");
        }
    }

    private static Finding probeQemu(boolean arm64, Outcome storage) {
        if (!arm64) {
            return new Finding(Check.QEMU, Outcome.FAIL, "needs 64-bit ARM");
        }
        if (storage == Outcome.FAIL) {
            return new Finding(Check.QEMU, Outcome.FAIL, "not enough free storage");
        }
        return new Finding(Check.QEMU, Outcome.OK, "available · downloads on demand");
    }

    private interface Probe {
        Finding run();
    }

    private static void skip(Listener listener, List<Finding> into, Check check, String why) {
        Finding finding = new Finding(check, Outcome.SKIPPED, why);
        into.add(finding);
        if (listener != null) listener.onCheckFinished(finding);
    }

    private static Finding step(Listener listener, List<Finding> into, Check check, Probe probe) {
        if (listener != null) listener.onCheckStarted(check);
        Finding finding;
        try {
            finding = probe.run();
        } catch (Throwable t) {
            StrykerLog.w(TAG, "probe " + check + " threw", t);
            finding = new Finding(check, Outcome.FAIL, "check failed");
        }
        into.add(finding);
        if (listener != null) listener.onCheckFinished(finding);
        return finding;
    }

    private static <T> T withDeadline(long timeoutMs, Callable<T> work) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capability-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> future = exec.submit(work);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                future.cancel(true);
                return null;
            }
        } finally {
            exec.shutdownNow();
        }
    }

    private static String summarise(Context app, List<EngineType> plan, boolean rootGranted,
                                    Finding memory, Finding storage, Finding network) {
        if (plan.isEmpty()) return app.getString(R.string.caps_sum_none);

        StringBuilder sb = new StringBuilder();
        if (rootGranted) {
            sb.append(app.getString(R.string.caps_sum_root));
        } else if (plan.get(0) == EngineType.UML) {
            sb.append(app.getString(plan.size() > 1
                    ? R.string.caps_sum_uml_fallback : R.string.caps_sum_uml));
        } else {
            sb.append(app.getString(R.string.caps_sum_vm));
        }
        if (network.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_data));
        if (storage.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_storage));
        if (memory.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_memory));
        return sb.toString();
    }

    private static String gb(long bytes) {
        if (bytes <= 0) return "0 GB";
        double g = bytes / 1024.0 / 1024.0 / 1024.0;
        if (g < 1.0) {
            return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.US, "%.1f GB", g);
    }
}
