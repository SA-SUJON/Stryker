package com.zalexdev.stryker.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.logger.LoggerFragment;
import com.zalexdev.stryker.metasploit.utils.MetasploitUtils;
import com.zalexdev.stryker.settings.commands.CustomCommandsWifi;
import com.zalexdev.stryker.utils.Core;

import java.util.Objects;

public class SettingsHomeFragment extends Fragment {

    private static final int DEFAULT_MAX_PAR = 3;

    private Activity activity;
    private Context context;
    private Core core;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        return inflater.inflate(R.layout.settings_main, container, false);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        SwitchMaterial saveAps = view.findViewById(R.id.save_aps);
        SwitchMaterial hide = view.findViewById(R.id.hide);
        SwitchMaterial autoScan = view.findViewById(R.id.autoscan_switch);
        SwitchMaterial autoWifi = view.findViewById(R.id.autowifi_switch);
        SwitchMaterial promo = view.findViewById(R.id.promo_switch);
        SwitchMaterial autoBanner = view.findViewById(R.id.banner_detect);
        SwitchMaterial pixieIfaceDown = view.findViewById(R.id.pixie_iface_down_switch);
        LinearLayout pixieIfaceDownRow = view.findViewById(R.id.pixie_iface_down_row);
        LinearLayout internalDeauthRow = view.findViewById(R.id.internal_deauth_row);
        View internalDeauthDivider = view.findViewById(R.id.internal_deauth_divider);
        SwitchMaterial internalDeauth = view.findViewById(R.id.internal_deauth_switch);
        LinearLayout autoWifiRow = view.findViewById(R.id.autowifi_row);
        LinearLayout promoRow = view.findViewById(R.id.promo_row);
        LinearLayout saveApsRow = view.findViewById(R.id.save_aps_row);
        LinearLayout autoScanRow = view.findViewById(R.id.autoscan_row);
        LinearLayout bannerRow = view.findViewById(R.id.banner_row);
        LinearLayout hideRow = view.findViewById(R.id.hide_row);
        LinearLayout geoBgRow = view.findViewById(R.id.geo_bg_row);
        LinearLayout geoSatelliteRow = view.findViewById(R.id.geo_satellite_row);
        SwitchMaterial geoBg = view.findViewById(R.id.geo_bg_switch);
        SwitchMaterial geoSatellite = view.findViewById(R.id.geo_satellite_switch);
        LinearLayout unmountLayout = view.findViewById(R.id.unmount_view);
        LinearLayout deleteLayout = view.findViewById(R.id.delete_view);
        LinearLayout debug = view.findViewById(R.id.debug_view);
        LinearLayout maxPar = view.findViewById(R.id.max_par);
        LinearLayout changeInterfaces = view.findViewById(R.id.change_interfaces);
        LinearLayout changeCommands = view.findViewById(R.id.change_commands);
        TextView maxParCount = view.findViewById(R.id.max_par_count);

        saveAps.setChecked(core.isStoreEnabled());
        autoBanner.setChecked(core.isBannerScanEnabled());
        pixieIfaceDown.setChecked(core.isPixieIfaceDown());
        internalDeauth.setChecked(core.isInternalDeauthEnabled());
        hide.setChecked(core.getBoolean("hide"));
        autoWifi.setChecked(core.getBoolean("wifi"));
        autoScan.setChecked(core.getBoolean("autoScan"));
        maxParCount.setText(String.valueOf(currentMaxPar()));

        saveAps.setOnCheckedChangeListener((btn, b) -> core.putBoolean("save_aps", b));
        autoBanner.setOnCheckedChangeListener((btn, b) -> core.putBoolean("autoBanner", b));
        pixieIfaceDown.setOnCheckedChangeListener((btn, b) -> core.putBoolean("pixie_iface_down", b));
        internalDeauth.setOnCheckedChangeListener((btn, b) -> core.putBoolean("internal_deauth", b));
        hide.setOnCheckedChangeListener((btn, b) -> core.putBoolean("hide", b));
        autoWifi.setOnCheckedChangeListener((btn, b) -> core.putBoolean("wifi", b));
        autoScan.setOnCheckedChangeListener((btn, b) -> core.putBoolean("autoScan", b));

