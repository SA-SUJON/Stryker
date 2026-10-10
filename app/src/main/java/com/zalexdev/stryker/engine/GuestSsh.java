package com.zalexdev.stryker.engine;

import android.content.Context;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.stryker.terminal.bridge.StrykerLog;

public final class GuestSsh {

    private static final String TAG = "GuestSsh";
    private static final String USER = "root";

    private static final String SHARE_SSH_DIR = ".ssh";
    private static final String SHARE_AUTHORIZED_KEYS = "authorized_keys";
    private static final String SHARE_HOST_KEYS = "host_keys.pub";
    private static final String SHARE_READY = "ready";

    private static volatile Context appContext;
    private static volatile File shareDir;
    private static volatile int port = RootlessPaths.HOST_SSH_PORT;

    private static Session session;
    private static final Object LOCK = new Object();

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final String PONG = "__STRYKER_PONG__";

    private static final Map<Integer, Integer> wantedForwards = new ConcurrentHashMap<>();

    private static volatile long lastLoss;

    public static long lastLossAt() {
        return lastLoss;
    }

    static void noteUnreachable() {
        lastLoss = System.currentTimeMillis();
    }

    static void dropIfDead() {
        synchronized (LOCK) {
            lastLoss = System.currentTimeMillis();
            Session s = session;
            if (s != null && !s.isConnected()) {
                try { s.disconnect(); } catch (Exception ignored) {}
                session = null;
            }
        }
    }

