package com.zalexdev.stryker.engine;

import com.stryker.terminal.bridge.StrykerLog;

final class SshBootWatch {

    private static final String TAG = "SshBootWatch";

    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final long GRACE_NETWORK_MS = 90_000;
    private static final long GRACE_SSHD_MS = 90_000;
    private static final long GRACE_LOGIN_MS = 120_000;
    private static final long GRACE_CONSOLE_MS = 60_000;

    private final int port;
    private final long started;
    private final long hardStop;
    private long deadline;

    private GuestPort.Reach best;
    private String bestDetail = "";
    private boolean loggedIn;
    private boolean answered;
    private boolean consoleLogin;
    private String news;

    SshBootWatch(int port, long baseMs, long hardLimitMs) {
        this.port = port;
        this.started = System.currentTimeMillis();
        this.deadline = started + baseMs;
        this.hardStop = started + Math.max(baseMs, hardLimitMs);
    }

    int port() {
        return port;
    }

    boolean expired() {
        return System.currentTimeMillis() >= deadline;
    }

    long elapsedSeconds() {
        return (System.currentTimeMillis() - started) / 1000L;
    }

    boolean worthLoggingIn() {
        if (GuestSsh.isConnected()) {
            noteLoggedIn();
            return true;
        }
        if (best == GuestPort.Reach.SSH) return true;
        GuestPort.Probe p = GuestPort.probe(port, PROBE_TIMEOUT_MS);
        note(p);
        return p.reach == GuestPort.Reach.SSH;
    }

    void afterLoginAttempt(boolean pong) {
        if (GuestSsh.isConnected()) noteLoggedIn();
        if (pong) answered = true;
    }

    void noteConsoleLogin() {
        if (consoleLogin) return;
        consoleLogin = true;
        extend(GRACE_CONSOLE_MS, "guest console reached its login prompt");
    }

    String takeNews() {
        String n = news;
        news = null;
        return n;
    }

    boolean guestNetworkSeen() {
        return loggedIn || rank(best) >= rank(GuestPort.Reach.GUEST_CLOSED);
    }

    String reason(String console) {
        String ssh = explain();
        if (guestNetworkSeen() || console == null || console.trim().isEmpty()) return ssh;
        return console + " (" + ssh + ")";
    }

    String explain() {
        String where = RootlessPaths.HOST_LOOPBACK + ":" + port;
        String last = GuestSsh.lastFailure();
        String why = last.isEmpty() ? "" : " (last error: " + last + ")";
        if (answered) {
            return "the guest answers over SSH on " + where + ", but its shell never came up as root";
        }
        if (loggedIn) {
            return "the app logged in over SSH on " + where + ", but the guest was too slow to open"
                    + " a command channel" + why;
        }
        if (best == GuestPort.Reach.SSH) {
            return GuestPort.explain(best, port, last.isEmpty() ? bestDetail : last);
        }
        return GuestPort.explain(best, port, bestDetail);
    }

    private void noteLoggedIn() {
        if (loggedIn) return;
        loggedIn = true;
        extend(GRACE_LOGIN_MS, "logged in to the guest over SSH");
    }

    private void note(GuestPort.Probe p) {
        if (rank(p.reach) <= rank(best)) return;
        best = p.reach;
        bestDetail = p.detail;
        if (p.reach == GuestPort.Reach.GUEST_CLOSED) {
            extend(GRACE_NETWORK_MS, "guest network is up, sshd is not listening yet");
        } else if (p.reach == GuestPort.Reach.SSH) {
            extend(GRACE_SSHD_MS, "sshd answers on " + RootlessPaths.HOST_LOOPBACK + ":" + port
                    + " (" + p.detail + ")");
        }
    }

    private void extend(long graceMs, String why) {
        long now = System.currentTimeMillis();
        long target = Math.min(hardStop, now + graceMs);
        String msg = why + " at " + ((now - started) / 1000L) + "s";
        if (target > deadline) {
            deadline = target;
            msg += ", boot window extended to " + ((deadline - started) / 1000L) + "s";
        }
        StrykerLog.i(TAG, msg);
        news = msg;
    }

    private static int rank(GuestPort.Reach r) {
        if (r == null) return -1;
        switch (r) {
            case NOTHING_LISTENING: return 0;
            case NO_ANSWER: return 1;
            case OTHER: return 1;
            case GUEST_CLOSED: return 2;
            case SSH: return 3;
            default: return -1;
        }
    }
}
