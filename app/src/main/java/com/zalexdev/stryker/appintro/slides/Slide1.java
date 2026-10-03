package com.zalexdev.stryker.appintro.slides;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.utils.Core;

public class Slide1 extends Fragment implements IntroPage {

    private static final int SIGNATURE_LAST_FRAME = 220;

    private com.airbnb.lottie.LottieAnimationView hand;

    private Core core;
    private ViewPager2 mPager;
    private MaterialCheckBox box1, box2, box3;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide1, container, false);
        core = new Core(getContext());
        mPager = requireActivity().findViewById(R.id.view_pager);

        hand = view.findViewById(R.id.consent_hand);
        hand.setMaxFrame(SIGNATURE_LAST_FRAME);
        com.zalexdev.stryker.appintro.IntroLayout.centerOn(
                hand, view.findViewById(R.id.consent_eyebrow));

        box1 = view.findViewById(R.id.slide_checkbox);
        box2 = view.findViewById(R.id.slide_checkbox2);
        box3 = view.findViewById(R.id.slide_checkbox3);

        bindRow(view, R.id.consent_row_1, box1);
        bindRow(view, R.id.consent_row_2, box2);
        bindRow(view, R.id.consent_row_3, box3);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hand != null && hand.getProgress() == 0f) hand.playAnimation();
    }

    private void bindRow(View root, int rowId, MaterialCheckBox box) {
        root.findViewById(rowId).setOnClickListener(v -> {
            box.setChecked(!box.isChecked());
            if (getActivity() instanceof AppIntroActivity) {
                ((AppIntroActivity) getActivity()).refreshPrimary();
            }
        });
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(R.string.intro_action_continue);
    }

    @Override
    public boolean primaryEnabled() {
        return box1 != null && box1.isChecked()
                && box2 != null && box2.isChecked()
                && box3 != null && box3.isChecked();
    }

    @Override
    public void onPrimary() {
        core.moveNext(mPager);
    }
}