        promo.setChecked(!core.getBoolean(
                com.zalexdev.stryker.utils.PromoDialogs.KEY_MUTED));
        promo.setOnCheckedChangeListener((btn, b) -> core.putBoolean(
                com.zalexdev.stryker.utils.PromoDialogs.KEY_MUTED, !b));
        bindRowToSwitch(promoRow, promo);
        bindRowToSwitch(autoWifiRow, autoWifi);
        bindRowToSwitch(saveApsRow, saveAps);
        bindRowToSwitch(pixieIfaceDownRow, pixieIfaceDown);
        bindRowToSwitch(internalDeauthRow, internalDeauth);
        if (core.isRootless()) {
            internalDeauthRow.setVisibility(View.GONE);
            internalDeauthDivider.setVisibility(View.GONE);
        }
        bindRowToSwitch(autoScanRow, autoScan);
        bindRowToSwitch(bannerRow, autoBanner);
        bindRowToSwitch(hideRow, hide);

        geoBg.setChecked(core.getBoolean("geomac_bg_scan"));
        geoSatellite.setChecked(core.getBoolean("geomac_satellite"));
        geoSatellite.setOnCheckedChangeListener((btn, b) -> core.putBoolean("geomac_satellite", b));
        geoBg.setOnCheckedChangeListener((btn, b) -> core.putBoolean("geomac_bg_scan", b));
        bindRowToSwitch(geoBgRow, geoBg);
        bindRowToSwitch(geoSatelliteRow, geoSatellite);

        LinearLayout msfAutostartRow = view.findViewById(R.id.msf_autostart_row);
        LinearLayout msfStopRow = view.findViewById(R.id.msf_stop_row);
        SwitchMaterial msfAutostart = view.findViewById(R.id.msf_autostart_switch);
        msfAutostart.setChecked(core.getBoolean("msf"));
        msfAutostart.setOnCheckedChangeListener((btn, b) -> core.putBoolean("msf", b));
        bindRowToSwitch(msfAutostartRow, msfAutostart);
        msfStopRow.setOnClickListener(v -> stopMetasploit());

        maxPar.setOnClickListener(v -> showMaxParDialog(maxParCount));

        changeInterfaces.setOnClickListener(v -> openSub(new SettingsChangeInterfaces(), "wifi"));
        changeCommands.setOnClickListener(v -> openSub(new CustomCommandsWifi(), "cmd"));
        debug.setOnClickListener(v -> openSub(new LoggerFragment(), "logs"));

        LinearLayout engineSection = view.findViewById(R.id.engine_section);
        LinearLayout vmSettingsRow = view.findViewById(R.id.vm_settings_row);
        boolean wifiGuest = com.zalexdev.stryker.engine.WifiEngine.armed(core);
        if (core.isRootless() || wifiGuest) {
            engineSection.setVisibility(View.VISIBLE);
            vmSettingsRow.setOnClickListener(v -> openSub(new VmSettingsFragment(), "vm"));
        } else {
            engineSection.setVisibility(View.GONE);
        }
        bindWifiEngineRow(view, wifiGuest);

