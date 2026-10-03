package com.zalexdev.stryker.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.stryker.terminal.bridge.StrykerLog;

final class UmlUsb implements GuestUsb {

    private static final String TAG = "UmlUsb";

    private static final String GATEWAY = "10.0.2.2";

    private static final long PERMISSION_WAIT_MS = 30_000;

    private static final long SERVER_READY_MS = 8_000;

    private final Context app;
    private final UmlEngine engine;
    private final UsbHostAccess host;

    private final Map<Integer, Attached> attached = new LinkedHashMap<>();

    UmlUsb(Context context, UmlEngine engine) {
        this.app = context.getApplicationContext();
        this.engine = engine;
        this.host = new UsbHostAccess(context);
    }

    private static final class Attached {
        final UsbHostAccess.Handle handle;
        final AutoCloseable fdSocket;
        final Process server;
        final int port;
        final int vhciPort;
        final String busid;
        final String node;
        final String netdev;
        final String driver;

        Attached(UsbHostAccess.Handle handle, AutoCloseable fdSocket, Process server, int port,
                 int vhciPort, String busid, String node, String netdev, String driver) {
            this.handle = handle;
            this.fdSocket = fdSocket;
            this.server = server;
            this.port = port;
            this.vhciPort = vhciPort;
            this.busid = busid;
            this.node = node;
            this.netdev = netdev;
            this.driver = driver;
        }
    }

    @Override
    public List<UsbDevice> devices() {
        return host.devices();
    }

    @Override
    public boolean isWifiCandidate(UsbDevice device) {
        return UsbHostAccess.isWifiCandidate(device);
    }

    @Override
    public UsbDevice findByVidPid(String vidPid) {
        UsbDevice first = null;
        for (UsbDevice d : host.matching(vidPid)) {
            if (first == null) first = d;
            if (!isAttached(d)) return d;
        }
        return first;
    }

    @Override
    public boolean hasPermission(UsbDevice device) {
        return host.hasPermission(device);
    }

    @Override
    public boolean requestPermission(UsbDevice device, long waitMs) {
        if (device == null) return false;
        if (host.hasPermission(device)) return true;
        host.requestPermissionBlocking(device, waitMs);
        return host.hasPermission(device);
    }

    @Override
    public boolean isAttached(UsbDevice device) {
        pruneIfGuestGone();
        synchronized (this) {
            return device != null && attached.containsKey(device.getDeviceId());
        }
    }

    @Override
    public boolean hasAttached() {
        pruneIfGuestGone();
        synchronized (this) {
            return !attached.isEmpty();
        }
    }

    @Override
    public int attachedCount() {
        pruneIfGuestGone();
        synchronized (this) {
            return attached.size();
        }
    }

    private void pruneIfGuestGone() {
        boolean stale;
        synchronized (this) {
            stale = !attached.isEmpty();
        }
        if (!stale || engine.isRunning()) return;
        StrykerLog.i(TAG, "the guest is gone; releasing the devices it had");
        detachAll();
    }

