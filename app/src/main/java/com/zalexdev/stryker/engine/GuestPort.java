package com.zalexdev.stryker.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import com.stryker.terminal.bridge.StrykerLog;

public final class GuestPort {

    private static final String TAG = "GuestPort";

    static final int SEARCH_SPAN = 100;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int BANNER_LIMIT = 256;

    private GuestPort() {}

    public enum Reach {
        NOTHING_LISTENING,
        NO_ANSWER,
        GUEST_CLOSED,
        OTHER,
        SSH
    }

    public static final class Probe {
        public final Reach reach;
        public final String detail;

        Probe(Reach reach, String detail) {
            this.reach = reach;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static Probe probe(int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            try {
                s.connect(new InetSocketAddress(RootlessPaths.HOST_LOOPBACK, port),
                        Math.max(250, Math.min(timeoutMs, CONNECT_TIMEOUT_MS)));
            } catch (IOException e) {
                return new Probe(Reach.NOTHING_LISTENING, e.getMessage());
            }
            byte[] buf = new byte[BANNER_LIMIT];
            int n = 0;
            try {
                s.setSoTimeout(Math.max(250, timeoutMs));
                InputStream in = s.getInputStream();
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) break;
                    n += r;
                    if (hasSshLine(buf, n) || lineCount(buf, n) >= 4) break;
                }
            } catch (SocketTimeoutException e) {
                if (n == 0) return new Probe(Reach.NO_ANSWER, "no bytes within " + timeoutMs + "ms");
            } catch (IOException e) {
                if (n == 0) return new Probe(Reach.GUEST_CLOSED, e.getMessage());
            }
            if (n == 0) return new Probe(Reach.GUEST_CLOSED, "closed without sending anything");
            String head = new String(buf, 0, n, StandardCharsets.US_ASCII);
            String banner = sshLine(head, false);
            if (banner != null) return new Probe(Reach.SSH, banner);
            return new Probe(Reach.OTHER, firstLine(head));
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    public static boolean free(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(RootlessPaths.HOST_LOOPBACK), port), 1);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (ss != null) {
                try { ss.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static int claim(int preferred, String forWhat) throws IOException {
        if (free(preferred)) return preferred;
        String holder = describeHolder(preferred);
        for (int p = preferred + 1; p < preferred + SEARCH_SPAN && p <= 65535; p++) {
            if (!free(p)) continue;
            StrykerLog.w(TAG, "port " + preferred + " is taken (" + holder + "), using " + p);
            GuestExec.logToStore(RootlessPaths.HOST_LOOPBACK + ":" + preferred
                    + " is already taken on the phone (" + holder + "), so " + forWhat
                    + " goes through " + RootlessPaths.HOST_LOOPBACK + ":" + p + " instead");
            return p;
        }
        throw new IOException("no free loopback port for " + forWhat + " between " + preferred
                + " and " + (preferred + SEARCH_SPAN - 1) + " — " + RootlessPaths.HOST_LOOPBACK
                + ":" + preferred + " is held by " + holder);
    }

    static String describeHolder(int port) {
        Probe p = probe(port, 1500);
        switch (p.reach) {
            case SSH:
                return "another SSH server answers there: " + p.detail;
            case OTHER:
                return "another service answers there: " + p.detail;
            case NO_ANSWER:
                return "something accepts connections there but stays silent";
            case GUEST_CLOSED:
                return "something accepts connections there and drops them";
            default:
                return "another process has it bound";
        }
    }

    public static String explain(Reach reach, int port, String detail) {
        String where = RootlessPaths.HOST_LOOPBACK + ":" + port;
        String why = detail == null || detail.isEmpty() ? "" : " (" + detail + ")";
        if (reach == null) return "the app never got to check " + where;
        switch (reach) {
            case NOTHING_LISTENING:
                return "nothing ever listened on " + where + ", so the port forward into the guest"
                        + " never came up" + why;
            case NO_ANSWER:
                return where + " is forwarded, but the guest never answered behind it — its"
                        + " network did not come up";
            case GUEST_CLOSED:
                return "the guest's network is up, but nothing listens on its port "
                        + RootlessPaths.GUEST_SSH_PORT + " — sshd never started";
            case OTHER:
                return "something other than the guest's sshd answers on " + where + why;
            case SSH:
            default:
                return "sshd answers on " + where + ", but the app could not log in" + why;
        }
    }

    private static boolean hasSshLine(byte[] buf, int n) {
        return sshLine(new String(buf, 0, n, StandardCharsets.US_ASCII), true) != null;
    }

    private static String sshLine(String head, boolean completeOnly) {
        String[] lines = head.split("\n", -1);
        int upto = completeOnly ? lines.length - 1 : lines.length;
        for (int i = 0; i < upto; i++) {
            String t = lines[i].trim();
            if (t.startsWith("SSH-")) return t;
        }
        return null;
    }

    private static int lineCount(byte[] buf, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) if (buf[i] == '\n') c++;
        return c;
    }

    private static String firstLine(String head) {
        int nl = head.indexOf('\n');
        String t = nl >= 0 ? head.substring(0, nl) : head;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length() && sb.length() < 80; i++) {
            char c = t.charAt(i);
            if (c == '\r') continue;
            sb.append(c >= 0x20 && c < 0x7f ? c : '.');
        }
        String line = sb.toString().trim();
        return line.isEmpty() ? "an unrecognised greeting" : line;
    }
}
