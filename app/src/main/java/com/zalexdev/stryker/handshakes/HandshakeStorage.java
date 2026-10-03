package com.zalexdev.stryker.handshakes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.stryker.terminal.bridge.StrykerLog;

public class HandshakeStorage extends Fragment {

    private static final Pattern MAC_PATTERN = Pattern.compile("((\\w{2}:){5}\\w{2})");

    private Core core;
    private Context context;
    private Activity activity;
    private RecyclerView recyclerView;
    private MaterialCardView emptyCard;
    private MaterialCardView listCard;
    private TextView statusTitle;
    private TextView metaTotal;
    private TextView metaCracked;
    private TextView metaSize;
    private SwipeRefreshLayout refresh;
    private HandshakesAdapter adapter;
    private ActivityResultLauncher<String[]> importLauncher;

    private static final String[] CAPTURE_SUFFIXES = {
            ".cap", ".pcap", ".pcapng", ".hccapx", ".hc22000", ".22000", ".ivs", ".csv"
    };

    public HandshakeStorage() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.handshakes_fragment, container, false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::importCaptures);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        context = getContext();
        activity = getActivity();
        core = new Core(context);
        new File(captureDir()).mkdirs();

        recyclerView = view.findViewById(R.id.hs_list);
        emptyCard = view.findViewById(R.id.hs_empty_card);
        listCard = view.findViewById(R.id.hs_list_card);
        statusTitle = view.findViewById(R.id.hs_status_title);
        metaTotal = view.findViewById(R.id.hs_meta_total);
        metaCracked = view.findViewById(R.id.hs_meta_cracked);
        metaSize = view.findViewById(R.id.hs_meta_size);
        refresh = view.findViewById(R.id.hs_refresh);
        MaterialButton refreshBtn = view.findViewById(R.id.hs_refresh_btn);
        MaterialButton importBtn = view.findViewById(R.id.hs_import_btn);
        MaterialButton shareAllBtn = view.findViewById(R.id.hs_share_all_btn);
        TextView pathLabel = view.findViewById(R.id.hs_storage_path);

        if (pathLabel != null) pathLabel.setText(captureDir());

        importBtn.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));
        shareAllBtn.setOnClickListener(v -> shareAll());

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setItemViewCacheSize(255);

        refresh.setOnRefreshListener(this::reload);
        refreshBtn.setOnClickListener(v -> reload());

        reload();
    }

    private String captureDir() {
        return core.getShareRoot() + "/captured";
    }

    private void importCaptures(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        new Thread(() -> {
            int copied = 0, skipped = 0;
            File dest = new File(captureDir());
            dest.mkdirs();
            for (Uri uri : uris) {
                String name = displayName(uri);
                if (!looksLikeCapture(name)) { skipped++; continue; }
                if (copyInto(uri, new File(dest, uniqueName(dest, name)))) copied++;
                else skipped++;
            }
            final int ok = copied, bad = skipped;
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (ok == 0) toast(getString(R.string.hs_import_none));
                else if (bad == 0) toast(getString(R.string.hs_imported, ok));
                else toast(getString(R.string.hs_import_skipped, ok, bad));
                reload();
            });
        }, "hs-import").start();
    }

    private static boolean looksLikeCapture(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String suffix : CAPTURE_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    private static String uniqueName(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            String tried = stem + "_" + i + ext;
            if (!new File(dir, tried).exists()) return tried;
        }
        return stem + "_" + System.currentTimeMillis() + ext;
    }

    private boolean copyInto(Uri uri, File dest) {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            return true;
        } catch (IOException | SecurityException e) {
            dest.delete();
            return false;
        }
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.trim().isEmpty()) return n.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        if (last == null) return "capture.cap";
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private void shareAll() {
        ArrayList<Uri> uris = new ArrayList<>();
        File dir = new File(captureDir());
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.length() == 0) continue;
                if (!looksLikeCapture(f.getName())) continue;
                try {
                    uris.add(FileProvider.getUriForFile(
                            context, context.getPackageName() + ".provider", f));
                } catch (Exception e) {
                    StrykerLog.w("HandshakeStorage", "cannot share " + f.getName(), e);
                }
            }
        }
        if (uris.isEmpty()) {
            toast(getString(R.string.hs_share_none));
            return;
        }
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        send.setType("application/octet-stream");
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, getString(R.string.hs_action_share_all));
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(chooser);
    }

    private void toast(String text) {
        if (context == null) return;
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void reload() {
        ArrayList<String> files = core.getListFiles(captureDir());
        if (refresh != null) refresh.setRefreshing(false);

        if (files == null || files.isEmpty()) {
            listCard.setVisibility(View.GONE);
            emptyCard.setVisibility(View.VISIBLE);
            statusTitle.setText(R.string.hs_status_empty);
            metaTotal.setText("0");
            metaCracked.setText("0");
            metaSize.setText("0 KB");
            return;
        }

        listCard.setVisibility(View.VISIBLE);
        emptyCard.setVisibility(View.GONE);

        adapter = new HandshakesAdapter(context, activity, files);
        adapter.setOnChangeListener(this::updateStats);
        recyclerView.setAdapter(adapter);

        updateStats();
    }

    private void updateStats() {
        if (adapter == null) return;
        int total = adapter.hslist.size();
        int cracked = 0;
        long bytes = 0;
        for (String path : adapter.hslist) {
            String mac = path;
            Matcher m = MAC_PATTERN.matcher(path);
            if (m.find()) mac = m.group(0);
            String stored = core.getString(mac);
            if (stored != null && !stored.isEmpty()) cracked++;
            File f = path.startsWith("/") ? new File(path) : new File(captureDir(), path);
            if (f.exists()) bytes += f.length();
        }
        statusTitle.setText(getString(R.string.hs_status_count, total));
        metaTotal.setText(String.valueOf(total));
        metaCracked.setText(String.valueOf(cracked));
        metaSize.setText(humanSize(bytes));
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
