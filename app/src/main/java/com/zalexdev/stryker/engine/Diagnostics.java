package com.zalexdev.stryker.engine;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import androidx.core.content.FileProvider;

import com.zalexdev.stryker.BuildConfig;
import com.zalexdev.stryker.logger.LogFilter;
import com.zalexdev.stryker.logger.LogStore;
import com.stryker.terminal.bridge.StrykerLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Diagnostics {

    private static final String TAG = "Diagnostics";

    private Diagnostics() {
    }

    public static String deviceReport(Context app) {
        StringBuilder b = new StringBuilder();
        b.append("Stryker diagnostics\n");
        b.append("app            ").append(BuildConfig.VERSION_NAME)
                .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n");
        b.append("collected      ").append(System.currentTimeMillis()).append('\n');
        b.append('\n');
        b.append("host page size ").append(pageSize()).append('\n');
        b.append("device         ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        b.append("android        ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        b.append("build          ").append(Build.DISPLAY).append('\n');
        b.append("abis           ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        b.append("kernel         ").append(System.getProperty("os.version")).append('\n');
        b.append("cpus           ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        b.append('\n');

        com.zalexdev.stryker.utils.Core core = new com.zalexdev.stryker.utils.Core(app);
        b.append("engine         ").append(EngineType.active(core)).append('\n');
        b.append("rootless       ").append(core.isRootless()).append('\n');
        b.append("payload        ").append(EnginePayload.describe(core)).append('\n');
        GuestEngine up = Engines.running(app);
        b.append("running        ").append(up == null ? "none" : up.displayName()).append('\n');
        b.append('\n');

        b.append("native dir     ").append(nativeDir(app)).append('\n');
        for (String name : new String[]{"libqemu.so", "libslirp.so", "libuml.so", "libstub.so",
                "libumnet.so", "libpasst.so", "libumusb.so", "libbash.so"}) {
            File f = new File(nativeDir(app), name);
            b.append("  ").append(pad(name, 14));
            if (f.exists()) {
                b.append(f.length()).append(" bytes exec=").append(f.canExecute());
            } else {
                b.append("MISSING");
            }
            b.append('\n');
        }
        return b.toString();
    }

    public static File collect(Context app) {
        try {
            File out = com.zalexdev.stryker.utils.Exports.fresh(
                    app.getCacheDir(), "stryker-diagnostics", ".zip");
            ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out));
            try {
                write(zip, "device.txt", deviceReport(app).getBytes(StandardCharsets.UTF_8));

                copy(zip, "uml-console.log", Engines.uml(app).console());
                copy(zip, "qemu-serial.log", RootlessPaths.serialLog(app));

                File log = new File(app.getCacheDir(), out.getName() + ".log");
                LogStore store = LogStore.from(app);
                store.export(log, new LogFilter());
                copy(zip, "stryker-log.txt", log);
                log.delete();
            } finally {
                zip.close();
            }
            StrykerLog.i(TAG, "diagnostics bundle written, " + out.length() + " bytes");
            return out;
        } catch (Throwable t) {
            StrykerLog.e(TAG, "could not collect diagnostics", t);
            return null;
        }
    }

    public static Intent shareIntent(Context app, File file) {
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".provider", file);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Stryker diagnostics");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, "Send diagnostics");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return chooser;
    }

    private static long pageSize() {
        try {
            return Os.sysconf(OsConstants._SC_PAGESIZE);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String nativeDir(Context app) {
        try {
            return app.getApplicationInfo().nativeLibraryDir;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void copy(ZipOutputStream zip, String name, File src) {
        if (src == null || !src.isFile() || src.length() == 0) return;
        try {
            zip.putNextEntry(new ZipEntry(name));
            FileInputStream in = new FileInputStream(src);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            } finally {
                in.close();
            }
            zip.closeEntry();
        } catch (Throwable t) {
            StrykerLog.w(TAG, "could not add " + name + ": " + t);
        }
    }

    private static void write(OutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        ((ZipOutputStream) zip).putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        ((ZipOutputStream) zip).closeEntry();
    }

    private static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    private static String join(String[] items) {
        if (items == null || items.length == 0) return "none";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) b.append(", ");
            b.append(items[i]);
        }
        return b.toString();
    }
}
