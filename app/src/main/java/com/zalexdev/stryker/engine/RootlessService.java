package com.zalexdev.stryker.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.R;

public class RootlessService extends Service {

    private static final String CHANNEL_ID = "stryker_rootless";
    private static final int NOTIF_ID = 71;

    public static void start(Context context) {
        Intent i = new Intent(context, RootlessService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, RootlessService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification("Booting Linux VM…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification("Booting Linux VM…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, buildNotification("Booting Linux VM…"));
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        try {
            com.zalexdev.stryker.logger.LogStore.from(getApplicationContext());
        } catch (Throwable ignored) {}
        updateNotification("VM stopped: 6h foreground limit reached, reopen Stryker to resume");
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try { VmStatsCollector.get(getApplicationContext()).start(); } catch (Throwable ignored) {}
        try { UsbWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        new Thread(() -> {
            boolean ok = Engines.active(WifiEngine.bindForBoot(new com.zalexdev.stryker.utils.Core(this)))
                    .startBlocking(new RootlessEngine.BootListener() {
                @Override public void onBootLine(String line) {}
                @Override public void onBooted() {
                    updateNotification("Linux VM ready");
                    try { UsbWatcher.sweepNow(getApplicationContext()); } catch (Throwable ignored) {}
                }
                @Override public void onFailed(String reason) { updateNotification("VM failed: " + reason); }
            });
            if (!ok) {
                updateNotification("VM not ready — will retry on demand");
            } else {
                try { RootlessEngine.get(this).ensureGuestCore(); } catch (Exception ignored) {}
            }
        }).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { UsbWatcher.stop(); } catch (Throwable ignored) {}
        try {
            VmStatsCollector c = VmStatsCollector.peek();
            if (c != null) c.stop();
        } catch (Throwable ignored) {}
        final GuestEngine engine = Engines.running(getApplicationContext());
        if (engine != null) {
            new Thread(() -> {
                try { engine.stop(); } catch (Throwable ignored) {}
            }, "stryker-vm-service-stop").start();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Rootless engine",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Stryker rootless engine")
                .setContentText(text)
                .setSmallIcon(R.drawable.bolt)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
