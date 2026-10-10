package com.zalexdev.stryker.handshakes.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BruteStopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!BruteJobs.ACTION_STOP.equals(intent.getAction())) return;
        String key = intent.getStringExtra(BruteJobs.EXTRA_KEY);
        if (key == null || key.isEmpty()) {
            BruteJobs.cancelAll();
            return;
        }
        BruteJobs.cancel(key);
    }
}
