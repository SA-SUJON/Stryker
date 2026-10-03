package com.zalexdev.stryker.engine;

import java.io.File;
import java.util.List;

public interface GuestEngine {

    interface BootListener {
        void onBootLine(String line);

        void onBooted();

        void onFailed(String reason);
    }

    EngineType type();

    String displayName();

    List<Artifact> requiredArtifacts();

    List<String> missing();

    boolean isInstalled();

    final class Artifact {
        public final String key;
        public final String label;
        public final File target;
        public final boolean compressed;

        public Artifact(String key, String label, File target, boolean compressed) {
            this.key = key;
            this.label = label;
            this.target = target;
            this.compressed = compressed;
        }
    }

    enum State { STOPPED, BOOTING, READY }

    State status();

    State statusBlocking();

    java.util.List<String> consoleTail(int lines);

    boolean isRunning();

    boolean isReady();

    boolean startBlocking(BootListener listener);

    void startAsync();

    void stop();

    boolean stopAndWait(long timeoutMs);

    String lastError();

    String guestPrompt();

    File shareDir();

    int sshPort();

    String guestSharePath();

    boolean forwardPort(int hostPort, int guestPort);

    boolean unforwardPort(int hostPort);

    java.util.ArrayList<String> exec(String command);

    GuestExec.Session openStream(String command) throws java.io.IOException;

    GuestUsb usb();

    boolean ensureUsbWifiAttached();

    boolean usbDriverOk();

    boolean supports(Capability capability);

    enum Capability {
        DISK_TUNING,
        TCG_TUNING,
        CPU_MODEL,
        SAFE_PROFILE,
        DISK_RESIZE,
        USB_PASSTHROUGH,
        SECCOMP,
    }
}
