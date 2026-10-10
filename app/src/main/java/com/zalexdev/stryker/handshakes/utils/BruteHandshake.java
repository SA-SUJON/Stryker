package com.zalexdev.stryker.handshakes.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BruteHandshake extends AsyncTask<Void, String, WiFINetwork> {
    private static final String RC_MARK = "__STRYKER_BRUTE_RC__";

    public String exec = Core.EXECUTE;
    public String path;
    public String wordlist;
    public String bssid;
    public Core core;
    public Activity activity;
    public TextView progress;
    public TextView time;
    public Context context;
    public int id;
    public Process process;
    public GuestExec.Session guestSession;
    public Logger logger;

    private final CrackOutcome outcome = new CrackOutcome();
    private volatile boolean cancelled;

    public BruteHandshake(String p, String w, Core c, Activity a, Context con, TextView pr, TextView t, int i) {
        core = c;
        path = p;
        wordlist = w;
        activity = a;
        progress = pr;
        time = t;
        context = con;
        id = i;
        logger = new Logger();
    }

    public BruteHandshake withBssid(String mac) {
        bssid = mac;
        return this;
    }

    public String problem() {
        return outcome.problem();
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();

    }

    private String guestCapture() {
        if (path != null && path.startsWith("/")) return path;
        return core.guestShare() + "/captured/" + path;
    }

    private String command() {
        StringBuilder sb = new StringBuilder("aircrack-ng -w ");
        sb.append(GuestFiles.shellQuote(wordlist));
        if (bssid != null && bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            sb.append(" -b ").append(bssid);
        }
        sb.append(' ').append(GuestFiles.shellQuote(guestCapture()));
        sb.append(" < /dev/null 2>&1");
        return sb.toString();
    }

    @SuppressLint("WrongThread")
    @Override
    protected WiFINetwork doInBackground(Void... command) {
        WiFINetwork result = new WiFINetwork();
        logger.writeLine("Starting brute handshake", 1);
        String cmd = command();
        logger.writeLine(cmd, 1);
        try {
            if (core.isRootless()) {
                runRootless(cmd, result);
            } else {
                runRooted(cmd, result);
            }
        } catch (IOException | InterruptedException e) {
            if (!cancelled) {
                outcome.fail(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (!result.getOK() && !cancelled) {
            String why = outcome.problem();
            if (why != null) logger.writeLine(why, 3);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CreateNotification("Failed", why == null ? "Password Not Found" : why, 100, 100);
            }
        }
        return result;
    }

    private void runRootless(String cmd, WiFINetwork result) throws IOException {
        int lines = 0;
        int exit = -1;
        boolean finished = false;
        guestSession = core.guest().openStream(cmd);
        BufferedReader reader = guestSession.reader;
        String line;
        while ((line = reader.readLine()) != null) {
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
            consume(line, result);
        }
        guestSession.close();
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

    private void runRooted(String cmd, WiFINetwork result) throws IOException, InterruptedException {
        process = core.generateSuProcess();
        if (process == null) {
            outcome.fail("su is not available on this device");
            return;
        }
        OutputStream stdin = process.getOutputStream();
        stdin.write((exec + "'" + Core.SHELL + "'\n").getBytes());
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
            while ((line = br.readLine()) != null) {
                if (line.startsWith(RC_MARK)) {
                    finished = true;
                    try {
                        exit = Integer.parseInt(line.substring(RC_MARK.length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
                lines++;
                consume(line, result);
            }
        }
        process.waitFor();
        process.destroy();
        try { errPump.join(1500); } catch (InterruptedException ignored) { }
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

    private void consume(String line, WiFINetwork result) {
        logger.writeLine(line, 2);
        outcome.note(line);
        onProgressUpdate(line);
        if (!line.contains("KEY FOUND! [ ")) return;
        Matcher matcher = Pattern.compile("\\[ (.*?)\\]").matcher(line);
        if (matcher.find()) {
            result.setPsk(matcher.group(1));
        }
        result.setOK(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CreateNotification("Success", "Password found: " + result.getPsk(), 100, 100);
        }
    }

    @Override
    protected void onPostExecute(WiFINetwork result) {
        super.onPostExecute(result);


    }

    public void kill() {
        cancelled = true;
        if (process != null) {
            process.destroy();
        }
        if (guestSession != null) {
            guestSession.close();
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        activity.runOnUiThread(() -> {

            String rem = "";
            Matcher matcher = Pattern.compile("\\d+/\\d+").matcher(values[0]);
            Matcher matcher2 = Pattern.compile("\\d+ hours").matcher(values[0]);
            Matcher matcher3 = Pattern.compile("\\d+ minutes").matcher(values[0]);
            Matcher matcher4 = Pattern.compile("\\d+ seconds").matcher(values[0]);
            if (matcher2.find()) {
                rem = rem + matcher2.group(0) + " ";
            }
            if (matcher3.find()) {
                rem = rem + matcher3.group(0) + " ";
            }
            if (matcher4.find()) {
                rem = rem + matcher4.group(0) + " ";
            }
            int pr = 0;
            int all = 0;
            if (matcher.find()) {
                pr = Integer.parseInt(Objects.requireNonNull(matcher.group(0)).split("/")[0]);
                all = Integer.parseInt(Objects.requireNonNull(matcher.group(0)).split("/")[1]);
                progress.setText("Progress: " + matcher.group(0) + " k/s");
            }
            if (rem.length() != 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    CreateNotification(matcher.group(0), rem, pr, all);
                }
                time.setText("Time remaining: " + rem);
            }

        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void CreateNotification(String key, String left, int prog, int max) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context);

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(left)
                .setContentText(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");


        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(id, b.build());
    }


}
