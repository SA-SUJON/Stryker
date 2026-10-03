package com.zalexdev.stryker.appintro.slides;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.utils.Core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class SlidePCheck extends Fragment implements IntroPage {

    private static final String PREF_CHECKED = "pcheck_checked";
    private static final String PREF_ROOT = "pcheck_root";
    private static final String PREF_MON = "pcheck_monitor";
    private static final String PREF_USB = "pcheck_usb";
    private static final String PREF_MANUFACT = "pcheck_manufacture";
    private static final String PREF_SPACE = "pcheck_space";
    private static final String PREF_FREE_GB = "pcheck_free_gb";

    private static final long REQUIRED_FREE_GB = 4;

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;
    private View cardView;
    private TextView capabilitiesLabel;
    private TextView disclaimer;
    private boolean gateReady;
    private boolean running;
    private LinearProgressIndicator progressIndicator;

    private TextView rootSub, monMode, usbOtg, manufacture;
    private View rowRoot, rowMonitor, rowUsb, rowDevice, rowSpace;
    private TextView summary;
    private TextView spaceSub, spaceBadge;

    private boolean checked = false;
    private boolean switchToRootless = false;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    @SuppressLint("SetTextI18n")
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_pcheck, container, false);
        activity = getActivity();
        if (activity == null) return view;
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        if (core.isRootless()) {
            view.post(() -> core.moveNext(mPager));
            return view;
        }

        cardView = view.findViewById(R.id.check_card);
        capabilitiesLabel = view.findViewById(R.id.capabilities_label);
        disclaimer = view.findViewById(R.id.disclaimer);
        progressIndicator = view.findViewById(R.id.progress_indicator);

        rootSub = view.findViewById(R.id.isRootOk);
        monMode = view.findViewById(R.id.isMonitModeOk);
        usbOtg = view.findViewById(R.id.isUsbOtgOk);
        manufacture = view.findViewById(R.id.isManufactureOk);
        spaceSub = view.findViewById(R.id.isSpaceOk);
        summary = view.findViewById(R.id.pcheck_summary);

        rowRoot = view.findViewById(R.id.row_root);
        rowMonitor = view.findViewById(R.id.row_monitor);
        rowUsb = view.findViewById(R.id.row_usb);
        rowDevice = view.findViewById(R.id.row_device);
        rowSpace = view.findViewById(R.id.row_space);


        if (core.getBoolean(PREF_CHECKED)) {
            restoreResults();
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!checked && !running) runCheck();
    }

    @SuppressLint("SetTextI18n")
    private void restoreResults() {
        boolean rootOk = core.getBoolean(PREF_ROOT);
        boolean monFinal = core.getBoolean(PREF_MON);
        boolean usbOk = core.getBoolean(PREF_USB);
        boolean manufactOk = core.getBoolean(PREF_MANUFACT);
        long freeGb = 0;
        try {
            freeGb = Long.parseLong(core.getString(PREF_FREE_GB));
        } catch (NumberFormatException ignored) {
        }
        boolean spaceOk = core.getBoolean(PREF_SPACE) && freeGb >= REQUIRED_FREE_GB;

        progressIndicator.setVisibility(View.GONE);
        cardView.setVisibility(View.VISIBLE);
        capabilitiesLabel.setVisibility(View.VISIBLE);
        disclaimer.setVisibility(View.VISIBLE);

        renderRows(rootOk, monFinal, usbOk, manufactOk, spaceOk, freeGb);

        checked = true;
        applyGate(rootOk);
    }

    private void applyGate(boolean rootOk) {
        switchToRootless = !rootOk && EngineType.rootlessSupported(context);
        gateReady = rootOk || switchToRootless;
        refreshChrome();
    }

    private void refreshChrome() {
        if (activity instanceof AppIntroActivity) ((AppIntroActivity) activity).refreshPrimary();
    }

    @SuppressLint("SetTextI18n")
    private void renderRows(boolean rootOk, boolean monFinal, boolean usbOk,
                            boolean manufactOk, boolean spaceOk, long freeGb) {
        boolean archOk = Core.isArm64();
        boolean deviceOk = manufactOk && archOk;

        applyRow(rootOk, rowRoot, rootSub,
                "No su — install Magisk or another root manager");
        applyRow(monFinal, rowMonitor, monMode,
                "Monitor mode unlikely — capture and handshake tools may fail");
        applyRow(usbOk, rowUsb, usbOtg,
                "No USB host mode — external adapters will not work");
        applyRow(deviceOk, rowDevice, manufacture,
                archOk
                        ? "Samsung stock ROM — WiFi and local scan may misbehave"
                        : primaryAbi() + " — Stryker is arm64-v8a only");
        applyRow(spaceOk, rowSpace, spaceSub,
                "Only " + freeGb + " GB free — the chroot needs about "
                        + REQUIRED_FREE_GB + " GB");

        boolean allOk = rootOk && monFinal && usbOk && deviceOk && spaceOk;
        summary.setText(allOk
                ? getString(R.string.pcheck_all_ok) : getString(R.string.pcheck_some_issues));

        if (!archOk) {
            disclaimer.setText("This device reports " + primaryAbi()
                    + ". Stryker is arm64-v8a only — the chroot cannot be installed.");
        }
    }

    private static String primaryAbi() {
        if (Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0) return "an unknown CPU";
        return Build.SUPPORTED_ABIS[0];
    }

    @SuppressLint("SetTextI18n")
    private void runCheck() {
        progressIndicator.setVisibility(View.VISIBLE);
        running = true;
        gateReady = false;
        refreshChrome();
        cancelled.set(false);

        new Thread(() -> {
            boolean rootOk = core.checkRoot();
            if (cancelled.get()) return;
            boolean usbOk = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);

            boolean monAvail = core.checkFile("/sys/module/wlan/parameters/con_mode");
            if (monAvail) {
                monAvail = Core.contains(core.customCommand(
                                "cat /proc/cpuinfo | grep \"Hardware\" | sed \"s/^Hardware.*: \\(.*\\)/\\1/g\""),
                        "Qualcomm");
            }
            boolean monFinal = monAvail;

            boolean manufactOk = !Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("samsung");

            long freeGb = freeStorageGb();
            boolean spaceOk = freeGb >= REQUIRED_FREE_GB;

            core.putBoolean(PREF_ROOT, rootOk);
            core.putBoolean(PREF_MON, monFinal);
            core.putBoolean(PREF_USB, usbOk);
            core.putBoolean(PREF_MANUFACT, manufactOk);
            core.putBoolean(PREF_SPACE, spaceOk);
            core.putString(PREF_FREE_GB, String.valueOf(freeGb));
            core.putBoolean(PREF_CHECKED, true);

            if (cancelled.get() || activity == null || !isAdded()) return;

            activity.runOnUiThread(() -> {
                if (cancelled.get() || activity == null || !isAdded()) return;
                progressIndicator.setVisibility(View.GONE);
                cardView.setVisibility(View.VISIBLE);
                capabilitiesLabel.setVisibility(View.VISIBLE);
                disclaimer.setVisibility(View.VISIBLE);

                renderRows(rootOk, monFinal, usbOk, manufactOk, spaceOk, freeGb);

                checked = true;
                running = false;
                applyGate(rootOk);
            });
        }).start();
    }

    private void applyRow(boolean ok, View row, TextView detail, String warnText) {
        row.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) detail.setText(warnText);
    }

    @Override
    public void onDestroyView() {
        cancelled.set(true);
        super.onDestroyView();
    }

    @SuppressLint("UsableSpace")
    private long freeStorageGb() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long bytes = stat.getAvailableBytes();
            return bytes / (1024L * 1024L * 1024L);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        if (running) return context.getString(R.string.pcheck_running);
        if (!checked) return context.getString(R.string.pcheck_run);
        if (switchToRootless) return context.getString(R.string.pcheck_switch_rootless);
        return context.getString(gateReady
                ? R.string.intro_action_next : R.string.pcheck_no_root);
    }

    @Override
    public boolean primaryEnabled() {
        if (running) return false;
        if (!checked) return true;
        return gateReady;
    }

    @Override
    public void onPrimary() {
        if (switchToRootless) {
            EngineType.persist(core, EngineType.ROOTLESS);
            ((AppIntroActivity) activity).applyEngineFlow(EngineType.ROOTLESS);
            return;
        }
        if (checked) {
            core.moveNext(mPager);
            return;
        }
        runCheck();
    }
}
