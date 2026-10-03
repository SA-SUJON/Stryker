package com.stryker.terminal.bridge;

import android.util.Log;

public final class StrykerLog {

    public static final int INFO = 0;
    public static final int OUT = 2;
    public static final int ERR = 3;
    public static final int WARN = 4;

    public interface Sink {
        void write(int level, String tag, String message, Throwable error);
    }

    private static volatile Sink sink;

    private StrykerLog() {
    }

    public static void install(Sink s) {
        sink = s;
    }

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
        to(OUT, tag, msg, null);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
        to(OUT, tag, msg, null);
    }

    public static void d(String tag, String msg, Throwable t) {
        Log.d(tag, msg, t);
        to(OUT, tag, msg, t);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        to(INFO, tag, msg, null);
    }

    public static void i(String tag, String msg, Throwable t) {
        Log.i(tag, msg, t);
        to(INFO, tag, msg, t);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        to(WARN, tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        to(WARN, tag, msg, t);
    }

    public static void w(String tag, Throwable t) {
        Log.w(tag, t);
        to(WARN, tag, "", t);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        to(ERR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        to(ERR, tag, msg, t);
    }

    public static void v(String tag, String msg, Throwable t) {
        Log.v(tag, msg, t);
        to(OUT, tag, msg, t);
    }

    public static void wtf(String tag, String msg) {
        Log.wtf(tag, msg);
        to(ERR, tag, msg, null);
    }

    public static void wtf(String tag, String msg, Throwable t) {
        Log.wtf(tag, msg, t);
        to(ERR, tag, msg, t);
    }

    public static void wtf(String tag, Throwable t) {
        Log.wtf(tag, t);
        to(ERR, tag, "", t);
    }

    private static void to(int level, String tag, String msg, Throwable t) {
        Sink s = sink;
        if (s == null) return;
        try {
            s.write(level, tag, msg == null ? "" : msg, t);
        } catch (Throwable ignored) {
        }
    }
}
