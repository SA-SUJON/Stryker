package com.zalexdev.stryker.appintro.slides;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.radiobutton.MaterialRadioButton;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.engine.DeviceCapabilities;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.utils.Core;

import java.util.List;

public class SlideEngineSelect extends Fragment implements IntroPage {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private View rowRootless, rowChroot, rowUml;
    private MaterialRadioButton checkRootless, checkChroot, checkUml;

    private EngineType selected = EngineType.CHROOT;
    private boolean rootlessSupported;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_engine, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        rowRootless = view.findViewById(R.id.card_rootless);
        rowChroot = view.findViewById(R.id.card_chroot);
        rowUml = view.findViewById(R.id.card_uml);
        checkRootless = view.findViewById(R.id.check_rootless);
        checkChroot = view.findViewById(R.id.check_chroot);
        checkUml = view.findViewById(R.id.check_uml);
        TextView rootlessNote = view.findViewById(R.id.rootless_note);
        TextView umlNote = view.findViewById(R.id.uml_note);

        rootlessSupported = EngineType.rootlessSupported(context);
        rowChroot.setOnClickListener(v -> select(EngineType.CHROOT));

        if (rootlessSupported) {
            selected = EngineType.ROOTLESS;
            rowRootless.setOnClickListener(v -> select(EngineType.ROOTLESS));
        } else {
            rootlessNote.setText(R.string.engine_needs_arm64);
            rowRootless.setAlpha(0.4f);
            selected = EngineType.CHROOT;
        }

        EngineType recommended = DeviceCapabilities.recommended(core);
        if (recommended != null && (rootlessSupported || recommended == EngineType.CHROOT)) {
            markRecommended(view, recommended);
            selected = recommended;
        }

        if (!rootlessSupported) {
            umlNote.setText(R.string.engine_needs_arm64);
            rowUml.setAlpha(0.4f);
        } else if (umlRuledOut()) {
            umlNote.setText(R.string.engine_uml_blocked);
            rowUml.setAlpha(0.4f);
            if (selected == EngineType.UML) selected = EngineType.ROOTLESS;
        } else {
            rowUml.setOnClickListener(v -> select(EngineType.UML));
        }

        applySelectionUi();
        return view;
    }

    private boolean umlRuledOut() {
        List<EngineType> detected = DeviceCapabilities.plan(core);
        return !detected.isEmpty()
                && !detected.contains(EngineType.CHROOT)
                && !detected.contains(EngineType.UML);
    }

    private void markRecommended(View view, EngineType type) {
        view.findViewById(R.id.rec_rootless)
                .setVisibility(type == EngineType.ROOTLESS ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.rec_chroot)
                .setVisibility(type == EngineType.CHROOT ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.rec_uml)
                .setVisibility(type == EngineType.UML ? View.VISIBLE : View.GONE);
    }

    private void select(EngineType type) {
        if ((type == EngineType.ROOTLESS || type == EngineType.UML) && !rootlessSupported) return;
        selected = type;
        applySelectionUi();
    }

    private void applySelectionUi() {
        checkChroot.setChecked(selected == EngineType.CHROOT);
        checkUml.setChecked(selected == EngineType.UML);
        checkRootless.setChecked(selected == EngineType.ROOTLESS);
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(R.string.intro_action_continue);
    }

    @Override
    public void onPrimary() {
        EngineType.persist(core, selected);
        ((AppIntroActivity) activity).applyEngineFlow(selected);
    }
}