    @Override
    public synchronized String attachmentDetail(UsbDevice device) {
        Attached a = device == null ? null : attached.get(device.getDeviceId());
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!a.busid.isEmpty()) sb.append("bus ").append(a.busid);
        if (!a.node.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(a.node);
        if (!a.netdev.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(a.netdev);
        else if (!a.driver.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(a.driver);
        return sb.toString();
    }

    @Override
    public List<UsbDevice> pickWifiDevices() {
        List<UsbDevice> out = new ArrayList<>();
        for (UsbDevice d : host.devices()) {
            if (UsbHostAccess.isWifiCandidate(d)) out.add(d);
        }
        return out;
    }

    @Override
    public void attachAsync(final UsbDevice device, final AttachCallback done) {
        Thread t = new Thread(() -> {
            boolean ok = attach(device);
            if (done != null) done.onResult(ok, device);
        }, "uml-usb-attach");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public int attachAllWifiDongles(long waitMs) {
        List<UsbDevice> picks = pickWifiDevices();
        int ok = 0;
        for (UsbDevice d : picks) {
            if (isAttached(d)) { ok++; continue; }
            if (!host.hasPermission(d) && !host.requestPermissionBlocking(d, waitMs)) {
                GuestExec.logToStore("USB: permission was not granted for "
                        + UsbHostAccess.describe(d));
                continue;
            }
            if (attach(d)) ok++;
        }
        return ok;
    }

    private final Object attachLock = new Object();

    @Override
    public boolean attach(UsbDevice device) {
        if (device == null) return false;
        synchronized (this) {
            if (attached.containsKey(device.getDeviceId())) return true;
        }
        synchronized (attachLock) {
            synchronized (this) {
                if (attached.containsKey(device.getDeviceId())) return true;
            }
            return attachLocked(device);
        }
    }

    private boolean attachLocked(UsbDevice device) {
        if (!engine.isReady() && !engine.startBlocking(null)) {
            GuestExec.logToStore("USB: the UML guest is not running, so there is nowhere to "
                    + "attach " + UsbHostAccess.describe(device));
            return false;
        }
        File server = engine.usbServer();
        if (!server.canExecute()) {
            GuestExec.logToStore("USB: libumusb.so is missing from this build — USB passthrough "
                    + "on UML needs it, and nothing else can stand in for it");
            return false;
        }
        if (!host.hasPermission(device)
                && !host.requestPermissionBlocking(device, PERMISSION_WAIT_MS)) {
            GuestExec.logToStore("USB: permission was not granted for "
                    + UsbHostAccess.describe(device));
            return false;
        }

        UsbHostAccess.Handle handle = host.open(device);
        if (handle == null) {
            GuestExec.logToStore("USB: could not open " + UsbHostAccess.describe(device));
            return false;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            if (startAndAttach(device, handle, server)) return true;
        }
        handle.close();
        return false;
    }

    private boolean startAndAttach(UsbDevice device, UsbHostAccess.Handle handle, File serverBin) {
        int port = freePort();
        if (port <= 0) {
            GuestExec.logToStore("USB: no free loopback port for the USB/IP server");
            return false;
        }
        String sockName = "stryker-usb-" + port;

        AutoCloseable fdSocket;
        try {
            fdSocket = UsbHostAccess.serveFd(sockName, handle.fd);
        } catch (IOException e) {
            GuestExec.logToStore("USB: cannot open the descriptor channel @" + sockName
                    + ": " + e.getMessage());
            return false;
        }

        Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    serverBin.getAbsolutePath(),
                    "--usbsock", sockName,
                    "--listen", "127.0.0.1:" + port,
                    "--no-cmdline",
                    "--verbose");
            pb.directory(engine.base());
            pb.redirectErrorStream(true);
            proc = pb.start();
        } catch (IOException e) {
            close(fdSocket);
            GuestExec.logToStore("USB: could not start the USB/IP server: " + e.getMessage());
            return false;
        }

        Startup startup = readStartup(proc);
        if (!startup.ok) {
            proc.destroy();
            close(fdSocket);
            if (startup.unconfigured) {
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(device)
                        + " has no active configuration, so there is no interface to hand over."
                        + " Android's kernel claimed the adapter, could not find its firmware and"
                        + " dropped it without putting it back. Unplug it and plug it in again.");
            } else {
                GuestExec.logToStore("USB: the USB/IP server did not come up: " + startup.problem);
            }
            return false;
        }
        drain(proc, startup.reader, UsbHostAccess.describe(device));

        String out = runAttachInGuest(port, startup.speed, startup.devid);
        String busid = find(out, "STRYKER_USB_ATTACHED=(\\S+)");
        String node = find(out, "STRYKER_USB_NODE=(\\S+)");
        String netdev = find(out, "STRYKER_USB_NETDEV=(\\S+)");
        String driver = find(out, "STRYKER_USB_DRIVER=(\\S+)");
        String vhciPort = find(out, "STRYKER_USB_PORT=(\\d+)");

        if (busid.isEmpty()) {
            if (!vhciPort.isEmpty()) detachInGuest(vhciPort);
            proc.destroy();
            close(fdSocket);
            String why = attachError(out);
            StrykerLog.w(TAG, "attach failed: " + out);
            GuestExec.logToStore("USB: " + UsbHostAccess.describe(device)
                    + " was not attached — " + why);
            return false;
        }

        synchronized (this) {
            attached.put(device.getDeviceId(), new Attached(handle, fdSocket, proc, port,
                    vhciPort.isEmpty() ? -1 : Integer.parseInt(vhciPort),
                    busid, node, netdev, driver));
        }
        StringBuilder said = new StringBuilder("USB: ")
                .append(UsbHostAccess.describe(device))
                .append(" is in the guest as ").append(busid);
        if (!node.isEmpty()) said.append(" (").append(node).append(")");
        if (!netdev.isEmpty()) said.append(", interface ").append(netdev);
        else if (driver.isEmpty()) said.append(", with no driver bound — the node is there for a "
                + "userspace driver to open");
        GuestExec.logToStore(said.toString());
        StrykerLog.i(TAG, said.toString());
        return true;
    }

    @Override
    public void detach(UsbDevice device) {
        Attached a;
        synchronized (this) {
            a = device == null ? null : attached.remove(device.getDeviceId());
        }
        release(a, device);
    }

    @Override
    public void detachAll() {
        List<Map.Entry<Integer, Attached>> all;
        synchronized (this) {
            all = new ArrayList<>(attached.entrySet());
            attached.clear();
        }
        for (Map.Entry<Integer, Attached> e : all) release(e.getValue(), null);
    }

