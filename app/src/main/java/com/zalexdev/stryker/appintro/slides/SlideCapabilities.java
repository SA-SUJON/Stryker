package com.zalexdev.stryker.appintro.slides;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.engine.DeviceCapabilities;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.utils.Core;


public class SlideCapabilities extends Fragment implements IntroPage {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private com.airbnb.lottie.LottieAnimationView gears;
    private TextView headline;
    private TextView status;

    private volatile boolean abandoned;
    private boolean started;
    private boolean finished;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_capabilities, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        gears = view.findViewById(R.id.caps_gears);
        com.zalexdev.stryker.appintro.IntroLayout.centerOn(
                gears, view.findViewById(R.id.caps_eyebrow));
        headline = view.findViewById(R.id.verdict_title);
        status = view.findViewById(R.id.scan_status);

        MaterialButton skip = view.findViewById(R.id.skip_detection);
        skip.setOnClickListener(v -> skip());

        startProbe();
        return view;
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(R.string.intro_action_continue);
    }

    @Override
    public boolean primaryEnabled() {
        return finished;
    }

    @Override
    public void onPrimary() {
        EngineType picked = DeviceCapabilities.recommended(core);
        if (picked == null) {
            core.moveNext(mPager);
            return;
        }
        EngineType.persist(core, picked);
        ((AppIntroActivity) activity).applyEngineFlow(picked);
    }

    private void startProbe() {
        if (started) return;
        started = true;
        new Thread(() -> {
            DeviceCapabilities.Report report = DeviceCapabilities.run(context,
                    new DeviceCapabilities.Listener() {
                        @Override
                        public void onCheckStarted(DeviceCapabilities.Check check) {
                            runOnUi(() -> status.setText(getString(R.string.setup_caps_running,
                                    getString(check.labelRes))));
                        }

                        @Override
                        public void onCheckFinished(DeviceCapabilities.Finding finding) {
                        }
                    });
            runOnUi(() -> finish(report));
        }, "capability-run").start();
    }

    private void skip() {
        abandoned = true;
        core.putBoolean(DeviceCapabilities.K_SKIPPED, true);
        core.moveNext(mPager);
    }

    private void finish(DeviceCapabilities.Report report) {
        DeviceCapabilities.persist(core, report);
        finished = true;

        headline.setText(verdictTitleFor(report.recommended()));
        headline.setTextColor(ContextCompat.getColor(context,
                report.needsManualInstall() ? R.color.red : R.color.intro_text));
        status.setText(report.summary);

        if (activity instanceof AppIntroActivity) ((AppIntroActivity) activity).refreshPrimary();
    }

    private int verdictTitleFor(EngineType recommended) {
        if (recommended == null) return R.string.setup_verdict_none;
        switch (recommended) {
            case CHROOT: return R.string.setup_verdict_root;
            case UML:    return R.string.setup_verdict_uml;
            default:     return R.string.setup_verdict_qemu;
        }
    }

    private void runOnUi(Runnable r) {
        if (activity == null || abandoned) return;
        activity.runOnUiThread(() -> {
            if (!abandoned && isAdded()) r.run();
        });
    }

    @Override
    public void onDestroyView() {
        abandoned = true;
        super.onDestroyView();
    }
}
