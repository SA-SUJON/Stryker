package com.zalexdev.stryker.utils;

import android.app.Activity;
import android.content.Context;

import com.zalexdev.stryker.engine.GuestExec;
import com.zalexdev.stryker.logger.LogTool;
import com.zalexdev.stryker.logger.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;

public abstract class AdvancedProcess {

    public static final String MACHINE_PREFIX = "STRYKER:";

    public Activity activity;
    public Context context;
    public Process process;
    public Core core;
    public InputStream output;
    public InputStream error;
    public OutputStream input;
    public String cmd;
    public String tool;
    public boolean chroot;
    public boolean success = false;
    private static final int MAX_KEPT_LINES = 400;

    public ArrayList<String> outputList = new ArrayList<>();

    private final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
    private boolean drainScheduled = false;

    private static final int MAX_PENDING = 2000;

    private void deliver(String line) {
        synchronized (pending) {
            pending.addLast(line);
            while (pending.size() > MAX_PENDING) pending.removeFirst();
            if (drainScheduled) return;
            drainScheduled = true;
        }
        activity.runOnUiThread(this::drain);
    }

    private void drain() {
        java.util.ArrayList<String> batch;
        synchronized (pending) {
            batch = new java.util.ArrayList<>(pending);
            pending.clear();
            drainScheduled = false;
        }
        for (String l : batch) {
            try {
                onNewLine(l);
            } catch (Throwable ignored) {
            }
        }
    }

    private void record(String line) {
        synchronized (outputList) {
            outputList.add(line);
            if (outputList.size() > MAX_KEPT_LINES) {
                outputList.subList(0, outputList.size() - MAX_KEPT_LINES).clear();
            }
        }
    }

    private String lastShape = null;
    private int repeats = 0;

    private static String shapeOf(String line) {
        return line == null ? "" : line.replaceAll("[0-9]+", "#");
    }

    private boolean worthLogging(String line) {
        String shape = shapeOf(line);
        if (shape.equals(lastShape)) {
            repeats++;
            return false;
        }
        if (repeats > 0 && lastShape != null) {
            logger.writeLine("… previous line repeated " + repeats + " times", 2, tool);
            repeats = 0;
        }
        lastShape = shape;
        return true;
    }
    public Logger logger;
    public boolean running = true;
    public boolean noLog = false;

    private final boolean rootless;
    private volatile GuestExec.Session guestSession;
    private volatile boolean killed;

    public AdvancedProcess(Activity activity, Context context, String command, boolean chroot) {
        this.activity = activity;
        this.context = context;
        core = new Core(context);
        this.tool = LogTool.classify(command);
        this.cmd = lineBuffered(command);
        this.chroot = chroot;
        this.rootless = chroot && core.isRootless();
        this.logger = new Logger();
        execute();
    }

    public AdvancedProcess(Activity activity, Context context, String command, boolean chroot, boolean inMainThread) {
        this.activity = activity;
        this.context = context;
        core = new Core(context);
        this.tool = LogTool.classify(command);
        this.cmd = lineBuffered(command);
        this.chroot = chroot;
        this.rootless = chroot && core.isRootless();
        this.logger = new Logger();
        if (inMainThread)
            executeInMainThread();
        else
            execute();
    }

    private static final java.util.List<String> LIVE_OUTPUT_TOOLS = java.util.Arrays.asList(
            "aireplay-ng", "airodump-ng", "airbase-ng", "aircrack-ng", "besside-ng",
            "mdk4", "mdk3", "wash", "reaver", "bully", "hcxdumptool", "tcpdump", "tshark");

    static String lineBuffered(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        String first = space < 0 ? trimmed : trimmed.substring(0, space);
        if (!LIVE_OUTPUT_TOOLS.contains(first)) return command;
        return "__SB=$(command -v stdbuf 2>/dev/null); $__SB ${__SB:+-oL -eL} " + trimmed;
    }