    private void release(Attached a, UsbDevice device) {
        if (a == null) return;
        if (a.vhciPort >= 0 && engine.isRunning()) {
            detachInGuest(String.valueOf(a.vhciPort));
        }
        try { a.server.destroy(); } catch (Throwable ignored) {}
        close(a.fdSocket);
        a.handle.close();
        GuestExec.logToStore("USB: released "
                + UsbHostAccess.describe(device != null ? device : a.handle.device));
    }

    private void detachInGuest(String vhciPort) {
        try {
            GuestExec.run("bash /host/usb-attach.sh --detach " + vhciPort + " 2>&1");
        } catch (Throwable t) {
            StrykerLog.w(TAG, "guest detach of port " + vhciPort + " failed", t);
        }
    }

    private static final class Startup {
        boolean ok;
        boolean unconfigured;
        String problem = "";
        String devid = "";
        String speed = "";
        String busid = "";
        BufferedReader reader;
    }

    private Startup readStartup(Process proc) {
        Startup s = new Startup();
        s.reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        Pattern attachLine = Pattern.compile("echo \"0 <sockfd> (\\d+) (\\d+)\"");
        Pattern probeLine = Pattern.compile("busid (\\S+?),");
        long deadline = System.currentTimeMillis() + SERVER_READY_MS;
        String lastProblem = "";
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!s.reader.ready()) {
                    if (!proc.isAlive()) {
                        s.problem = lastProblem.isEmpty()
                                ? "it exited straight away" : lastProblem;
                        return s;
                    }
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                    continue;
                }
                String line = s.reader.readLine();
                if (line == null) {
                    s.problem = lastProblem.isEmpty() ? "its output ended" : lastProblem;
                    return s;
                }
                StrykerLog.i(TAG, line);
                Matcher m = probeLine.matcher(line);
                if (m.find()) s.busid = m.group(1);
                m = attachLine.matcher(line);
                if (m.find()) {
                    s.devid = m.group(1);
                    s.speed = m.group(2);
                    s.ok = true;
                    return s;
                }
                if (line.contains("claiming interface")
                        && line.contains("No such file or directory")) {
                    s.unconfigured = true;
                }
                if (line.contains(":") && !line.contains("interface ") && !line.contains("endpoint ")) {
                    lastProblem = line.replaceFirst("^umusb: ", "").trim();
                }
            }
        } catch (IOException e) {
            s.problem = e.getMessage() != null ? e.getMessage() : "its output could not be read";
            return s;
        }
        s.problem = lastProblem.isEmpty()
                ? "it did not report a listening socket within " + (SERVER_READY_MS / 1000) + "s"
                : lastProblem;
        return s;
    }

    private void drain(final Process proc, final BufferedReader reader, final String label) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) StrykerLog.i(TAG, line);
            } catch (IOException ignored) {
            } finally {
                try { reader.close(); } catch (IOException ignored) {}
                StrykerLog.i(TAG, "the USB/IP server for " + label + " ended");
            }
        }, "umusb-log");
        t.setDaemon(true);
        t.start();
    }

    private String runAttachInGuest(int port, String speed, String devid) {
        if (!stageScript()) {
            return "usb-attach.sh: the script could not be placed in the share";
        }
        StringBuilder cmd = new StringBuilder("bash /host/usb-attach.sh --connect ")
                .append(GATEWAY).append(":").append(port);
        if (!speed.isEmpty()) cmd.append(" --speed ").append(speed);
        if (!devid.isEmpty()) cmd.append(" --devid ").append(devid);
        cmd.append(" 2>&1");
        List<String> lines = GuestExec.run(cmd.toString());
        return android.text.TextUtils.join("\n", lines);
    }

    private boolean stageScript() {
        File target = new File(engine.shareDir(), "usb-attach.sh");
        try (InputStream in = app.getAssets().open("usb-attach.sh");
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, stripCr(buf, n));
        } catch (IOException e) {
            StrykerLog.w(TAG, "staging usb-attach.sh", e);
            return false;
        }
        target.setReadable(true, false);
        return true;
    }

    private static int stripCr(byte[] buf, int len) {
        int w = 0;
        for (int i = 0; i < len; i++) {
            if (buf[i] != '\r') buf[w++] = buf[i];
        }
        return w;
    }

    private static String attachError(String out) {
        for (String line : out.split("\n")) {
            if (line.contains("usb-attach.sh:") && !line.contains("device enumerated")
                    && !line.contains("connected to") && !line.contains("attaching fd")) {
                return line.substring(line.indexOf("usb-attach.sh:") + 14).trim();
            }
        }
        return out.trim().isEmpty() ? "the guest said nothing at all" : out.trim();
    }

    private static String find(String haystack, String regex) {
        Matcher m = Pattern.compile(regex).matcher(haystack);
        return m.find() ? m.group(1) : "";
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void close(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
