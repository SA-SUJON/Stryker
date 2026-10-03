package com.zalexdev.stryker.engine;

import android.content.Context;

import java.io.File;

public final class NativeExec {

    private NativeExec() {
    }

    public static String check(Context app, File... bins) {
        String dir = null;
        try {
            dir = app.getApplicationInfo().nativeLibraryDir;
        } catch (Throwable ignored) {
        }
        for (File bin : bins) {
            if (bin == null) continue;
            if (!bin.exists()) {
                return bin.getName() + " is not on disk. The app was installed with its native"
                        + " binaries left packed inside the APK, so there is nothing to run at "
                        + (dir == null ? "the native library directory" : dir)
                        + ". Reinstalling the APK restores them.";
            }
            if (bin.length() == 0) {
                return bin.getName() + " is empty — the install did not finish. Reinstall the app.";
            }
            if (!bin.canExecute()) {
                return "Android will not execute " + bin.getName() + " at " + bin.getParent()
                        + ". The package manager unpacks these with the execute bit set, so this"
                        + " is a broken or partly applied install — reinstall the app.";
            }
        }
        return null;
    }

    public static String explain(File bin, Throwable t) {
        String raw = t == null || t.getMessage() == null ? "" : t.getMessage();
        String name = bin == null ? "the engine" : bin.getName();

        if (raw.contains("Permission denied")) {
            return name + " could not be started: Android refused the exec. A program may only be"
                    + " run from the directory the APK's native binaries are unpacked into"
                    + (bin == null ? "" : " (" + bin.getParent() + ")")
                    + "; anywhere under the app's own data directory is refused outright, and no"
                    + " amount of chmod changes that.";
        }
        if (raw.contains("Exec format error")) {
            return name + " could not be started: this device's kernel will not load it. That is"
                    + " either a binary for the wrong architecture, or one laid out for 4 KB memory"
                    + " pages on a device that uses 16 KB pages.";
        }
        if (raw.contains("No such file")) {
            return name + " could not be started: the file, or something it needs to load, is not"
                    + " there. Reinstalling the app puts the bundled binaries back.";
        }
        if (raw.contains("Out of memory") || raw.contains("Cannot allocate")) {
            return name + " could not be started: the system refused the memory it asked for."
                    + " Close some apps and try again, or give the guest less RAM in its settings.";
        }
        return name + " could not be started: " + (raw.isEmpty() ? t.toString() : raw);
    }
}