        if (core.isRootless()) {
            unmountLayout.setVisibility(View.GONE);
            deleteLayout.setVisibility(View.GONE);
        } else {
            unmountLayout.setOnClickListener(v -> confirmUnmount());
            deleteLayout.setOnClickListener(v -> confirmDelete());
        }
    }

    private void bindWifiEngineRow(View view, boolean armed) {
        View card = view.findViewById(R.id.wifi_engine_card);
        View row = view.findViewById(R.id.wifi_engine_row);
        android.widget.TextView state = view.findViewById(R.id.wifi_engine_state);
        if (card == null || row == null || state == null) return;
        if (!armed) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        com.zalexdev.stryker.engine.EngineType type =
                com.zalexdev.stryker.engine.WifiEngine.configured(core);
        String name = getString(type == com.zalexdev.stryker.engine.EngineType.UML
                ? R.string.engine_uml_name : R.string.engine_vm_name);
        state.setText(getString(R.string.wifi_guest_settings_on, name));
        row.setOnClickListener(v -> confirmWifiEngineOff());
    }

    private void confirmWifiEngineOff() {
        if (getContext() == null) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.wifi_guest_settings_title)
                .setMessage(R.string.wifi_guest_turn_off_body)
                .setPositiveButton(R.string.wifi_guest_turn_off, (d, w) -> {
                    com.zalexdev.stryker.engine.WifiEngine.disarm(core);
                    com.zalexdev.stryker.engine.RootlessService.stop(requireContext());
                    View root = getView();
                    if (root != null) bindWifiEngineRow(root, false);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openSub(Fragment f, String tag) {
        Fragment host = getParentFragment();
        if (host instanceof SettingsNew) {
            ((SettingsNew) host).openChild(f, tag);
        } else {
            getParentFragmentManager().beginTransaction()
                    .addToBackStack(tag)
                    .replace(R.id.flContent, f)
                    .commit();
        }
    }

    private void stopMetasploit() {
        if (activity instanceof MainActivity) {
            MetasploitUtils msf = ((MainActivity) activity).getMetasploitUtils();
            if (msf != null) {
                msf.stop();
                core.toaster(activity, getString(R.string.settings_msf_stop_toast));
                return;
            }
        }
        core.toaster(activity, getString(R.string.settings_msf_stop_not_running));
    }

    private int currentMaxPar() {
        int v = core.getInt("max_par");
        return v < 1 ? DEFAULT_MAX_PAR : v;
    }

    private void bindRowToSwitch(View row, SwitchMaterial s) {
        row.setOnClickListener(v -> s.setChecked(!s.isChecked()));
    }

    private void showMaxParDialog(TextView display) {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.input_dialog);
        Objects.requireNonNull(dialog.getWindow()).setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        TextView title = dialog.findViewById(R.id.title);
        TextInputEditText value = dialog.findViewById(R.id.value);
        TextInputLayout valueLayout = dialog.findViewById(R.id.value_layout);
        MaterialButton ok = dialog.findViewById(R.id.ok);
        MaterialButton cancel = dialog.findViewById(R.id.cancel);

        title.setText(R.string.settings_max_par_dialog_title);
        valueLayout.setHint(getString(R.string.settings_max_par_dialog_hint));
        value.setText(String.valueOf(currentMaxPar()));
        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String entered = Objects.requireNonNull(valueLayout.getEditText()).getText().toString().trim();
            if (!entered.matches("[0-9]+")) {
                valueLayout.setError(getString(R.string.settings_max_par_dialog_error));
                return;
            }
            int parsed = Integer.parseInt(entered);
            if (parsed < 1) {
                valueLayout.setError(getString(R.string.settings_max_par_dialog_error));
                return;
            }
            core.putInt("max_par", parsed);
            display.setText(String.valueOf(parsed));
            dialog.dismiss();
        });
        dialog.show();
    }

    private void confirmUnmount() {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_unmount_confirm_title)
                .setMessage(R.string.settings_unmount_confirm_message)
                .setPositiveButton(R.string.settings_unmount_title, (d, w) -> unmount())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_delete_confirm_title)
                .setMessage(R.string.settings_delete_confirm_message)
                .setPositiveButton(R.string.settings_delete_title, (d, w) -> performDelete())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void unmount() {
        new Thread(() -> {
            boolean ok = core.unmountCore();
            if (ok) {
                core.toaster(activity, getString(R.string.settings_unmount_success));
                activity.runOnUiThread(() -> activity.finishAffinity());
            } else {
                core.toaster(activity, getString(R.string.settings_unmount_failed));
            }
        }).start();
    }

    private void performDelete() {
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_delete_progress_title)
                .setMessage(R.string.settings_delete_step_unmount)
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            if (!core.unmountCore()) {
                stopDelete(progress, R.string.settings_delete_unmount_failed);
                return;
            }
            ui(() -> progress.setMessage(getString(R.string.settings_delete_step_wipe)));

            if (!core.safeDeleteTree("/data/local/stryker")) {
                stopDelete(progress, R.string.settings_delete_wipe_failed);
                return;
            }
            ui(() -> progress.setMessage(getString(R.string.settings_delete_step_uninstall)));

            core.customCommand("pm uninstall com.zalexdev.stryker");
        }, "stryker-delete").start();
    }

    private void stopDelete(androidx.appcompat.app.AlertDialog progress, int reason) {
        ui(() -> {
            progress.dismiss();
            core.toaster(activity, getString(reason));
        });
    }

    private void ui(Runnable r) {
        if (activity != null && isAdded()) activity.runOnUiThread(r);
    }
}
