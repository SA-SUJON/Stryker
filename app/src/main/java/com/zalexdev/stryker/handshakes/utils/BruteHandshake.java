package com.zalexdev.stryker.handshakes.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.engine.GuestExec;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.GuestFiles;
import com.zalexdev.stryker.utils.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BruteHandshake {

    public enum State { RUNNING, FOUND, MISSED, FAILED, CANCELLED }

    private static final String RC_MARK = "__STRYKER_BRUTE_RC__";
    private static final String CHANNEL_ID = "BruteForce";
    private static final long PUBLISH_EVERY_MS = 400L;

    private static final Pattern RATE = Pattern.compile("\\d+/\\d+");
    private static final Pattern HOURS = Pattern.compile("\\d+ hours");
    private static final Pattern MINUTES = Pattern.compile("\\d+ minutes");
    private static final Pattern SECONDS = Pattern.compile("\\d+ seconds");
    private static final Pattern KEY = Pattern.compile("\\[ (.*?)\\]");

    public final String key;
    public final String captureLabel;
    public final String wordlistName;
    public final int id;

    private final Core core;
    private final Context context;
    private final Logger logger;
    private final String capturePath;
    private final String wordlistPath;
    private final String bssid;
    private final CrackOutcome outcome = new CrackOutcome();

    private volatile State state = State.RUNNING;
    private volatile String progressText = "";
    private volatile String timeText = "";
    private volatile String psk = "";
    private volatile int percent;
    private volatile int total;
    private volatile long publishedAt;

    private volatile Thread worker;
    private volatile Process process;
    private volatile GuestExec.Session guestSession;
    private volatile boolean cancelled;

    public BruteHandshake(Core core, Context context, String key, String captureLabel,
                          String capturePath, String wordlistPath, String wordlistName,
                          String bssid, int id) {
        this.core = core;
        this.context = context.getApplicationContext();
        this.key = key;
        this.captureLabel = captureLabel;
        this.capturePath = capturePath;
        this.wordlistPath = wordlistPath;
        this.wordlistName = wordlistName;
        this.bssid = bssid;
        this.id = id;
        this.logger = new Logger();
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public String psk() {
        return psk;
    }

    public String problem() {
        return outcome.problem();
    }

    public String progressText() {
        return progressText;
    }

    public String timeText() {
        return timeText;
    }

    public int percent() {
        return percent;
    }

    void start() {
        Thread t = new Thread(this::run, "brute-" + key);
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public void cancel() {
        if (!isRunning()) return;
        cancelled = true;
        state = State.CANCELLED;
        Process p = process;
        if (p != null) p.destroy();
        GuestExec.Session s = guestSession;
        if (s != null) s.close();
        Thread t = worker;
        if (t != null) t.interrupt();
        reapStrayCracker();
        clearNotification();
        logger.writeLine("Cracking cancelled for " + captureLabel, 3);
        BruteJobs.publish(this);
    }

    private String command() {
        StringBuilder sb = new StringBuilder("aircrack-ng -w ");
        sb.append(GuestFiles.shellQuote(wordlistPath));
        if (bssid != null && bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            sb.append(" -b ").append(bssid);
        }
        sb.append(' ').append(GuestFiles.shellQuote(capturePath));
        sb.append(" < /dev/null 2>&1");
        return sb.toString();
    }

    private void run() {
        logger.writeLine("Starting brute handshake", 1);
        String cmd = command();
        logger.writeLine(cmd, 1);
        notifyRunning();
        try {
            if (core.isRootless()) {
                runRootless(cmd);
            } else {
                runRooted(cmd);
            }
        } catch (IOException | InterruptedException e) {
            if (!cancelled) {
                outcome.fail(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        } catch (Throwable t) {
            if (!cancelled) outcome.fail(String.valueOf(t.getMessage()));
        }
        finish();
    }

    private void finish() {
        if (cancelled) {
            state = State.CANCELLED;
            BruteJobs.publish(this);
            return;
        }
        if (!psk.isEmpty()) {
            state = State.FOUND;
            if (bssid != null && !bssid.isEmpty()) core.putString(bssid, psk);
            notifyDone(context.getString(R.string.hs_notif_found), psk);
        } else {
            String why = outcome.problem();
            state = why == null ? State.MISSED : State.FAILED;
            if (why != null) logger.writeLine(why, 3);
            notifyDone(why == null
                            ? context.getString(R.string.hs_notif_missed)
                            : context.getString(R.string.hs_notif_failed),
                    why == null ? captureLabel : why);
        }
        BruteJobs.publish(this);
    }

    private void runRootless(String cmd) throws IOException {
        int lines = 0;
        int exit = -1;
        boolean finished = false;
        guestSession = core.guest().openStream(cmd);
        BufferedReader reader = guestSession.reader;
        String line;
        while (!cancelled && (line = reader.readLine()) != null) {
            if (line.startsWith(GuestExec.Session.SENTINEL)) {
                finished = true;
                try {
                    exit = Integer.parseInt(
                            line.substring(GuestExec.Session.SENTINEL.length()).trim());
                } catch (NumberFormatException ignored) {
                }
                break;
            }
            lines++;
            consume(line);
        }
        guestSession.close();
        if (cancelled) return;
        if (lines == 0) {
            outcome.noteNoOutput();
            return;
        }
        if (!finished) {
            outcome.fail("the guest dropped the connection while cracking");
            return;
        }
        outcome.noteExit(exit);
    }

    private void runRooted(String cmd) throws IOException, InterruptedException {
        process = core.generateSuProcess();
        if (process == null) {
            outcome.fail("su is not available on this device");
            return;
        }
        OutputStream stdin = process.getOutputStream();
        stdin.write((Core.EXECUTE + "'" + Core.SHELL + "'\n").getBytes());
        stdin.write((cmd + "\n").getBytes());
        stdin.write(("printf '\\n" + RC_MARK + "%s\\n' \"$?\"\n").getBytes());
        stdin.write("exit\nexit\n".getBytes());
        stdin.flush();
        stdin.close();

        final InputStream stderr = process.getErrorStream();
        Thread errPump = new Thread(() -> {
            try (BufferedReader er = new BufferedReader(new InputStreamReader(stderr))) {
                String l;
                while ((l = er.readLine()) != null) {
                    logger.writeLine(l, 3);
                    outcome.note(l);
                }
            } catch (IOException ignored) {
            }
        }, "brute-stderr");
        errPump.setDaemon(true);
        errPump.start();

        int exit = -1;
        int lines = 0;
        boolean finished = false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while (!cancelled && (line = br.readLine()) != null) {
                if (line.startsWith(RC_MARK)) {
                    finished = true;
                    try {
                        exit = Integer.parseInt(line.substring(RC_MARK.length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
                lines++;
                consume(line);
            }
        }
        process.waitFor();
        process.destroy();
        try { errPump.join(1500); } catch (InterruptedException ignored) { }
        if (cancelled) return;
        if (lines == 0) {
            outcome.noteNoOutput();
            return;
        }
        if (!finished) {
            outcome.fail("the root shell died while cracking");
            return;
        }
        outcome.noteExit(exit);
    }

    private void reapStrayCracker() {
        if (core.isRootless()) return;
        final String target = capturePath;
        new Thread(() -> {
            try {
                core.customChrootCommand("pkill -f " + GuestFiles.shellQuote(
                        "aircrack-ng.*" + target) + " >/dev/null 2>&1; true", true);
            } catch (Throwable ignored) {
            }
        }, "brute-reap").start();
    }

    private void consume(String line) {
        logger.writeLine(line, 2);
        outcome.note(line);
        track(line);
        if (!line.contains("KEY FOUND! [ ")) return;
        Matcher matcher = KEY.matcher(line);
        if (matcher.find() && matcher.group(1) != null) {
            psk = matcher.group(1).trim();
        }
    }

    private void track(String line) {
        String rem = "";
        Matcher hours = HOURS.matcher(line);
        if (hours.find()) rem = rem + hours.group(0) + " ";
        Matcher minutes = MINUTES.matcher(line);
        if (minutes.find()) rem = rem + minutes.group(0) + " ";
        Matcher seconds = SECONDS.matcher(line);
        if (seconds.find()) rem = rem + seconds.group(0) + " ";

        Matcher rate = RATE.matcher(line);
        boolean moved = false;
        if (rate.find() && rate.group(0) != null) {
            String[] parts = rate.group(0).split("/");
            try {
                percent = Integer.parseInt(parts[0]);
                total = Integer.parseInt(parts[1]);
                progressText = context.getString(R.string.hs_progress_rate, rate.group(0));
                moved = true;
            } catch (NumberFormatException ignored) {
            }
        }
        if (!rem.isEmpty()) {
            timeText = context.getString(R.string.hs_progress_remaining, rem.trim());
            moved = true;
        }
        if (!moved) return;
        long now = System.currentTimeMillis();
        if (now - publishedAt < PUBLISH_EVERY_MS) return;
        publishedAt = now;
        notifyRunning();
        BruteJobs.publish(this);
    }

    private NotificationManager manager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "BruteForce", NotificationManager.IMPORTANCE_LOW));
    }

    private NotificationCompat.Builder base(String title, String text) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(
                context, id, open, Utils.setPendingIntentFlag());
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);
    }

    private void notifyRunning() {
        NotificationManager nm = manager();
        if (nm == null) return;
        ensureChannel(nm);
        PendingIntent stop = PendingIntent.getBroadcast(context, id,
                BruteJobs.stopIntent(context, key), Utils.setPendingIntentFlag());
        String text = progressText.isEmpty() ? captureLabel : progressText;
        if (!timeText.isEmpty()) text = text + " · " + timeText;
        NotificationCompat.Builder b = base(
                context.getString(R.string.hs_notif_running, captureLabel), text)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(R.drawable.bolt, context.getString(R.string.hs_notif_stop), stop);
        if (total > 0) b.setProgress(total, percent, false);
        else b.setProgress(0, 0, true);
        try {
            nm.notify(id, b.build());
        } catch (Throwable ignored) {
        }
    }

    private void notifyDone(String title, String text) {
        NotificationManager nm = manager();
        if (nm == null) return;
        ensureChannel(nm);
        try {
            nm.notify(id, base(title, text).setOngoing(false).build());
        } catch (Throwable ignored) {
        }
    }

    private void clearNotification() {
        NotificationManager nm = manager();
        if (nm == null) return;
        try {
            nm.cancel(id);
        } catch (Throwable ignored) {
        }
    }
}
