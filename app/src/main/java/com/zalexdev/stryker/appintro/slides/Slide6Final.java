package com.zalexdev.stryker.appintro.slides;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.IntroLayout;
import com.zalexdev.stryker.appintro.IntroPage;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.utils.Core;

public class Slide6Final extends Fragment implements IntroPage {

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide5, container, false);
        Core core = new Core(getContext());

        TextView successSub = view.findViewById(R.id.success_subtitle);
        TextView shellText = view.findViewById(R.id.cap_shell_text);

        if (EngineType.isRootless(core)) {
            successSub.setText(R.string.final_guest_body);
            shellText.setText(R.string.final_guest_shell);
        } else {
            successSub.setText(getString(R.string.final_chroot_body, Core.CHROOT_ROOT));
            shellText.setText(R.string.final_chroot_shell);
        }

        IntroLayout.centerOn(view.findViewById(R.id.final_confetti),
                view.findViewById(R.id.final_eyebrow));
        return view;
    }


    @Override
    public CharSequence primaryLabel(Context context) {
        return context.getString(R.string.intro_action_launch);
    }

    @Override
    public void onPrimary() {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.finish();
    }
}
