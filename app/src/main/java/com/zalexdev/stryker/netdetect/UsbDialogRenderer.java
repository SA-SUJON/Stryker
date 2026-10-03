package com.zalexdev.stryker.netdetect;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.zalexdev.stryker.R;

public final class UsbDialogRenderer {

    public interface Actions {
        void onFix(UsbChain.Fix fix, UsbDeviceReport report);
    }

    private final Context context;
    private final View emptyState, detectedState, noteCard;
    private final TextView title, subtitle, noteTitle, noteBody, noteAction;
    private final ImageView noteIcon;
    private final LinearLayout chain, rows;
    private final Actions actions;

    public UsbDialogRenderer(@NonNull View root, @Nullable Actions actions) {
        this.context = root.getContext();
        this.actions = actions;
        emptyState    = root.findViewById(R.id.usb_empty_state);
        detectedState = root.findViewById(R.id.usb_detected_state);
        title         = root.findViewById(R.id.usb_title);
        subtitle      = root.findViewById(R.id.usb_subtitle);
        chain         = root.findViewById(R.id.usb_chain);
        noteCard      = root.findViewById(R.id.usb_note_card);
        noteIcon      = root.findViewById(R.id.usb_note_icon);
        noteTitle     = root.findViewById(R.id.usb_note_title);
        noteBody      = root.findViewById(R.id.usb_note_body);
        noteAction    = root.findViewById(R.id.usb_note_action);
        rows          = root.findViewById(R.id.usb_rows);
    }

    public void renderEmpty() {
        emptyState.setVisibility(View.VISIBLE);
        detectedState.setVisibility(View.GONE);
    }

    public void render(@Nullable UsbDeviceReport r, @NonNull UsbChain c) {
        if (r == null && c.noteTitle == null) {
            renderEmpty();
            return;
        }
        emptyState.setVisibility(View.GONE);
        detectedState.setVisibility(View.VISIBLE);

        title.setText(r == null ? context.getString(R.string.usb_empty_title) : r.displayName());
        subtitle.setText(r == null ? "" : subtitleFor(r));
        subtitle.setVisibility(r == null ? View.GONE : View.VISIBLE);
        bindChain(c);
        bindNote(r, c);
        if (r == null) rows.removeAllViews(); else bindRows(r, c);
    }

    private String subtitleFor(UsbDeviceReport r) {
        String chip = r.chipset == null ? null : r.chipset.chipset;
        if (chip == null || chip.isEmpty() || chip.startsWith("Unknown")) return r.vidPid;
        return r.vidPid + "  ·  " + chip;
    }

