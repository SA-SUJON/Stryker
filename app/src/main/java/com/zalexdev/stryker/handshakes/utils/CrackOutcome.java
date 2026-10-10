package com.zalexdev.stryker.handshakes.utils;

import java.util.Locale;

public final class CrackOutcome {

    private String problem;
    private boolean sawDictionary;
    private boolean sawPackets;
    private boolean exhausted;

    public synchronized String problem() {
        return problem;
    }

    public synchronized boolean sawPackets() {
        return sawPackets;
    }

    public synchronized void note(String raw) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;
        String low = line.toLowerCase(Locale.ROOT);

        if (low.contains("read ") && low.contains("packets")) sawPackets = true;
        if (low.contains("passphrase not in dictionary") || low.contains("key not found")) {
            exhausted = true;
        }
        if (low.contains("tested") && low.contains("keys")) sawDictionary = true;

        if (problem != null) return;

        if (low.contains("opening dictionary") && low.contains("failed")) {
            problem = "aircrack-ng could not open the wordlist: " + inParens(line);
        } else if (low.contains("fopen(dictionary)")) {
            problem = "aircrack-ng could not open the wordlist: " + after(line, ":");
        } else if (low.contains("specify a dictionary") || low.contains("specify dictionary")) {
            problem = "aircrack-ng was started without a wordlist";
        } else if (low.contains("no networks found")) {
            problem = "this capture holds no network aircrack-ng can work on";
        } else if (low.contains("packets contained no eapol data")
                || low.contains("no valid wpa handshakes")) {
            problem = "this capture has no usable handshake";
        } else if (low.contains("unsupported file format")
                || low.contains("unknown file format")) {
            problem = "aircrack-ng does not understand this capture file";
        } else if (low.contains("not found") && low.contains("aircrack-ng")) {
            problem = "aircrack-ng is not installed in the guest";
        } else if (low.contains("permission denied")) {
            problem = "permission denied: " + line;
        } else if (low.contains("no such file or directory")) {
            problem = "a file aircrack-ng needed is not there: " + line;
        } else if (low.startsWith("error:") || low.startsWith("fatal:")) {
            problem = line;
        }
    }

    public synchronized void noteExit(int code) {
        if (problem != null) return;
        if (code == 0 || exhausted || sawDictionary) return;
        if (code < 0) {
            problem = "the cracker stopped before it finished";
            return;
        }
        problem = sawPackets
                ? "aircrack-ng read the capture but stopped with code " + code
                : "aircrack-ng stopped with code " + code + " before reading the capture";
    }

    public synchronized void noteNoOutput() {
        fail("the cracker produced no output at all");
    }

    public synchronized void fail(String why) {
        if (problem == null && why != null && !why.isEmpty()) problem = why;
    }

    private static String inParens(String line) {
        int open = line.lastIndexOf('(');
        int close = line.lastIndexOf(')');
        if (open >= 0 && close > open) return line.substring(open + 1, close);
        return line;
    }

    private static String after(String line, String sep) {
        int at = line.lastIndexOf(sep);
        return at >= 0 && at + sep.length() < line.length()
                ? line.substring(at + sep.length()).trim()
                : line;
    }
}
