package com.zalexdev.stryker.handshakes.utils;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class BruteJobs {

    public static final String ACTION_STOP = "com.zalexdev.stryker.BRUTE_STOP";
    public static final String EXTRA_KEY = "brute_key";

    public interface Listener {
        void onJobUpdated(BruteHandshake job);
    }

    private static final Map<String, BruteHandshake> jobs = new ConcurrentHashMap<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private BruteJobs() {}

    public static BruteHandshake find(String key) {
        return key == null ? null : jobs.get(key);
    }

    public static boolean isRunning(String key) {
        BruteHandshake job = find(key);
        return job != null && job.isRunning();
    }

    public static Collection<BruteHandshake> all() {
        return new ArrayList<>(jobs.values());
    }

    public static int runningCount() {
        int n = 0;
        for (BruteHandshake job : jobs.values()) if (job.isRunning()) n++;
        return n;
    }

    public static synchronized boolean start(BruteHandshake job) {
        if (job == null) return false;
        BruteHandshake previous = jobs.get(job.key);
        if (previous != null && previous.isRunning()) return false;
        jobs.put(job.key, job);
        job.start();
        return true;
    }

    public static void cancel(String key) {
        BruteHandshake job = find(key);
        if (job != null) job.cancel();
    }

    public static void cancelAll() {
        for (BruteHandshake job : jobs.values()) job.cancel();
    }

    public static void forget(String key) {
        if (key == null) return;
        BruteHandshake job = jobs.get(key);
        if (job != null && !job.isRunning()) jobs.remove(key);
    }

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    static void publish(final BruteHandshake job) {
        if (listeners.isEmpty()) return;
        MAIN.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onJobUpdated(job);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static Intent stopIntent(Context context, String key) {
        Intent intent = new Intent(ACTION_STOP);
        intent.setPackage(context.getPackageName());
        intent.setClass(context, BruteStopReceiver.class);
        intent.putExtra(EXTRA_KEY, key);
        return intent;
    }
}
