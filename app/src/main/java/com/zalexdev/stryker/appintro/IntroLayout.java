package com.zalexdev.stryker.appintro;

import android.view.View;

public final class IntroLayout {

    private IntroLayout() {}

    public static void centerOn(final View target, final View anchor) {
        if (target == null || anchor == null) return;
        View.OnLayoutChangeListener listener = (v, l, t, r, b, ol, ot, or, ob) -> apply(target, anchor);
        anchor.addOnLayoutChangeListener(listener);
        target.addOnLayoutChangeListener(listener);
        apply(target, anchor);
    }

    private static void apply(View target, View anchor) {
        if (anchor.getWidth() == 0 || target.getWidth() == 0) return;
        float anchorCentre = anchor.getLeft() + anchor.getWidth() / 2f;
        float targetCentre = target.getLeft() + target.getWidth() / 2f;
        float shift = anchorCentre - targetCentre;
        if (target.getTranslationX() != shift) target.setTranslationX(shift);
    }
}
