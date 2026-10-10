package com.zalexdev.stryker.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class GuestFiles {

    private static final String FILE_MARK = "__GF__";
    private static final String DIR_MARK = "__GFD__";

    private GuestFiles() {}

    public enum FileState { OK, EMPTY, UNREADABLE, DIRECTORY, SPECIAL, MISSING }

    public enum DirState { POPULATED, EMPTY, NOT_A_DIR, MISSING }

    public static final class Resolved {
        public final String path;
        public final String problem;
        public final String detail;

        private Resolved(String path, String problem, String detail) {
            this.path = path;
            this.problem = problem;
            this.detail = detail == null ? "" : detail;
        }

        public boolean ok() {
            return path != null;
        }

        public String quoted() {
            return path == null ? null : shellQuote(path);
        }
    }

    public static Resolved unresolved(String problem) {
        return new Resolved(null, problem, "");
    }

    public static String shellQuote(String raw) {
        if (raw == null) return "''";
        return "'" + raw.replace("'", "'\\''") + "'";
    }

    public static List<String> shareCandidates(Core core, String subdir, String name) {
        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        addDir(dirs, core.guestShare(), subdir);
        addDir(dirs, "/sdcard/Stryker", subdir);
        addDir(dirs, "/host", subdir);
        try {
            File ext = core.getContext().getExternalFilesDir(null);
            if (ext != null) {
                addDir(dirs, ext.getAbsolutePath() + "/Stryker", subdir);
                addDir(dirs, "/sdcard/Android/data/" + core.getContext().getPackageName()
                        + "/files/Stryker", subdir);
            }
        } catch (Throwable ignored) {
        }
        addDir(dirs, core.getShareRoot(), subdir);
        List<String> out = new ArrayList<>();
        for (String d : dirs) out.add(d + "/" + name);
        return out;
    }

    private static void addDir(LinkedHashSet<String> into, String root, String subdir) {
        if (root == null || root.isEmpty()) return;
        String clean = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        into.add(subdir == null || subdir.isEmpty() ? clean : clean + "/" + subdir);
    }

    public static Resolved resolveShared(Core core, String subdir, String name, File hostFile) {
        if (name == null || name.isEmpty()) {
            return new Resolved(null, "no file was given to look for", "");
        }
        List<String> candidates = shareCandidates(core, subdir, name);
        if (hostFile != null) candidates.add(hostFile.getAbsolutePath());

        Resolved first = pick(core, candidates);
        if (first.ok()) return first;

        File staged = stage(core, subdir, name, hostFile);
        if (staged != null) {
            Resolved second = pick(core, candidates);
            if (second.ok()) return second;
        }
        return new Resolved(null, diagnose(core, subdir, name, hostFile, candidates), first.detail);
    }

    private static Resolved pick(Core core, List<String> candidates) {
        Map<String, FileState> seen = probeFiles(core, candidates);
        for (String p : candidates) {
            if (seen.get(p) == FileState.OK) return new Resolved(p, null, "");
        }
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, FileState> e : seen.entrySet()) {
            if (e.getValue() == FileState.MISSING) continue;
            if (detail.length() > 0) detail.append("; ");
            detail.append(e.getKey()).append(": ").append(describe(e.getValue()));
        }
        return new Resolved(null, null, detail.toString());
    }

    private static String describe(FileState s) {
        switch (s) {
            case EMPTY: return "0 bytes";
            case UNREADABLE: return "cannot be opened";
            case DIRECTORY: return "is a folder";
            case SPECIAL: return "is not a regular file";
            case OK: return "readable";
            default: return "not there";
        }
    }

    private static File stage(Core core, String subdir, String name, File hostFile) {
        if (hostFile == null || !hostFile.isFile() || hostFile.length() == 0) return null;
        File dst = new File(core.getShareRoot() + "/" + subdir, name);
        if (dst.getAbsolutePath().equals(hostFile.getAbsolutePath())) return null;
        if (dst.isFile() && dst.length() == hostFile.length()) return dst;
        try {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = new FileInputStream(hostFile);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst.isFile() && dst.length() > 0 ? dst : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String diagnose(Core core, String subdir, String name, File hostFile,
                                   List<String> candidates) {
        if (hostFile != null && !hostFile.isFile()) {
            return name + " is no longer in " + hostFile.getParent() + " on the phone";
        }
        if (hostFile != null && hostFile.length() == 0) {
            return name + " is empty on the phone (0 bytes)";
        }

        LinkedHashSet<String> dirs = new LinkedHashSet<>();
        for (String c : candidates) {
            int cut = c.lastIndexOf('/');
            if (cut > 0) dirs.add(c.substring(0, cut));
        }
        Map<String, DirState> states = probeDirs(core, new ArrayList<>(dirs));

        String mounted = null;
        String emptyDir = null;
        for (Map.Entry<String, DirState> e : states.entrySet()) {
            if (e.getValue() == DirState.POPULATED && mounted == null) mounted = e.getKey();
            if (e.getValue() == DirState.EMPTY && emptyDir == null) emptyDir = e.getKey();
        }

        String where = core.isRootless()
                ? core.guest().displayName()
                : "the chroot";
        if (mounted != null) {
            return where + " can read " + mounted + " but " + name + " is not in it";
        }
        if (emptyDir != null) {
            return where + " sees " + emptyDir + " as an empty folder, so the shared storage is "
                    + "not mounted into it" + remount(core);
        }
        return where + " has no " + subdir + " folder at all (" + join(dirs)
                + "), so the shared storage never reached it" + remount(core);
    }

    private static String remount(Core core) {
        return core.isRootless()
                ? " — restart the VM from the dashboard so it mounts the share again"
                : " — stop and start the chroot so it binds /sdcard/Stryker again";
    }

    private static String join(LinkedHashSet<String> dirs) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String d : dirs) {
            if (n++ >= 3) {
                sb.append(", …");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(d);
        }
        return sb.toString();
    }

    private static Map<String, FileState> probeFiles(Core core, List<String> paths) {
        Map<String, FileState> out = new LinkedHashMap<>();
        for (String p : paths) out.put(p, FileState.MISSING);
        if (paths.isEmpty()) return out;

        out.putAll(parseFiles(paths, run(core, fileProbeScript(paths))));
        return out;
    }

    static String fileProbeScript(List<String> paths) {
        StringBuilder sb = new StringBuilder("for __p in");
        for (String p : paths) sb.append(' ').append(shellQuote(p));
        sb.append("; do ")
          .append("if [ -d \"$__p\" ]; then __s=dir; ")
          .append("elif [ ! -e \"$__p\" ]; then __s=none; ")
          .append("elif [ ! -f \"$__p\" ]; then __s=special; ")
          .append("elif [ ! -s \"$__p\" ]; then __s=empty; ")
          .append("elif head -c 1 \"$__p\" >/dev/null 2>&1 ")
          .append("|| dd if=\"$__p\" bs=1 count=1 >/dev/null 2>&1; then __s=ok; ")
          .append("else __s=noread; fi; ")
          .append("printf '").append(FILE_MARK).append(" %s %s\\n' \"$__s\" \"$__p\"; done");
        return sb.toString();
    }

    static String dirProbeScript(List<String> dirs) {
        StringBuilder sb = new StringBuilder("for __d in");
        for (String d : dirs) sb.append(' ').append(shellQuote(d));
        sb.append("; do ")
          .append("if [ ! -e \"$__d\" ]; then __s=none; ")
          .append("elif [ ! -d \"$__d\" ]; then __s=notdir; ")
          .append("elif [ -n \"$(ls -A \"$__d\" 2>/dev/null | head -n 1)\" ]; then __s=full; ")
          .append("else __s=empty; fi; ")
          .append("printf '").append(DIR_MARK).append(" %s %s\\n' \"$__s\" \"$__d\"; done");
        return sb.toString();
    }

    static Map<String, FileState> parseFiles(List<String> paths, List<String> output) {
        Map<String, FileState> out = new LinkedHashMap<>();
        for (String p : paths) out.put(p, FileState.MISSING);
        for (String line : output) {
            String[] parts = split(line, FILE_MARK);
            if (parts == null) continue;
            if (out.containsKey(parts[1])) out.put(parts[1], fileState(parts[0]));
        }
        return out;
    }

    static Map<String, DirState> parseDirs(List<String> dirs, List<String> output) {
        Map<String, DirState> out = new LinkedHashMap<>();
        for (String d : dirs) out.put(d, DirState.MISSING);
        for (String line : output) {
            String[] parts = split(line, DIR_MARK);
            if (parts == null) continue;
            if (out.containsKey(parts[1])) out.put(parts[1], dirState(parts[0]));
        }
        return out;
    }

    private static Map<String, DirState> probeDirs(Core core, List<String> dirs) {
        Map<String, DirState> out = new LinkedHashMap<>();
        for (String d : dirs) out.put(d, DirState.MISSING);
        if (dirs.isEmpty()) return out;

        out.putAll(parseDirs(dirs, run(core, dirProbeScript(dirs))));
        return out;
    }

    private static String[] split(String line, String mark) {
        if (line == null) return null;
        int at = line.indexOf(mark);
        if (at < 0) return null;
        String rest = line.substring(at + mark.length()).trim();
        int space = rest.indexOf(' ');
        if (space <= 0) return null;
        String path = rest.substring(space + 1).trim();
        if (path.isEmpty()) return null;
        return new String[]{rest.substring(0, space), path};
    }

    private static FileState fileState(String token) {
        if ("ok".equals(token)) return FileState.OK;
        if ("empty".equals(token)) return FileState.EMPTY;
        if ("noread".equals(token)) return FileState.UNREADABLE;
        if ("dir".equals(token)) return FileState.DIRECTORY;
        if ("special".equals(token)) return FileState.SPECIAL;
        return FileState.MISSING;
    }

    private static DirState dirState(String token) {
        if ("full".equals(token)) return DirState.POPULATED;
        if ("empty".equals(token)) return DirState.EMPTY;
        if ("notdir".equals(token)) return DirState.NOT_A_DIR;
        return DirState.MISSING;
    }

    private static List<String> run(Core core, String command) {
        try {
            List<String> out = core.customChrootCommand(command, true);
            return out == null ? new ArrayList<String>() : out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }
}