    static void noteSessionLost() {
        synchronized (LOCK) {
            lastLoss = System.currentTimeMillis();
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) {}
                session = null;
            }
        }
    }

    private GuestSsh() {}

    public static void configure(Context context, File share, int sshPort) {
        appContext = context.getApplicationContext();
        shareDir = share;
        port = sshPort > 0 ? sshPort : RootlessPaths.HOST_SSH_PORT;
        wantedForwards.clear();
        lastPingFailure = null;
        disconnect();
    }

    public static int port() {
        return port;
    }

    static String lastFailure() {
        String f = lastPingFailure;
        return f == null ? "" : f;
    }

    private static File keyDir() {
        File d = new File(appContext.getFilesDir(), "ssh");
        d.mkdirs();
        return d;
    }

    public static File privateKey() { return new File(keyDir(), "id_guest"); }
    public static File publicKey()  { return new File(keyDir(), "id_guest.pub"); }

    public static synchronized void ensureKeypair() throws JSchException, IOException {
        File priv = privateKey();
        if (priv.exists() && priv.length() > 0) return;

        JSch jsch = new JSch();
        if (!writeKeypair(jsch, KeyPair.ED25519, 0)) {
            StrykerLog.i(TAG, "Ed25519 cannot be stored by this jsch build; using RSA");
            if (!writeKeypair(jsch, KeyPair.RSA, 3072)) {
                throw new JSchException("could not generate a usable keypair for the guest");
            }
        }

        priv.setReadable(false, false);
        priv.setReadable(true, true);
        priv.setWritable(false, false);
        StrykerLog.i(TAG, "generated a new keypair for the guest");
    }

    private static boolean writeKeypair(JSch jsch, int type, int bits) {
        KeyPair kp = null;
        try {
            kp = bits > 0 ? KeyPair.genKeyPair(jsch, type, bits) : KeyPair.genKeyPair(jsch, type);
            kp.writePrivateKey(privateKey().getAbsolutePath());
            kp.writePublicKey(publicKey().getAbsolutePath(), "stryker@" + android.os.Build.MODEL);
            return privateKey().length() > 0 && publicKey().length() > 0;
        } catch (Throwable t) {
            StrykerLog.i(TAG, "keypair type " + type + " unusable here: " + t);
            privateKey().delete();
            publicKey().delete();
            return false;
        } finally {
            if (kp != null) kp.dispose();
        }
    }

    public static void publishPublicKey() throws IOException, JSchException {
        ensureKeypair();
        File share = shareDir;
        if (share == null) throw new IOException("no share directory configured");
        File dir = new File(share, SHARE_SSH_DIR);
        dir.mkdirs();
        byte[] pub = readAll(publicKey());
        File dst = new File(dir, SHARE_AUTHORIZED_KEYS);
        try (OutputStream out = new FileOutputStream(dst)) {
            out.write(pub);
            out.flush();
        }
    }

    public static boolean guestReported() {
        File share = shareDir;
        if (share == null) return false;
        File ready = new File(new File(share, SHARE_SSH_DIR), SHARE_READY);
        return ready.isFile() && ready.length() > 0;
    }

    private static byte[] knownHosts() {
        File share = shareDir;
        if (share == null) return null;
        File pub = new File(new File(share, SHARE_SSH_DIR), SHARE_HOST_KEYS);
        if (!pub.isFile() || pub.length() == 0) return null;
        try {
            String text = new String(readAll(pub), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                sb.append('[').append(RootlessPaths.HOST_LOOPBACK).append("]:").append(port)
                  .append(' ').append(line).append('\n');
            }
            return sb.length() == 0 ? null : sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static Session session() throws JSchException, IOException {
        synchronized (LOCK) {
            if (session != null && session.isConnected()) return session;
            session = null;

            if (appContext == null) throw new JSchException("GuestSsh has no context yet");
            ensureKeypair();

            JSchException last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return connectOnce();
                } catch (JSchException e) {
                    last = e;
                    noteSessionLost();
                    if (!looksLikeHostKeyTrouble(e)) break;
                    StrykerLog.w(TAG, "host key was rejected, re-reading the guest's copy: "
                            + e.getMessage());
                    sleep(1200);
                }
            }
            throw last == null ? new JSchException("could not open a session to the guest") : last;
        }
    }

    private static Session connectOnce() throws JSchException, IOException {
        byte[] hosts = knownHosts();
        if (hosts == null) {
            throw new JSchException("the guest has not published its host key yet"
                    + " -- it writes " + SHARE_SSH_DIR + "/" + SHARE_HOST_KEYS
                    + " into the share once stryker-guest-init has run");
        }

        JSch jsch = new JSch();
        jsch.addIdentity(privateKey().getAbsolutePath());
        jsch.setKnownHosts(new ByteArrayInputStream(hosts));

        Session s = jsch.getSession(USER, RootlessPaths.HOST_LOOPBACK, port);
        s.setConfig("StrictHostKeyChecking", "yes");
        s.setConfig("PreferredAuthentications", "publickey");
        s.setServerAliveInterval(15_000);
        s.setServerAliveCountMax(4);
        s.connect(CONNECT_TIMEOUT_MS);
        session = s;
        StrykerLog.i(TAG, "connected to the guest over ssh on port " + port);
        reapplyForwards(s);
        return s;
    }

    private static void reapplyForwards(Session s) {
        for (Map.Entry<Integer, Integer> f : wantedForwards.entrySet()) {
            try {
                applyForward(s, f.getKey(), f.getValue());
            } catch (Exception e) {
                StrykerLog.w(TAG, "could not restore the forward of 127.0.0.1:" + f.getKey()
                        + " on the new session: " + e.getMessage());
            }
        }
    }

    private static void applyForward(Session s, int hostPort, int guestPort) throws JSchException {
        try { s.delPortForwardingL(RootlessPaths.HOST_LOOPBACK, hostPort); } catch (Exception ignored) {}
        s.setPortForwardingL(RootlessPaths.HOST_LOOPBACK, hostPort, "127.0.0.1", guestPort);
    }

    private static boolean looksLikeHostKeyTrouble(JSchException e) {
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("hostkey") || lower.contains("host key")
                || lower.contains("reject") || lower.contains("not published");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static ChannelExec exec(String command) throws JSchException, IOException {
        ChannelExec c = (ChannelExec) session().openChannel("exec");
        c.setCommand(command);
        c.setInputStream(null);
        c.setErrStream(null, true);
        return c;
    }

    public static ChannelShell shell(int columns, int rows) throws JSchException, IOException {
        ChannelShell c = (ChannelShell) session().openChannel("shell");
        c.setPtyType("xterm-256color", columns, rows, 0, 0);
        return c;
    }

    public static ChannelShell interactiveShell() throws JSchException, IOException {
        ChannelShell c = (ChannelShell) session().openChannel("shell");
        c.setPty(false);
        return c;
    }

    public static boolean forwardLocalPort(int hostPort, int guestPort) {
        if (hostPort == port) {
            StrykerLog.w(TAG, "refusing to forward 127.0.0.1:" + hostPort
                    + ": it is the port the guest's ssh comes in on");
            return false;
        }
        wantedForwards.put(hostPort, guestPort);
        try {
            synchronized (LOCK) {
                applyForward(session(), hostPort, guestPort);
            }
            StrykerLog.i(TAG, "forwarded 127.0.0.1:" + hostPort + " to guest port " + guestPort);
            return true;
        } catch (Exception e) {
            StrykerLog.w(TAG, "cannot forward port " + hostPort + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean unforwardLocalPort(int hostPort) {
        wantedForwards.remove(hostPort);
        Session s = session;
        if (s == null || !s.isConnected()) return true;
        try {
            s.delPortForwardingL(RootlessPaths.HOST_LOOPBACK, hostPort);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final long PING_REPORT_EVERY_MS = 10_000L;
    private static volatile long lastPingReport;
    private static volatile String lastPingFailure;

    public static boolean ping(int timeoutMs) {
        String stage = "session";
        int budget = Math.max(timeoutMs, 1000);
        try {
            ChannelExec c = exec("echo " + PONG);
            stage = "channel";
            try {
                InputStream in = c.getInputStream();
                c.connect(budget);
                stage = "reply";
                if (!awaitPong(c, in, budget)) {
                    throw new IOException(c.isClosed()
                            ? "the guest closed the channel without answering"
                            : "no answer within " + budget + "ms");
                }
                if (lastPingFailure != null) {
                    StrykerLog.i(TAG, "guest is answering again");
                    lastPingFailure = null;
                }
                return true;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            noteUnreachable();
            String why = stage + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            long now = System.currentTimeMillis();
            if (!why.equals(lastPingFailure) || now - lastPingReport > PING_REPORT_EVERY_MS) {
                lastPingFailure = why;
                lastPingReport = now;
                StrykerLog.w(TAG, "ping failed at " + why);
            }
            return false;
        }
    }

    private static boolean awaitPong(ChannelExec c, InputStream in, int budgetMs) throws IOException {
        long until = System.currentTimeMillis() + budgetMs;
        StringBuilder got = new StringBuilder();
        byte[] buf = new byte[64];
        while (true) {
            boolean closed = c.isClosed();
            int avail = in.available();
            if (avail > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, avail));
                if (n < 0) return false;
                got.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                if (got.indexOf(PONG) >= 0) return true;
                if (got.length() > 4096) got.delete(0, got.length() - 64);
                continue;
            }
            if (closed) return false;
            if (System.currentTimeMillis() >= until) return false;
            sleep(20);
        }
    }

    public static void disconnect() {
        noteSessionLost();
    }

    public static boolean isConnected() {
        Session s = session;
        return s != null && s.isConnected();
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
        }
        return buf;
    }
}
