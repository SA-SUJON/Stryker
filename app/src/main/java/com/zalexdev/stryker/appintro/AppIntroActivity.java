package com.zalexdev.stryker.appintro;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.airbnb.lottie.LottieAnimationView;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.slides.Slide1;
import com.zalexdev.stryker.appintro.slides.Slide2;
import com.zalexdev.stryker.appintro.slides.Slide3;
import com.zalexdev.stryker.appintro.slides.Slide6Final;
import com.zalexdev.stryker.appintro.slides.SlideCapabilities;
import com.zalexdev.stryker.appintro.slides.SlideEngineSelect;
import com.zalexdev.stryker.appintro.slides.SlidePCheck;
import com.zalexdev.stryker.appintro.slides.SlideQemuInstall;
import com.zalexdev.stryker.appintro.slides.SlideWelcome;
import com.zalexdev.stryker.engine.EngineType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AppIntroActivity extends FragmentActivity {

    public static final String EXTRA_MIGRATE = "migrate_legacy_chroot";

    public static final String EXTRA_REPAIR_ENGINE = "repair_engine";

    public static final String EXTRA_UPDATE_ENGINE = "update_engine";

    public static final String EXTRA_REPLACE_PAYLOAD = "replace_payload";

    public enum Page { WELCOME, CONSENT, CAPS, ENGINE, PERMS, PCHECK, INSTALL_CHROOT, INSTALL_QEMU, FINAL }

    public static Intent updateIntent(Context context, EngineType engine) {
        Intent intent = new Intent(context, AppIntroActivity.class);
        intent.putExtra(EXTRA_UPDATE_ENGINE, engine.name());
        return intent;
    }

    public static Intent replaceIntent(Context context, EngineType engine) {
        Intent intent = updateIntent(context, engine);
        intent.putExtra(EXTRA_REPLACE_PAYLOAD, true);
        return intent;
    }

    public boolean isMigration() {
        return getIntent() != null && getIntent().getBooleanExtra(EXTRA_MIGRATE, false);
    }

    public boolean isUpdate() {
        return updateTarget() != null;
    }

    public boolean replacesPayload() {
        return getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_REPLACE_PAYLOAD, false);
    }

    private final List<Page> pages = new ArrayList<>(Arrays.asList(
            Page.WELCOME, Page.CONSENT, Page.CAPS, Page.ENGINE, Page.PERMS, Page.PCHECK,
            Page.INSTALL_CHROOT, Page.FINAL));

    private ViewPager2 mPager;
    private ScreenPagerAdapter pagerAdapter;
    private IntroBackgroundView background;
    private LinearLayout dots;
    private MaterialButton primary;
    private View bottomBar;
    private View wordmark;
    private GestureDetector swipes;

    private static final float SWIPE_MIN_DP = 64f;
    private static final float SWIPE_MIN_VELOCITY_DP = 220f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_app_intro);

        mPager = findViewById(R.id.view_pager);
        background = findViewById(R.id.intro_background);
        dots = findViewById(R.id.intro_dots);
        primary = findViewById(R.id.intro_primary);
        bottomBar = findViewById(R.id.intro_bottom_bar);
        wordmark = findViewById(R.id.intro_wordmark);
        applyWindowInsets();
        setUpSwipes();

        if (isMigration()) pages.remove(Page.WELCOME);
        applyRepairFlow();
        applyUpdateFlow();

        mPager.setUserInputEnabled(false);
        mPager.setPageTransformer(new SlideFadeTransformer());
        pagerAdapter = new ScreenPagerAdapter(this, pages);
        mPager.setAdapter(pagerAdapter);
        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float offset, int offsetPx) {
                if (background != null) background.setPageOffset(position + offset);
            }

            @Override
            public void onPageSelected(int position) {
                bindChrome(position);
            }
        });

        primary.setOnClickListener(v -> {
            IntroPage page = currentPage();
            if (page != null) page.onPrimary();
        });

        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull FragmentManager fm,
                                                  @NonNull Fragment f) {
                        if (f instanceof IntroPage) refreshPrimary();
                        resumeAnimations(f);
                    }

                    @Override
                    public void onFragmentPaused(@NonNull FragmentManager fm,
                                                 @NonNull Fragment f) {
                        pauseAnimations(f);
                    }
                }, false);

        buildDots();
        mPager.post(() -> bindChrome(mPager.getCurrentItem()));
    }

    private final java.util.Map<Fragment, java.util.List<LottieAnimationView>> pausedAnimations =
            new java.util.WeakHashMap<>();

    private void pauseAnimations(Fragment f) {
        View root = f.getView();
        if (root == null) return;
        java.util.List<LottieAnimationView> stopped = new java.util.ArrayList<>();
        for (LottieAnimationView v : findLottie(root)) {
            if (!v.isAnimating()) continue;
            v.pauseAnimation();
            stopped.add(v);
        }
        if (stopped.isEmpty()) pausedAnimations.remove(f);
        else pausedAnimations.put(f, stopped);
    }

    private void resumeAnimations(Fragment f) {
        View root = f.getView();
        if (root == null) {
            pausedAnimations.remove(f);
            return;
        }
        java.util.List<LottieAnimationView> stopped = pausedAnimations.remove(f);
        for (LottieAnimationView v : findLottie(root)) {
            if (stopped != null && stopped.contains(v)) {
                v.resumeAnimation();
            } else if (AUTOPLAY_TAG.equals(v.getTag()) && !v.isAnimating()) {
                v.playAnimation();
            }
        }
    }

    private static final String AUTOPLAY_TAG = "intro_autoplay";

    private static java.util.List<LottieAnimationView> findLottie(View root) {
        java.util.List<LottieAnimationView> out = new java.util.ArrayList<>();
        collectLottie(root, out);
        return out;
    }

    private static void collectLottie(View v, java.util.List<LottieAnimationView> out) {
        if (v instanceof LottieAnimationView) {
            out.add((LottieAnimationView) v);
            return;
        }
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) collectLottie(g.getChildAt(i), out);
    }

    private void applyWindowInsets() {
        final int wordmarkTop = wordmark.getPaddingTop();
        final int barBottom = bottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
                (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                    ViewGroup.MarginLayoutParams lp =
                            (ViewGroup.MarginLayoutParams) wordmark.getLayoutParams();
                    lp.topMargin = bars.top + wordmarkTop;
                    wordmark.setLayoutParams(lp);

                    bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                            bottomBar.getPaddingRight(), barBottom + bars.bottom);
                    mPager.setPadding(0, 0, 0, bars.bottom);
                    return windowInsets;
                });
    }

    private void setUpSwipes() {
        float d = getResources().getDisplayMetrics().density;
        final float minDistance = SWIPE_MIN_DP * d;
        final float minVelocity = SWIPE_MIN_VELOCITY_DP * d;

        swipes = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null || up == null) return false;
                float dx = up.getX() - down.getX();
                float dy = up.getY() - down.getY();
                if (Math.abs(dx) < Math.abs(dy)) return false;
                if (dx > -minDistance || Math.abs(vx) < minVelocity) return false;

                IntroPage page = currentPage();
                if (page != null && page.primaryVisible() && page.primaryEnabled()) {
                    page.onPrimary();
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipes != null) swipes.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void applyRepairFlow() {
        EngineType target = engineExtra(EXTRA_REPAIR_ENGINE);
        if (target == null || target == EngineType.CHROOT) return;

        EngineType.persist(new com.zalexdev.stryker.utils.Core(this), target);
        pages.clear();
        pages.add(Page.INSTALL_QEMU);
        pages.add(Page.FINAL);
    }

    private EngineType updateTarget() {
        return engineExtra(EXTRA_UPDATE_ENGINE);
    }

    private void applyUpdateFlow() {
        EngineType target = updateTarget();
        if (target == null) return;
        pages.clear();
        if (target == EngineType.CHROOT) {
            pages.add(Page.PCHECK);
            pages.add(Page.INSTALL_CHROOT);
        } else {
            pages.add(Page.INSTALL_QEMU);
        }
        pages.add(Page.FINAL);
    }

    private EngineType engineExtra(String key) {
        String name = getIntent() == null ? null : getIntent().getStringExtra(key);
        if (name == null || name.isEmpty()) return null;
        for (EngineType t : EngineType.values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    public void applyEngineFlow(EngineType type) {
        int decidedAt = mPager.getCurrentItem();
        if (decidedAt < 0 || decidedAt >= pages.size()) return;

        List<Page> kept = new ArrayList<>(pages.subList(0, decidedAt + 1));
        boolean permsDone = kept.contains(Page.PERMS);
        boolean pcheckDone = kept.contains(Page.PCHECK);

        while (pages.size() > decidedAt + 1) pages.remove(pages.size() - 1);
        if (!permsDone) pages.add(Page.PERMS);
        if (type == EngineType.ROOTLESS || type == EngineType.UML) {
            pages.add(Page.INSTALL_QEMU);
        } else {
            if (!pcheckDone) pages.add(Page.PCHECK);
            pages.add(Page.INSTALL_CHROOT);
        }
        pages.add(Page.FINAL);

        pagerAdapter.notifyDataSetChanged();
        buildDots();
        mPager.post(() -> {
            mPager.setCurrentItem(Math.min(decidedAt + 1, pages.size() - 1), true);
            bindChrome(mPager.getCurrentItem());
        });
    }

    public void jumpToLast() {
        mPager.setCurrentItem(pages.size() - 1);
    }

    private void bindChrome(int position) {
        updateDots(position);

        IntroPage page = currentPage();
        if (page == null || !page.primaryVisible()) {
            primary.setVisibility(View.INVISIBLE);
            return;
        }
        primary.setVisibility(View.VISIBLE);
        primary.setText(page.primaryLabel(this));
        primary.setEnabled(page.primaryEnabled());
        primary.setAlpha(page.primaryEnabled() ? 1f : 0.45f);
    }

    public void refreshPrimary() {
        if (mPager != null) bindChrome(mPager.getCurrentItem());
    }

    private IntroPage currentPage() {
        if (mPager == null || pages.isEmpty()) return null;
        int position = mPager.getCurrentItem();
        if (position < 0 || position >= pages.size()) return null;
        Fragment f = getSupportFragmentManager()
                .findFragmentByTag("f" + pages.get(position).ordinal());
        return f instanceof IntroPage ? (IntroPage) f : null;
    }

    private void buildDots() {
        if (dots == null) return;
        dots.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams((int) (6 * d), (int) (6 * d));
            lp.setMarginEnd((int) (6 * d));
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.intro_dot);
            dots.addView(dot);
        }
        updateDots(mPager == null ? 0 : mPager.getCurrentItem());
    }

    private void updateDots(int position) {
        if (dots == null) return;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean active = i == position;
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = (int) ((active ? 20 : 6) * d);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(active ? R.drawable.intro_dot_active : R.drawable.intro_dot);
        }
    }

    public int bottomBarHeight() {
        return bottomBar == null ? 0 : bottomBar.getHeight();
    }

    @Override
    public void onBackPressed() {
    }

    private static class ScreenPagerAdapter extends FragmentStateAdapter {
        private final List<Page> pages;

        ScreenPagerAdapter(@NonNull FragmentActivity a, List<Page> pages) {
            super(a);
            this.pages = pages;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (pages.get(position)) {
                case WELCOME: return new SlideWelcome();
                case CONSENT: return new Slide1();
                case CAPS: return new SlideCapabilities();
                case ENGINE: return new SlideEngineSelect();
                case PERMS: return new Slide2();
                case PCHECK: return new SlidePCheck();
                case INSTALL_CHROOT: return new Slide3();
                case INSTALL_QEMU: return new SlideQemuInstall();
                case FINAL:
                default: return new Slide6Final();
            }
        }

        @Override
        public int getItemCount() { return pages.size(); }

        @Override
        public long getItemId(int position) { return pages.get(position).ordinal(); }

        @Override
        public boolean containsItem(long itemId) {
            for (Page p : pages) if (p.ordinal() == itemId) return true;
            return false;
        }
    }

    private static class SlideFadeTransformer implements ViewPager2.PageTransformer {
        @Override
        public void transformPage(@NonNull View page, float position) {
            float abs = Math.abs(position);
            if (abs >= 1f) {
                page.setAlpha(0f);
                page.setTranslationX(0f);
                return;
            }
            page.setAlpha(1f - abs);
            page.setTranslationX(-position * page.getWidth() * 0.12f);
        }
    }
}
