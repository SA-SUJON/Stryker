package com.zalexdev.stryker.appintro;

import android.content.Context;

public interface IntroPage {

    CharSequence primaryLabel(Context context);

    void onPrimary();

    default boolean primaryEnabled() {
        return true;
    }

    default boolean primaryVisible() {
        return true;
    }
}
