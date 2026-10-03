package com.zalexdev.stryker.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import com.stryker.terminal.bridge.StrykerLog;

public class Slide2 extends Fragment implements IntroPage {

    private static final int STORAGE_ATTEMPTS_BEFORE_GIVING_UP = 2;

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private TextView title;
    private TextView rootSub, storageSub, batterySub;
    private ImageView rootStatus, storageStatus, batteryStatus;
    private ProgressBar rootSpinner, storageSpinner, batterySpinner;

    private boolean rootChecked;
    private boolean rootGranted;
    private boolean asking;
    private int storageAttempts;
    private boolean setupDone;

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide2, container, false);
        activity = getActivity();
        if (activity == null) return view;
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        title = view.findViewById(R.id.slide_title);
        com.zalexdev.stryker.appintro.IntroLayout.centerOn(
                view.findViewById(R.id.perms_key),
                view.findViewById(R.id.perms_eyebrow));

        rootSub = view.findViewById(R.id.root_subtitle);
        storageSub = view.findViewById(R.id.storage_subtitle);
        batterySub = view.findViewById(R.id.battery_subtitle);
        rootStatus = view.findViewById(R.id.root_status);
        storageStatus = view.findViewById(R.id.storage_status);
        batteryStatus = view.findViewById(R.id.battery_status);
        rootSpinner = view.findViewById(R.id.root_spinner);
        storageSpinner = view.findViewById(R.id.storage_spinner);
        batterySpinner = view.findViewById(R.id.battery_spinner);

        refreshStatuses();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (rootStatus == null || context == null || core == null) return;
        refreshStatuses();
        refreshChrome();
    }

    private boolean rootRequired() {
        return !core.isRootless();
    }

    private boolean rootOk() {
        return !rootRequired() || (rootChecked && rootGranted);
    }

    private boolean storageOk() {
        return core.hasAllFilesAccess();
    }

    private boolean satisfied() {
        return rootOk() && storageOk();
    }

    private boolean storageGivenUp() {
        return !storageOk() && storageAttempts >= STORAGE_ATTEMPTS_BEFORE_GIVING_UP;
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        if (satisfied()) return context.getString(R.string.intro_action_continue);
        if (rootOk() && storageGivenUp()) {
            return context.getString(R.string.perm_continue_without_storage);
        }
        return context.getString(R.string.intro_action_grant);
    }

    @Override
    public boolean primaryEnabled() {
        return !asking;
    }

    @Override
    public void onPrimary() {
        if (satisfied() || storageGivenUp()) {
            proceed();
            return;
        }
        if (!rootOk()) {
            askForRoot();
            return;
        }
        askForStorage();
    }

    private void askForStorage() {
        storageAttempts++;
        core.requestAllFilesAccess(activity);
        core.checkPermission(activity);
    }

    private void askForRoot() {
        asking = true;
        setWorking(rootSpinner, rootStatus, true);
        refreshChrome();

        new Thread(() -> {
            boolean rooted = core.checkRoot();
            uiSafe(() -> {
                rootChecked = true;
                rootGranted = rooted;
                asking = false;
                setWorking(rootSpinner, rootStatus, false);
                if (!rooted) title.setText(R.string.perm_denied_title);
                refreshStatuses();
                refreshChrome();
            });
        }, "perm-root").start();
    }

    private void proceed() {
        asking = true;
        refreshChrome();
        new Thread(() -> {
            if (!setupDone) {
                runSetup();
                setupDone = true;
            }
            uiSafe(() -> {
                asking = false;
                if (rootGranted) {
                    boolean alreadyInstalled =
                            core.checkFolder("/data/local/stryker/release/sdcard/Stryker")
                                    && core.checkFile(Core.CHROOT_MARKER);
                    if (alreadyInstalled) {
                        ((AppIntroActivity) activity).jumpToLast();
                        return;
                    }
                }
                core.moveNext(mPager);
            });
        }, "perm-setup").start();
    }

    private void runSetup() {
        boolean rooted = rootGranted;
        if (rooted) {
            core.customCommand("pm grant com.zalexdev.stryker android.permission.WRITE_EXTERNAL_STORAGE", true);
            core.customCommand("pm grant com.zalexdev.stryker android.permission.READ_EXTERNAL_STORAGE", true);
            core.customCommand("dumpsys deviceidle whitelist +com.zalexdev.stryker", true);
        }

        core.putString("vnc_passwd", "stryker");
        String wlan = "wlan0";
        if (rooted) {
            ArrayList<String> interfaces = core.getInterfacesList();
            if (interfaces.contains("swlan0")) wlan = "swlan0";
        }
        core.putString("wlan_scan", wlan);
        core.putString("wlan_wifi", wlan);
        core.putString("wlan_deauth", wlan);
        core.putString("wlan_wps", wlan);

        core.putInt("max_par", 3);
        core.remove("installed_modules");
        core.putBoolean("first_open", true);
        core.putBoolean("store_scan", true);
        core.putBoolean("auto_update", true);
        copyAssets();
        core.putBoolean("save_aps", true);
        core.putBoolean("autoScan", true);
        core.putBoolean("dash", true);
        core.putInt("night", 2);
        core.putInt("threads", 100);
        if (rooted) core.chmodFolder("/data/data/com.zalexdev.stryker/files");
    }

    private void refreshStatuses() {
        boolean rootless = core.isRootless();
        applyStatus(rootSpinner, rootStatus, rootSub, rootless || (rootChecked && rootGranted),
                getString(rootless ? R.string.perm_root_not_needed : R.string.perm_root_ok),
                getString(rootChecked && !rootGranted
                        ? R.string.perm_root_denied : R.string.perm_root_pending));
        applyStatus(storageSpinner, storageStatus, storageSub, storageOk(),
                getString(R.string.perm_storage_ok),
                getString(storageAttempts > 0
                        ? R.string.perm_storage_denied : R.string.perm_storage_pending));
        applyStatus(batterySpinner, batteryStatus, batterySub, batteryWhitelisted(),
                getString(R.string.perm_battery_ok), getString(R.string.perm_battery_pending));
    }

    private void applyStatus(ProgressBar spinner, ImageView icon, TextView subtitle,
                             boolean ok, String subOk, String subPending) {
        spinner.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        if (ok) {
            icon.setImageResource(R.drawable.done);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.green), PorterDuff.Mode.SRC_IN);
            subtitle.setText(subOk);
        } else {
            icon.setImageResource(R.drawable.question);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.intro_text_dim),
                    PorterDuff.Mode.SRC_IN);
            subtitle.setText(subPending);
        }
    }

    private void setWorking(ProgressBar spinner, ImageView icon, boolean working) {
        spinner.setVisibility(working ? View.VISIBLE : View.GONE);
        icon.setVisibility(working ? View.GONE : View.VISIBLE);
    }

    private boolean batteryWhitelisted() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations("com.zalexdev.stryker");
        } catch (Throwable t) {
            return false;
        }
    }

    private void copyAssets() {
        AssetManager assetManager = activity.getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            StrykerLog.e("Slide2", "Failed to get asset file list.", e);
        }
        if (files == null) return;
        for (String filename : files) {
            if (filename.equals(Core.BUSYBOX_ASSET)) continue;
            if (filename.equals("rootless")) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(filename, AssetManager.ACCESS_STREAMING);
                @SuppressLint("SdCardPath") File outFile =
                        new File("/data/data/com.zalexdev.stryker/files/", filename);
                out = new FileOutputStream(outFile);
                copyFile(in, out);
                out.flush();
            } catch (IOException ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
        Core.extractBusybox(activity);
        if (!core.isRootless()) {
            core.customCommand("dos2unix /data/data/com.zalexdev.stryker/files/*.sh", true);
            core.customCommand("dos2unix /data/data/com.zalexdev.stryker/files/*root*", true);
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void refreshChrome() {
        if (activity instanceof AppIntroActivity) ((AppIntroActivity) activity).refreshPrimary();
    }

    private void uiSafe(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> {
            if (isAdded()) r.run();
        });
    }
}
