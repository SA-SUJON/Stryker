package com.zalexdev.stryker.dashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.Locale;

public class StoredAdapter extends RecyclerView.Adapter<StoredAdapter.ViewHolder> {

    private final Activity activity;
    private final Context context;
    private final Core core;
    private final ArrayList<ArrayList<String>> stored = new ArrayList<>();

    public StoredAdapter(Context context, Activity activity, ArrayList<ArrayList<String>> stored) {
        this.activity = activity;
        this.context = context;
        core = new Core(context);
        if (stored == null) return;
        for (ArrayList<String> network : stored) {
            if (network != null && network.size() >= 4) {
                this.stored.add(network);
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.storedwifi_item, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint({"UseCompatLoadingForDrawables", "SetTextI18n"})
    @Override
    public void onBindViewHolder(@NonNull ViewHolder adapter, int position) {
        ArrayList<String> network = stored.get(position);
        adapter.name.setText(field(network, 2) + " (" + field(network, 3).toUpperCase(Locale.ROOT) + ")");
        adapter.additional.setText(getAdditionalInfo(network));
        adapter.copy_password.setOnClickListener(view -> {
            core.copyToClipBoard(field(network, 0));
            core.toaster("Password copied to clipboard!");
        });
        adapter.copy_password.setOnLongClickListener(view -> {
            core.toaster("Copy password to clipboard.");
            return false;
        });
        adapter.delete.setOnClickListener(view -> removeNetwork(network));
        adapter.cardView.setOnLongClickListener(view -> {
            removeNetwork(network);
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return stored.size();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    private static String field(ArrayList<String> network, int index) {
        String value = index < network.size() ? network.get(index) : null;
        return value == null ? "" : value;
    }

    private String getAdditionalInfo(ArrayList<String> network) {
        StringBuilder result = new StringBuilder();
        String password = field(network, 0);
        String wps_pin = field(network, 1);
        if (!password.isEmpty()) {
            result.append("Password: ").append(password);
        }
        if (!wps_pin.isEmpty()) {
            result.append(!password.isEmpty() ? "\n" : "").append("WPS Pin: ").append(wps_pin);
        }
        return result.toString();
    }

    private void removeNetwork(ArrayList<String> network) {
        MaterialAlertDialogBuilder adb = new MaterialAlertDialogBuilder(context)
                .setTitle("Confirmation")
                .setMessage("Are you sure to delete this saved network?")
                .setPositiveButton(context.getResources().getString(R.string.yes), (di, i) -> {
                    int index = stored.indexOf(network);
                    if (index < 0) return;
                    core.removeSavedNetwork(field(network, 3));
                    stored.remove(index);
                    notifyItemRemoved(index);
                })
                .setNegativeButton(android.R.string.cancel, (di, i) -> {});
        adb.show();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        public TextView name;
        public TextView additional;
        public ImageView delete;
        public ImageView copy_password;
        public MaterialCardView cardView;

        public ViewHolder(View v) {
            super(v);
            name = v.findViewById(R.id.name);
            additional = v.findViewById(R.id.additional);
            delete = v.findViewById(R.id.delete);
            copy_password = v.findViewById(R.id.copy);
            cardView = v.findViewById(R.id.item);
        }
    }
}