    public AdvancedProcess setNoLog(boolean noLog) {
        this.noLog = noLog;
        return this;
    }

    private void start() {
        if (killed) {
            running = false;
            return;
        }
        if (rootless) {
            startRootless();
            return;
        }
        process = core.generateSuProcess();
        if (killed) {
            try { process.destroy(); } catch (Exception ignored) {}
            running = false;
            return;
        }
        output = process.getInputStream();
        error = process.getErrorStream();
        input = process.getOutputStream();
        activity.runOnUiThread(this::onPrepare);
        sendCommand(cmd);
        logger.writeLine("Command: " + cmd, 1, tool);
        BufferedReader reader = new BufferedReader(new InputStreamReader(output));
        String line;
        Thread errorPump = new Thread(() -> {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(error))) {
                String errLine;
                while ((errLine = errorReader.readLine()) != null) {
                    String trimmed = errLine.trim();
                    if (!noLog) {
                        logger.writeLine(trimmed, 3, tool);
                    }
                    record("[E] " + trimmed);
                    if (!trimmed.startsWith(MACHINE_PREFIX)) {
                        deliver(trimmed);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "proc-stderr");
        errorPump.setDaemon(true);
        errorPump.start();
        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                String finalLine = line;
                if (!finalLine.startsWith(MACHINE_PREFIX)) {
                    deliver(finalLine);
                }
                if (!noLog && worthLogging(line)) {
                    logger.writeLine(line, 2, tool);
                }
                if (line.contains("JOBFINISHED")) {
                    process.destroy();
                }
                record(line);
            }
        } catch (Exception ignored) {

        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try {
            errorPump.join(1500);
        } catch (InterruptedException ignored) {
        }
        process.destroy();

        activity.runOnUiThread(() -> onFinished(outputList));
        running = false;
    }

    private void startRootless() {
        activity.runOnUiThread(this::onPrepare);
        logger.writeLine("Rootless command: " + cmd, 1, tool);
        try {
            if (!killed) {
                guestSession = core.guest().openStream(cmd);
                String line;
                while (!killed && (line = guestSession.reader.readLine()) != null) {
                    if (line.startsWith(GuestExec.Session.SENTINEL)) {
                        break;
                    }
                    line = line.trim();
                    String finalLine = line;
                    if (!finalLine.startsWith(MACHINE_PREFIX)) {
                        deliver(finalLine);
                    }
                    if (!noLog && worthLogging(line)) {
                        logger.writeLine(line, 2, tool);
                    }
                    if (line.contains("JOBFINISHED")) {
                        break;
                    }
                    record(line);
                }
            }
        } catch (Exception e) {
            logger.writeLine("Rootless exec failed (VM not reachable?): " + e.getMessage(), 3, tool);
        } finally {
            if (guestSession != null) guestSession.close();
        }
        activity.runOnUiThread(() -> onFinished(outputList));
        running = false;
    }

    public void execute() {
        new Thread(this::start).start();
    }

    public void executeInMainThread() {
        start();
    }

    public abstract void onFinished(ArrayList<String> outputList);

    public abstract void onNewLine(String line);

    public AdvancedProcess sendCommand(String command) {
        if (rootless) {
            return this;
        }
        try {
            if (chroot) {
                input.write((Core.EXECUTE + "'" + Core.SHELL + "'" + "\n").getBytes());
                input.write((command + "\n").getBytes());
                input.write(("exit\n").getBytes());
                input.write(("exit\n").getBytes());
            } else {
                input.write((command +"\n").getBytes());
                input.write(("exit\n").getBytes());
            }
            input.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return this;
    }

    public void kill() {
        killed = true;
        try {
            if (rootless) {
                if (guestSession != null) guestSession.close();
            } else if (process != null) {
                process.destroy();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        running = false;
    }

    protected void onPrepare() {

    }

    public abstract void onEvent(String line);

    public boolean isSuccess() {
        return success;
    }

    public boolean isRunning() {
        return running;
    }
}
