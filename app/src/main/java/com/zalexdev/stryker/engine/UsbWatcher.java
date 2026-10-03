package com.zalexdev.stryker.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import com.zalexdev.stryker.utils.Core;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.stryker.terminal.bridge.StrykerLog;

public final class UsbWatcher {

    private static final String TAG = "UsbWatcher";

    private static final long SETTLE_MS = 900;

    private static volatile UsbWatcher instance;

    private final Context app;
    private final HandlerThread thread;
    private final Handler handler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "usb-watcher"); t.setDaemon(true); return t; });

    private static final int MAX_ASKS = 2;

    private final java.util.Map<String, Integer> asked = new ConcurrentHashMap<>();

    private volatile boolean registered = false;

    private UsbWatcher(Context context) {
        this.app = context.getApplicationContext();
        this.thread = new HandlerThread("usb-watcher-events");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public static synchronized void start(Context context) {
        if (instance == null) instance = new UsbWatcher(context);
        instance.register();
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.unregister();
        instance = null;
    }

    public static void sweepNow(Context context) {
        UsbWatcher w = instance;
        if (w == null) {
            start(context);
            w = instance;
        }
        if (w != null) w.scheduleSweep();
    }

    private void register() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(app, receiver, filter, null, handler,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        scheduleSweep();
    }

    private void unregister() {
        if (registered) {
            try { app.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            registered = false;
        }
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        worker.shutdownNow();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (d != null) {
                    asked.remove(key(d));
                    final UsbDevice gone = d;
                    submit(() -> releaseIfHeld(gone));
                }
            }
            scheduleSweep();
        }
    };

    private void scheduleSweep() {
        handler.removeCallbacks(sweepTrigger);
        handler.postDelayed(sweepTrigger, SETTLE_MS);
    }

    private final Runnable sweepTrigger = () -> submit(this::sweep);

    private void submit(Runnable r) {
        try {
            worker.execute(r);
        } catch (Throwable ignored) {
        }
    }

    private void releaseIfHeld(UsbDevice device) {
        GuestUsb usb = usb();
        if (usb == null) return;
        try {
            if (usb.isAttached(device)) {
                usb.detach(device);
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(device)
                        + " was unplugged — taken back from the guest");
            }
        } catch (Throwable t) {
            StrykerLog.w(TAG, "detach on unplug failed", t);
        }
    }

    private void sweep() {
        GuestUsb usb = usb();
        if (usb == null) return;

        List<UsbDevice> picks;
        try {
            forgetDevicesThatAreGone(usb);
            picks = usb.pickWifiDevices();
        } catch (Throwable t) {
            StrykerLog.w(TAG, "device list unavailable", t);
            return;
        }

        for (UsbDevice d : picks) {
            if (d == null || usb.isAttached(d)) continue;
            String key = key(d);
            Integer tries = asked.get(key);
            if (tries != null && tries >= MAX_ASKS) continue;

            boolean had = usb.hasPermission(d);
            if (!had) {
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(d)
                        + " was plugged in — asking for access");
            }
            boolean ok;
            try {
                ok = usb.attach(d);
            } catch (Throwable t) {
                StrykerLog.w(TAG, "attach failed", t);
                ok = false;
            }
            if (ok) {
                asked.remove(key);
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(d) + " is in the guest");
                continue;
            }
            if (!usb.hasPermission(d) && engineReady()) {
                int n = (tries == null ? 0 : tries) + 1;
                asked.put(key, n);
                if (n >= MAX_ASKS) {
                    GuestExec.logToStore("USB: access to " + UsbHostAccess.describe(d)
                            + " was not granted — unplug and plug it back in to be asked again");
                }
            } else if (!usb.hasPermission(d)) {
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(d)
                        + " could not be handed over yet — the guest is not answering");
            } else {
                GuestExec.logToStore("USB: " + UsbHostAccess.describe(d)
                        + " could not be handed to the guest");
            }
        }
    }

    private GuestUsb usb() {
        GuestEngine engine = engine();
        try {
            return engine == null || !engine.isRunning() ? null : engine.usb();
        } catch (Throwable t) {
            return null;
        }
    }

    private GuestEngine engine() {
        try {
            return Engines.active(WifiEngine.bindForBoot(new Core(app)));
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean engineReady() {
        GuestEngine e = engine();
        try {
            return e != null && e.isReady();
        } catch (Throwable t) {
            return false;
        }
    }

    private void forgetDevicesThatAreGone(GuestUsb usb) {
        if (asked.isEmpty()) return;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (UsbDevice d : usb.devices()) {
            if (d != null) present.add(key(d));
        }
        asked.keySet().retainAll(present);
    }

    private static String key(UsbDevice d) {
        String name = d.getDeviceName();
        if (name != null && !name.isEmpty()) return name;
        return String.format(java.util.Locale.US, "%04x:%04x/%d",
                d.getVendorId(), d.getProductId(), d.getDeviceId());
    }
}