    private void bindChain(UsbChain c) {
        chain.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < c.stages.size(); i++) {
            if (i > 0) chain.addView(connector(c.stages.get(i).state));

            UsbChain.Stage stage = c.stages.get(i);
            View v = inflater.inflate(R.layout.item_usb_stage, chain, false);
            FrameLayout chip = v.findViewById(R.id.stage_chip);
            ImageView icon = v.findViewById(R.id.stage_icon);
            android.widget.ProgressBar spinner = v.findViewById(R.id.stage_spinner);
            TextView label = v.findViewById(R.id.stage_label);

            int tint = colorFor(stage.state);
            boolean solid = stage.state == UsbChain.State.DONE
                    && i == c.stages.size() - 1 && c.ok;
            boolean running = stage.state == UsbChain.State.RUNNING;

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(solid ? tint : withAlpha(tint, 0.12f));
            if (stage.state == UsbChain.State.FAIL) {
                bg.setStroke(dp(1.5f), tint);
            }
            chip.setBackground(bg);
            spinner.setVisibility(running ? View.VISIBLE : View.GONE);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(tint));
            icon.setVisibility(running ? View.INVISIBLE : View.VISIBLE);
            icon.setImageResource(stage.state == UsbChain.State.FAIL
                    ? R.drawable.error : stage.icon);
            icon.setImageTintList(ColorStateList.valueOf(solid ? Color.WHITE : tint));
            label.setText(stage.label);
            label.setTextColor(stage.state == UsbChain.State.PENDING
                    ? colorFor(UsbChain.State.PENDING)
                    : ContextCompat.getColor(context, R.color.grey));
            if (running) label.setTextColor(tint);
            chain.addView(v);
        }
    }

    private View connector(UsbChain.State into) {
        View v = new View(context);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(4), 1f);
        lp.gravity = Gravity.TOP;
        lp.topMargin = dp(20);
        lp.setMarginStart(dp(2));
        lp.setMarginEnd(dp(2));
        v.setLayoutParams(lp);

        GradientDrawable line = new GradientDrawable();
        line.setShape(GradientDrawable.LINE);
        if (into == UsbChain.State.PENDING) {
            line.setStroke(dp(2), colorFor(UsbChain.State.PENDING), dp(2), dp(3));
        } else {
            line.setStroke(dp(2), colorFor(into));
        }
        v.setBackground(line);
        return v;
    }

    private void bindNote(UsbDeviceReport r, UsbChain c) {
        if (c.noteTitle == null) {
            noteCard.setVisibility(View.GONE);
            return;
        }
        noteCard.setVisibility(View.VISIBLE);
        int accent = c.busy
                ? ContextCompat.getColor(context, R.color.stryker_accent)
                : c.ok ? ContextCompat.getColor(context, R.color.green)
                       : ContextCompat.getColor(context, R.color.red);
        ((MaterialCardView) noteCard).setCardBackgroundColor(withAlpha(accent, 0.07f));
        noteIcon.setImageTintList(ColorStateList.valueOf(accent));
        noteIcon.setImageResource(c.busy ? R.drawable.info_outlined : R.drawable.error);
        noteTitle.setText(c.noteTitle);
        noteTitle.setTextColor(accent);
        noteBody.setText(c.noteBody);
        noteBody.setVisibility(c.noteBody == null || c.noteBody.isEmpty()
                ? View.GONE : View.VISIBLE);
        noteBody.setTextColor(ContextCompat.getColor(context, R.color.grey));

        if (c.actionLabel == null || actions == null) {
            noteAction.setVisibility(View.GONE);
            return;
        }
        noteAction.setVisibility(View.VISIBLE);
        noteAction.setText(c.actionLabel);
        noteAction.setTextColor(accent);
        noteAction.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        noteAction.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, R.drawable.arrow_right, 0);
        noteAction.setOnClickListener(v -> actions.onFix(c.fix, r));
    }

    private void bindRows(UsbDeviceReport r, UsbChain c) {
        rows.removeAllViews();
        if (!c.iface.isEmpty()) {
            addRow(context.getString(R.string.usb_row_interface), interfaceValue(r, c.iface));
            String mac = macOf(c.iface);
            if (mac != null) addRow(context.getString(R.string.usb_row_mac), mac);
        }
        String driver = r.interfaces.drivers.isEmpty() ? null : r.interfaces.drivers.get(0);
        if (driver != null) addRow(context.getString(R.string.usb_row_driver), driver);
        if (r.busPath != null) addRow(context.getString(R.string.usb_row_bus), r.busPath);
        addRow(context.getString(R.string.usb_row_matched), matchLabel(r));
    }

    private String interfaceValue(UsbDeviceReport r, String iface) {
        String mode = SysfsReader.readText("/sys/class/net/" + iface + "/type");
        if ("803".equals(mode)) return iface + "  ·  " + context.getString(R.string.usb_monitor);
        return iface;
    }

    private static String macOf(String iface) {
        String mac = SysfsReader.readText("/sys/class/net/" + iface + "/address");
        return mac == null || mac.isEmpty() ? null : mac;
    }

    private String matchLabel(UsbDeviceReport r) {
        switch (r.chipsetSource) {
            case CURATED: return context.getString(R.string.usb_match_curated);
            case LEGACY:  return context.getString(R.string.usb_match_legacy);
            default:      return context.getString(R.string.usb_match_unknown);
        }
    }

    private void addRow(String label, String value) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_usb_row, rows, false);
        ((TextView) v.findViewById(R.id.row_label)).setText(label);
        ((TextView) v.findViewById(R.id.row_value)).setText(value);
        if (rows.getChildCount() == 0) v.findViewById(R.id.row_divider).setVisibility(View.GONE);
        rows.addView(v);
    }

    private int colorFor(UsbChain.State s) {
        switch (s) {
            case DONE:    return ContextCompat.getColor(context, R.color.green);
            case WARN:    return 0xFFEF6C00;
            case FAIL:    return ContextCompat.getColor(context, R.color.red);
            case RUNNING: return ContextCompat.getColor(context, R.color.stryker_accent);
            default:      return 0xFF9E9E9E;
        }
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (((int) (alpha * 255)) << 24);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}
