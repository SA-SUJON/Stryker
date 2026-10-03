package com.zalexdev.stryker.netdetect;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.zalexdev.stryker.R;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.engine.Engines;
import com.zalexdev.stryker.engine.GuestEngine;
import com.zalexdev.stryker.engine.GuestUsb;
import com.zalexdev.stryker.engine.RootlessService;
import com.zalexdev.stryker.engine.WifiEngine;
import com.zalexdev.stryker.utils.Core;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.stryker.terminal.bridge.StrykerLog;

public final class UsbFlow {

    public static final long DRIVER_WAIT_MS = 7_000;

    private static final long GUEST_WAIT_MS = 90_000;

    private static final long PERMISSION_WAIT_MS = 60_000;

    public interface Listener {
        void onUpdate(UsbDeviceReport report, UsbChain chain);
    }

    private final Context app;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private UsbChain.Builder chain;
    private int iPort, iHost, iVm, iWlan;
    private UsbDeviceReport report;

    public UsbFlow(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    private static final String TAG = "UsbFlow";

    public void start() {
        Thread t = new Thread(this::runGuarded, "usb-flow");
        t.setDaemon(true);
        t.start();
    }

    private void runGuarded() {
        try {
            run();
        } catch (Throwable t) {
            StrykerLog.e(TAG, "flow failed", t);
            try {
                com.zalexdev.stryker.logger.LogStore store =
                        com.zalexdev.stryker.logger.LogStore.peek();
                if (store != null) {
                    store.add(com.zalexdev.stryker.logger.LogEntry.ERR, "usb",
                            "USB sheet: " + t);
                }
            } catch (Throwable ignored) {
            }
            if (chain == null) {
                emit(new UsbChain(new java.util.ArrayList<>(), false, false,
                        app.getString(R.string.usb_note_flow_failed_title),
                        String.valueOf(t), app.getString(R.string.usb_note_retry),
                        UsbChain.Fix.RETRY, ""));
                return;
            }
            fail(app.getString(R.string.usb_note_flow_failed_title), String.valueOf(t),
                    app.getString(R.string.usb_note_retry), UsbChain.Fix.RETRY);
        }
    }

    public void cancel() {
        cancelled.set(true);
    }

    private void run() {
        Core core = new Core(app);
        boolean wifiGuest = WifiEngine.armed(core);
        boolean guestMode = core.isRootless() || wifiGuest;

        chain = new UsbChain.Builder()
                .add(app.getString(R.string.usb_stage_port), UsbChain.portIcon())
                .add(app.getString(R.string.usb_stage_android), UsbChain.androidIcon());
        iPort = 0;
        iHost = 1;
        iVm = -1;
        if (guestMode) {
            chain.add(app.getString(R.string.usb_stage_vm), UsbChain.vmIcon());
            iVm = 2;
        }
        chain.add(app.getString(R.string.usb_stage_wlan), UsbChain.wlanIcon());
        iWlan = guestMode ? 3 : 2;

        chain.running(iPort);
        progress(R.string.usb_step_scanning);
        List<UsbDeviceReport> devs = NetDetector.listNetworkUsbDevices(app);
        report = devs.isEmpty() ? null : devs.get(0);
        if (report == null) {
            chain.set(iPort, UsbChain.State.FAIL);
            fail(R.string.usb_empty_title, R.string.usb_empty_body, UsbChain.Fix.RETRY);
            return;
        }
        if (stopped()) return;

        if (guestMode) runGuest(core, wifiGuest);
        else runRoot();
    }

    private void runRoot() {
        chain.running(iHost);
        progress(R.string.usb_step_kernel);

        if (report.driverState() == DriverState.UNBOUND
                || (report.driverState() == DriverState.UNKNOWN
                        && report.netInterfaces.isEmpty())) {
            chain.set(iHost, UsbChain.State.FAIL);
            fail(R.string.usb_note_nodriver_title, R.string.usb_note_nodriver_body,
                    R.string.usb_note_setup_guest, UsbChain.Fix.SETUP_GUEST);
            return;
        }

        chain.running(iWlan);
        progress(R.string.usb_step_waiting_driver);
        String iface = awaitHostInterface();
        if (iface == null) {
            chain.set(iHost, UsbChain.State.WARN);
            chain.set(iWlan, UsbChain.State.FAIL);
            fail(R.string.usb_note_nonetdev_title,
                    app.getString(R.string.usb_note_nonetdev_body, driverName()),
                    app.getString(R.string.usb_note_search), UsbChain.Fix.SEARCH_DRIVER);
            return;
        }
        succeed(iface);
    }

    private void runGuest(Core core, boolean wifiGuest) {
        EngineType type = wifiGuest ? WifiEngine.configured(core) : EngineType.active(core);
        GuestEngine engine = Engines.active(app, type);
        GuestUsb usb = engine.usb();
        String guestName = engine.displayName();

        UsbDevice dev = usb == null ? null : usb.findByVidPid(report.vidPid);
        if (dev == null) {
            chain.set(iPort, UsbChain.State.FAIL);
            fail(R.string.usb_note_gone_title, R.string.usb_empty_body, UsbChain.Fix.RETRY);
            return;
        }

        chain.running(iHost);
        if (!usb.hasPermission(dev)) {
            progress(R.string.usb_step_asking);
            usb.requestPermission(dev, PERMISSION_WAIT_MS);
            if (stopped()) return;
        }
        if (!usb.hasPermission(dev)) {
            chain.set(iHost, UsbChain.State.FAIL);
            fail(R.string.usb_note_noperm_title, R.string.usb_note_noperm_body,
                    R.string.usb_note_ask_again, UsbChain.Fix.RETRY);
            return;
        }

        boolean phoneHolds = report.driverState() == DriverState.BOUND_WITH_NETDEV
                || report.driverState() == DriverState.BOUND_NO_NETDEV;
        chain.set(iHost, phoneHolds ? UsbChain.State.WARN : UsbChain.State.DONE);

        chain.running(iVm);
        if (!engine.isRunning() || !engine.isReady()) {
            progress(R.string.usb_step_starting_guest);
            RootlessService.start(app);
            if (!awaitGuest(engine)) {
                chain.set(iVm, UsbChain.State.FAIL);
                fail(R.string.usb_note_guestdown_title,
                        app.getString(R.string.usb_note_guestdown_body, guestName),
                        app.getString(R.string.usb_note_retry), UsbChain.Fix.RETRY);
                return;
            }
        }
        if (stopped()) return;

        if (!usb.isAttached(dev)) {
            progress(R.string.usb_step_handing);
            boolean ok;
            try {
                ok = usb.attach(dev);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok || !usb.isAttached(dev)) {
                chain.set(iVm, UsbChain.State.FAIL);
                if (phoneHolds) {
                    fail(R.string.usb_note_phoneholds_title,
                            app.getString(R.string.usb_note_phoneholds_body,
                                    driverName(), guestName),
                            app.getString(R.string.usb_note_retry), UsbChain.Fix.RETRY);
                } else {
                    fail(R.string.usb_note_handover_failed_title,
                            app.getString(R.string.usb_note_handover_failed_body, guestName),
                            app.getString(R.string.usb_note_retry), UsbChain.Fix.RETRY);
                }
                return;
            }
        }
        chain.set(iVm, UsbChain.State.DONE);
        if (stopped()) return;

        chain.running(iWlan);
        progress(R.string.usb_step_waiting_driver);
        String iface = awaitGuestInterface(type);
        if (iface == null) {
            chain.set(iWlan, UsbChain.State.FAIL);
            fail(R.string.usb_note_noguestdriver_title,
                    app.getString(R.string.usb_note_noguestdriver_body, guestName),
                    app.getString(R.string.usb_note_search), UsbChain.Fix.SEARCH_DRIVER);
            return;
        }
        succeed(iface);
    }

    private boolean awaitGuest(GuestEngine engine) {
        long deadline = System.currentTimeMillis() + GUEST_WAIT_MS;
        while (System.currentTimeMillis() < deadline && !cancelled.get()) {
            try {
                if (engine.isReady()) return true;
            } catch (Throwable ignored) {
            }
            sleep(1000);
        }
        return false;
    }

    private String awaitHostInterface() {
        long deadline = System.currentTimeMillis() + DRIVER_WAIT_MS;
        while (true) {
            List<UsbDeviceReport> devs = NetDetector.listNetworkUsbDevices(app);
            for (UsbDeviceReport r : devs) {
                if (!r.vidPid.equals(report.vidPid)) continue;
                report = r;
                if (!r.netInterfaces.isEmpty()) return r.netInterfaces.get(0);
            }
            if (System.currentTimeMillis() >= deadline || cancelled.get()) return null;
            sleep(700);
        }
    }

    private String awaitGuestInterface(EngineType type) {
        Core guestCore = new Core(app).overrideEngine(type);
        long deadline = System.currentTimeMillis() + DRIVER_WAIT_MS;
        while (true) {
            try {
                for (String[] pair : guestCore.monitorManager.listInterfaces()) {
                    String name = pair[0];
                    if (name != null && (name.startsWith("wlan") || name.startsWith("wlx"))) {
                        return name;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (System.currentTimeMillis() >= deadline || cancelled.get()) return null;
            sleep(700);
        }
    }

    private boolean stopped() {
        return cancelled.get();
    }

    private String driverName() {
        if (report != null && !report.interfaces.drivers.isEmpty()) {
            return report.interfaces.drivers.get(0);
        }
        if (report != null && report.chipset != null && report.chipset.driver != null) {
            return report.chipset.driver;
        }
        return "—";
    }

    private void progress(int titleRes) {
        emit(chain.snapshot(true, false, app.getString(titleRes), null, null,
                UsbChain.Fix.NONE, ""));
    }

    private void fail(int titleRes, int bodyRes, UsbChain.Fix fix) {
        fail(app.getString(titleRes), app.getString(bodyRes), null, fix);
    }

    private void fail(int titleRes, int bodyRes, int actionRes, UsbChain.Fix fix) {
        fail(app.getString(titleRes), app.getString(bodyRes), app.getString(actionRes), fix);
    }

    private void fail(int titleRes, String body, String action, UsbChain.Fix fix) {
        fail(app.getString(titleRes), body, action, fix);
    }

    private void fail(String title, String body, String action, UsbChain.Fix fix) {
        emit(chain.snapshot(false, false, title, body, action, fix, ""));
    }

    private void succeed(String iface) {
        chain.label(iWlan, iface);
        chain.set(iWlan, UsbChain.State.DONE);
        emit(chain.snapshot(false, true, null, null, null, UsbChain.Fix.NONE, iface));
    }

    private void emit(UsbChain snapshot) {
        if (listener != null && !cancelled.get()) listener.onUpdate(report, snapshot);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
