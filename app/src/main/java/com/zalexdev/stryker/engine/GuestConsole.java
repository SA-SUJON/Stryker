package com.zalexdev.stryker.engine;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import com.stryker.terminal.bridge.StrykerLog;

final class GuestConsole {

    private static final String TAG = "GuestConsole";
    private static final String MARK = "__STRYKER_CON__";
    private static final int READ_TIMEOUT_MS = 20000;

    private GuestConsole() {
    }

    static ArrayList<String> run(String command, String socketPath, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        if (command == null || socketPath == null) return out;
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setSoTimeout(timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            OutputStream os = sock.getOutputStream();
            os.write(("\n" + command + "\n" + "echo " + MARK + "$?\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream is = sock.getInputStream();
            StringBuilder buf = new StringBuilder();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            while (System.currentTimeMillis() < deadline) {
                int r = is.read(chunk);
                if (r <= 0) break;
                buf.append(new String(chunk, 0, r, StandardCharsets.UTF_8));
                if (countOf(buf, MARK) >= 2) break;
            }
            collect(buf.toString(), out);
        } catch (Exception e) {
            StrykerLog.w(TAG, "console command failed: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static int countOf(CharSequence hay, String needle) {
        int n = 0, from = 0;
        String s = hay.toString();
        while (true) {
            int i = s.indexOf(needle, from);
            if (i < 0) return n;
            n++;
            from = i + needle.length();
        }
    }

    private static void collect(String raw, ArrayList<String> out) {
        for (String line : raw.split("\r?\n")) {
            String t = line.replace("\r", "").trim();
            if (t.isEmpty()) continue;
            if (t.contains(MARK)) continue;
            if (t.startsWith("echo " + MARK)) continue;
            if (t.endsWith("#") && t.contains("@")) continue;
            out.add(t);
        }
    }
}
