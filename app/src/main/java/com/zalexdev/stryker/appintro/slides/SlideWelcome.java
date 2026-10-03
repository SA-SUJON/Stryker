package com.zalexdev.stryker.appintro.slides;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.IntroPage;

public class SlideWelcome extends Fragment implements IntroPage {

    private static final long ENTER_MS = 620L;
    private static final float RISE_DP = 26f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide_welcome, container, false);

        com.zalexdev.stryker.appintro.IntroLayout.centerOn(
                view.findViewById(R.id.welcome_wave),
                view.findViewById(R.id.welcome_eyebrow));

        float rise = RISE_DP * getResources().getDisplayMetrics().density;
        enter(view.findViewById(R.id.welcome_wave), 60L, rise);
        enter(view.findViewById(R.id.welcome_eyebrow), 140L, rise);
        enter(view.findViewById(R.id.welcome_title), 240L, rise);
        enter(view.findViewById(R.id.welcome_subtitle), 340L, rise);

        return view;
    }

    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(R.string.intro_action_start);
    }

    @Override
    public void onPrimary() {
        ViewPager2 pager = requireActivity().findViewById(R.id.view_pager);
        if (pager != null) pager.setCurrentItem(pager.getCurrentItem() + 1);
    }

    private void enter(View view, long delay, float fromY) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setTranslationY(fromY);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(ENTER_MS)
                .setInterpolator(new DecelerateInterpolator(2f))
                .start();
    }
}
