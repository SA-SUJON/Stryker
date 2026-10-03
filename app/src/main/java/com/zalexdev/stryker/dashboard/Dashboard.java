package com.zalexdev.stryker.dashboard;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.getkeepsafe.taptargetview.TapTarget;
import com.getkeepsafe.taptargetview.TapTargetView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.arsenal.ArsenalFragment;
import com.zalexdev.stryker.engine.EngineStatus;
import com.zalexdev.stryker.engine.Engines;
import com.zalexdev.stryker.engine.GuestEngine;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.engine.RootlessService;
import com.zalexdev.stryker.engine.VmBootStage;
import com.zalexdev.stryker.engine.VmStatsCollector;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.SparklineView;
import com.zalexdev.stryker.utils.VmRingView;

import net.cachapa.expandablelayout.ExpandableLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class Dashboard extends Fragment {

    private static final String PREF_TOOL = "dash_tool_";

    private TextView savedCount, recentScanCount;
    private LinearLayout toolsGrid;

    private Activity activity;
    private Context context;
    private Core core;
    private final MainActivity.Receiver receiver = new MainActivity.Receiver();

    private GuestEngine vmEngine;
    private View vmCard;
    private TextView vmBadge, vmSpecs, vmUsb, vmStatusChevron, vmLogsChevron, vmLogText;
    private android.widget.ScrollView vmLogScroll;
    private String lastVmLog;
    private TextView vmCpuValue, vmRamValue;
    private SparklineView vmCpuGraph, vmRamGraph;
    private VmRingView vmRing;
    private ExpandableLayout vmStatusExpand, vmLogsExpand;
    private final Handler vmHandler = new Handler(Looper.getMainLooper());
    private boolean vmRefreshing = false;
    private Runnable vmTick;
    private VmStatsCollector vmCollector;
    private TextView vmStatsChevron, vmStatsSummary, vmUsbChevron, vmUsbDetails;
    private MaterialButton vmUsbManage;
    private com.zalexdev.stryker.utils.AuroraView vmAurora;
    private final java.util.Map<TextView, Integer> vmCardTextColors = new java.util.HashMap<>();
    private final java.util.Map<View, android.graphics.drawable.Drawable> vmCardBackgrounds =
            new java.util.HashMap<>();
    private GuestEngine.State lastAuroraState;

    private int vmCardForeground;
    private final java.util.HashMap<android.widget.ImageView, android.content.res.ColorStateList>
            vmCardIconTints = new java.util.HashMap<>();
    private boolean auroraLight;
    private ExpandableLayout vmStatsExpand, vmUsbExpand;
    private ExecutorService vmStatsExec;
    private final AtomicBoolean vmSampling = new AtomicBoolean(false);

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @SuppressLint({"SetTextI18n", "SdCardPath"})
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        this.savedCount = view.findViewById(R.id.saved_count);
        this.recentScanCount = view.findViewById(R.id.recent_scan_count);
        this.toolsGrid = view.findViewById(R.id.tools_grid);

        checkPermission();
        core.putInt("dashboard_open", core.getInt("dashboard_open") + 1);

        renderCounts();
        buildTools();

        view.findViewById(R.id.dash_hero).setOnClickListener(
                v -> receiver.changeFragment(R.id.wifi_item));
        view.findViewById(R.id.terminal).setOnClickListener(v -> openTerminal());
        view.findViewById(R.id.dash_customize).setOnClickListener(v -> showCustomize());
        view.findViewById(R.id.dash_report).setOnClickListener(v -> reportBug());
        view.findViewById(R.id.dash_github).setOnClickListener(
                v -> openUrl(com.zalexdev.stryker.utils.Links.GITHUB));
        view.findViewById(R.id.dash_site).setOnClickListener(
                v -> openUrl(com.zalexdev.stryker.utils.Links.SITE));
        view.findViewById(R.id.dash_blog).setOnClickListener(
                v -> openUrl(com.zalexdev.stryker.utils.Links.BLOG));
        view.findViewById(R.id.dash_chat).setOnClickListener(
                v -> openUrl(com.zalexdev.stryker.utils.Links.CHAT));

        View.OnClickListener savedOpen = v -> receiver.changeFragment(
                R.id.dasboard_item, new WiFiHistoryFragment(), "wifi_history");
        View.OnClickListener scanOpen = v -> receiver.changeFragment(R.id.lan_item);
        view.findViewById(R.id.wifi_history).setOnClickListener(savedOpen);
        view.findViewById(R.id.recent_scan).setOnClickListener(scanOpen);
        view.findViewById(R.id.news).setOnClickListener(
                v -> receiver.changeFragment(R.id.dasboard_item, new NewsFragment(), "news"));

        MaterialCardView magiskNotification = view.findViewById(R.id.magisk);
        if (!core.isRootless()) {
            new Thread(() -> {
                if (core.checkMagiskNotification() && !core.getBoolean("magisk_notif")) {
                    activity.runOnUiThread(() -> magiskNotification.setVisibility(View.VISIBLE));
                }
            }).start();
        }

        MaterialButton magiskYes = view.findViewById(R.id.magisk_yes);
        MaterialButton magiskNo = view.findViewById(R.id.magisk_no);
        magiskYes.setOnClickListener(v -> {
            magiskNotification.setVisibility(View.GONE);
            core.disableMagiskNotification();
            if (!core.checkMagiskNotification()) {
                core.toaster(getString(R.string.magisk_off_success));
            } else {
                core.toaster(getString(R.string.magisk_notif_bad));
            }
            core.putBoolean("magisk_notif", true);
        });
        magiskNo.setOnClickListener(v -> {
            magiskNotification.setVisibility(View.GONE);
            core.putBoolean("magisk_notif", true);
        });

        if (!core.getBoolean("exploits_v30")) {
            new Thread(() -> {
                if (!core.checkFile(core.getShareRoot() + "/exploits/checker.py")) {
                    Snackbar s = Snackbar.make(activity.findViewById(android.R.id.content), "Updating please wait...", 60000);
                    activity.runOnUiThread(s::show);
                    com.zalexdev.stryker.engine.GuestCore.ensure(core);
                    String guestShare = core.guestShare();
                    core.customChrootCommand("mkdir -p " + guestShare + "/exploits; "
                            + "cp -f /exploits/* " + guestShare + "/exploits/ 2>/dev/null", true);
                    activity.runOnUiThread(s::dismiss);
                    core.putListString("installed_modules", new ArrayList<>());
                }
                core.putBoolean("exploits_v30", true);
            }).start();
        }

        setupVmCard(view);

        showFirstScanTip(view.findViewById(R.id.dash_hero));
    }


    private void setupVmCard(View view) {
        vmCard = view.findViewById(R.id.vm_card);
        if (vmCard == null) return;
        vmCard.setVisibility(View.VISIBLE);

        TextView cardTitle = view.findViewById(R.id.vm_card_title);
        vmBadge = view.findViewById(R.id.vm_status_badge);
        vmSpecs = view.findViewById(R.id.vm_specs_value);
        vmUsb = view.findViewById(R.id.vm_usb_value);
        vmStatusChevron = view.findViewById(R.id.vm_status_chevron);
        vmStatusExpand = view.findViewById(R.id.vm_status_expand);
        vmRing = view.findViewById(R.id.vm_ring);
        if (vmRing != null) {
            vmRing.setRingEnabled(false);
        }

        vmAurora = view.findViewById(R.id.vm_aurora);
        if (vmAurora != null) {
            vmAurora.setGlow(0.0032f);
            vmAurora.setPillarWidth(10f);
            vmAurora.setPillarHeight(0.6f);
            vmAurora.setNoise(0.35f);
            int night = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            auroraLight = night != android.content.res.Configuration.UI_MODE_NIGHT_YES;
            applyAuroraState(null);
        }

        if (!core.isRootless()) {
            if (cardTitle != null) cardTitle.setText("Chroot engine");
            if (vmUsb != null) vmUsb.setVisibility(View.GONE);
            if (vmStatusChevron != null) vmStatusChevron.setVisibility(View.GONE);
            hide(view, R.id.vm_status_expand, R.id.vm_controls_row, R.id.vm_console_divider,
                    R.id.vm_stats_header, R.id.vm_stats_expand, R.id.vm_divider_usb,
                    R.id.vm_usb_header, R.id.vm_usb_expand, R.id.vm_divider_logs,
                    R.id.vm_logs_header, R.id.vm_logs_expand);
            refreshChrootStatus();
            return;
        }

        view.findViewById(R.id.vm_status_header).setOnClickListener(v -> {
            vmStatusExpand.toggle();
            vmStatusChevron.setText(vmStatusExpand.isExpanded() ? "▾" : "▸");
        });

        vmEngine = Engines.active(core);
        if (cardTitle != null) cardTitle.setText(vmEngine.displayName());
        vmCollector = VmStatsCollector.get(context);
        vmCollector.start();

        vmStatsChevron = view.findViewById(R.id.vm_stats_chevron);
        vmStatsExpand = view.findViewById(R.id.vm_stats_expand);
        vmStatsSummary = view.findViewById(R.id.vm_stats_summary);
        vmCpuValue = view.findViewById(R.id.vm_cpu_stat_value);
        vmRamValue = view.findViewById(R.id.vm_ram_stat_value);
        vmCpuGraph = view.findViewById(R.id.vm_cpu_stat_graph);
        vmRamGraph = view.findViewById(R.id.vm_ram_stat_graph);

        vmUsbChevron = view.findViewById(R.id.vm_usb_chevron);
        vmUsbExpand = view.findViewById(R.id.vm_usb_expand);
        vmUsbDetails = view.findViewById(R.id.vm_usb_details);
        vmUsbManage = view.findViewById(R.id.vm_usb_manage);
        if (vmUsbManage != null) vmUsbManage.setOnClickListener(v -> showUsbPicker());

        vmLogsChevron = view.findViewById(R.id.vm_logs_chevron);
        vmLogText = view.findViewById(R.id.vm_log_text);
        vmLogScroll = view.findViewById(R.id.vm_log_scroll);
        vmLogsExpand = view.findViewById(R.id.vm_logs_expand);
        if (vmLogScroll != null) {
            vmLogScroll.setOnTouchListener((v, event) -> {
                ViewParent parent = v.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return false;
            });
        }

        try {
            if (vmCpuGraph != null) {
                vmCpuGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.accent_vm));
            }
            if (vmRamGraph != null) {
                vmRamGraph.setAccent(androidx.core.content.ContextCompat.getColor(context, R.color.green));
            }
        } catch (Exception ignored) {}
        vmStatsExec = Executors.newSingleThreadExecutor();

        section(view, R.id.vm_stats_header, vmStatsExpand, vmStatsChevron, this::sampleVmStats);
        section(view, R.id.vm_usb_header, vmUsbExpand, vmUsbChevron, this::refreshUsb);
        section(view, R.id.vm_logs_header, vmLogsExpand, vmLogsChevron, this::refreshVmLog);

        view.findViewById(R.id.vm_btn_start).setOnClickListener(v -> {
            RootlessService.start(context);
            if (vmCollector != null) vmCollector.start();
            refreshVmStatus();
        });
        view.findViewById(R.id.vm_btn_stop).setOnClickListener(v -> {
            RootlessService.stop(context);
            if (vmRing != null) {
                vmRing.setProgress(-1f);
                vmRing.setState(VmRingView.STATE_STOPPED);
            }
            if (vmBadge != null) vmBadge.setText("Stopping…");
            new Thread(() -> {
                try { vmEngine.stop(); } catch (Throwable ignored) {}
            }, "vm-stop").start();
        });
        view.findViewById(R.id.vm_btn_refresh_log).setOnClickListener(v -> refreshVmLog());
        view.findViewById(R.id.vm_btn_copy_log).setOnClickListener(v -> copyVmLog());

        vmTick = () -> {
            refreshVmStatus();
            sampleVmStats();
            if (vmLogsExpand != null && vmLogsExpand.isExpanded()) refreshVmLog();
            if (vmRefreshing) vmHandler.postDelayed(vmTick, 2500);
        };
        refreshVmStatus();
        refreshUsb();
    }

    private void section(View root, int headerId, ExpandableLayout expand, TextView chevron,
                         Runnable onExpand) {
        View header = root.findViewById(headerId);
        if (header == null || expand == null) return;
        header.setOnClickListener(v -> {
            expand.toggle();
            boolean open = expand.isExpanded();
            if (chevron != null) chevron.setText(open ? "▾" : "▸");
            if (open && onExpand != null) onExpand.run();
        });
    }

    private void sampleVmStats() {
        final VmStatsCollector collector = vmCollector;
        final GuestEngine engine = vmEngine;
        ExecutorService exec = vmStatsExec;
        if (collector == null || engine == null || exec == null || exec.isShutdown()) return;
        if (!vmSampling.compareAndSet(false, true)) return;
        final boolean full = vmStatsExpand != null && vmStatsExpand.isExpanded();
        try {
            exec.execute(() -> {
                GuestEngine.State state = GuestEngine.State.STOPPED;
                int stage = -1;
                VmStatsCollector.Series series = null;
                try {
                    state = engine.statusBlocking();
                    if (state == GuestEngine.State.BOOTING) {
                        stage = VmBootStage.detect(engine.consoleTail(120));
                    }
                    series = collector.snapshot(full ? 160 : 8);
                } catch (Throwable ignored) {
                }
                final GuestEngine.State finalState = state;
                final int finalStage = stage;
                final VmStatsCollector.Series finalSeries = series;
                Activity host = activity;
                if (host == null) {
                    vmSampling.set(false);
                    return;
                }
                host.runOnUiThread(() -> {
                    vmSampling.set(false);
                    renderRing(finalState, finalStage);
                    renderSeries(finalSeries, full);
                });
            });
        } catch (RejectedExecutionException e) {
            vmSampling.set(false);
        }
    }

    private void renderRing(GuestEngine.State state, int stage) {
        if (vmRing == null) return;
        if (state == GuestEngine.State.BOOTING) {
            vmRing.setState(VmRingView.STATE_BOOTING);
            vmRing.setProgress(stage >= 0 ? VmBootStage.fraction(stage) : -1f);
            if (vmBadge != null && stage >= 0 && context != null) {
                vmBadge.setText(context.getString(VmBootStage.labelRes(stage)));
            }
            return;
        }
        vmRing.setProgress(-1f);
        vmRing.setState(state == GuestEngine.State.READY
                ? VmRingView.STATE_READY : VmRingView.STATE_STOPPED);
    }

    private void renderSeries(VmStatsCollector.Series s, boolean full) {
        if (s == null || context == null) return;
        String cpuText = s.lastCpu >= 0f
                ? String.format(Locale.ENGLISH, "%.0f%%", s.lastCpu)
                : unavailable(s);
        String ramText = s.lastRamMb >= 0 ? readableMb(s.lastRamMb) : unavailable(s);
        if (vmCpuValue != null) vmCpuValue.setText(cpuText);
        if (vmRamValue != null) vmRamValue.setText(ramText);
        if (vmStatsSummary != null) {
            vmStatsSummary.setText(s.lastCpu >= 0f || s.lastRamMb >= 0
                    ? cpuText + " · " + ramText : cpuText);
        }
        if (!full) return;
        if (vmCpuGraph != null) {
            float[] norm = new float[s.cpu.length];
            for (int i = 0; i < norm.length; i++) {
                norm[i] = s.cpu[i] < 0f ? -1f : s.cpu[i] / 100f;
            }
            vmCpuGraph.setValues(norm);
        }
        if (vmRamGraph != null) vmRamGraph.setValues(s.ramFraction);
    }

    private String unavailable(VmStatsCollector.Series s) {
        if (context == null) return "—";
        if (!s.running) return context.getString(R.string.vm_stats_offline);
        if (s.blocked) return context.getString(R.string.vm_stats_blocked);
        return context.getString(R.string.vm_stats_waiting);
    }

    private void setCardLit(boolean lit) {
        if (vmAurora == null) return;
        vmAurora.setVisibility(lit ? View.VISIBLE : View.GONE);
        View card = getView() == null ? null : getView().findViewById(R.id.vm_card);
        if (!(card instanceof ViewGroup)) return;
        if (lit) {
            tintForDarkGround((ViewGroup) card);
        } else {
            restoreCardColors();
        }
    }

    private void restoreCardColors() {
        for (java.util.Map.Entry<TextView, Integer> e : vmCardTextColors.entrySet()) {
            e.getKey().setTextColor(e.getValue());
        }
        vmCardTextColors.clear();
        for (java.util.Map.Entry<View, android.graphics.drawable.Drawable> e
                : vmCardBackgrounds.entrySet()) {
            e.getKey().setBackground(e.getValue());
        }
        vmCardBackgrounds.clear();
        for (java.util.Map.Entry<android.widget.ImageView, android.content.res.ColorStateList> e
                : vmCardIconTints.entrySet()) {
            e.getKey().setImageTintList(e.getValue());
        }
        vmCardIconTints.clear();
        vmCardForeground = 0;
    }

    private void tintForDarkGround(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof com.zalexdev.stryker.utils.AuroraView) continue;
            if (child instanceof ViewGroup) {
                tintForDarkGround((ViewGroup) child);
                continue;
            }
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                Integer original = vmCardTextColors.get(tv);
                int c = original != null ? original : tv.getCurrentTextColor();
                if (luminance(c) < 0.72f) {
                    if (!vmCardTextColors.containsKey(tv)) vmCardTextColors.put(tv, c);
                    tv.setTextColor(luminance(c) < 0.22f ? 0xFFFFFFFF
                            : (vmCardForeground != 0 ? vmCardForeground : 0xFFDDE4EC));
                }
                continue;
            }
            if (child instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) child;
                if (!vmCardIconTints.containsKey(iv)) {
                    vmCardIconTints.put(iv, iv.getImageTintList());
                }
                if (vmCardForeground != 0) {
                    iv.setImageTintList(android.content.res.ColorStateList.valueOf(vmCardForeground));
                }
                continue;
            }
            android.view.ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null && lp.height > 0 && lp.height <= dp(3)) {
                if (!vmCardBackgrounds.containsKey(child)) {
                    vmCardBackgrounds.put(child, child.getBackground());
                }
                child.setBackgroundColor(0x22FFFFFF);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private void applyAuroraState(GuestEngine.State state) {
        if (vmAurora == null) return;
        if (lastAuroraState == state && state != null) return;
        lastAuroraState = state;
        if (state == null || state == GuestEngine.State.STOPPED) {
            setCardLit(false);
            if (vmRing != null) {
                try {
                    vmRing.setTileColor(androidx.core.content.ContextCompat.getColor(
                            context, R.color.light_lite_contrast));
                    vmRing.setGlyphTint(androidx.core.content.ContextCompat.getColor(
                            context, R.color.grey));
                } catch (Exception ignored) {
                }
            }
            if (vmBadge != null && state != null) {
                try {
                    vmBadge.setTextColor(androidx.core.content.ContextCompat.getColor(
                            context, R.color.grey));
                } catch (Exception ignored) {
                }
            }
            return;
        }

        int light, ground;
        float intensity, speed;
        int fg;
        if (state == GuestEngine.State.READY) {
            light = auroraLight ? 0xFF34D399 : 0xFF0B8F63;
            ground = auroraLight ? 0xFF047857 : 0xFF04120C;
            intensity = auroraLight ? 0.26f : 0.45f;
            speed = 0.30f;
            fg = 0xFFB8F2C9;
        } else {
            light = auroraLight ? 0xFFFBBF24 : 0xFFC47A1E;
            ground = auroraLight ? 0xFF8A5A0E : 0xFF150E02;
            intensity = auroraLight ? 0.24f : 0.42f;
            speed = 0.65f;
            fg = 0xFFFFDFA0;
        }
        vmCardForeground = fg;
        vmAurora.setColors(light, light, ground);
        vmAurora.setIntensity(intensity);
        vmAurora.setSpeed(speed);
        setCardLit(true);
        if (vmRing != null) {
            vmRing.setTileColor(0x40000000);
            vmRing.setGlyphTint(0xFFFFFFFF);
        }
        if (vmBadge != null && state != null) vmBadge.setTextColor(fg);
    }

    private void refreshUsb() {
        if (vmUsb == null || context == null) return;
        final GuestEngine engine = vmEngine;
        new Thread(() -> {
            final StringBuilder details = new StringBuilder();
            int attached = 0;
            int total = 0;
            try {
                com.zalexdev.stryker.engine.GuestUsb usb = engine == null ? null : engine.usb();
                java.util.List<android.hardware.usb.UsbDevice> devs = usb != null
                        ? usb.devices() : java.util.Collections.emptyList();
                for (android.hardware.usb.UsbDevice d : devs) {
                    total++;
                    boolean live = usb.isAttached(d);
                    if (live) attached++;
                    details.append(live ? "● " : "○ ").append(describeUsb(d));
                    details.append("  ").append(context.getString(live
                            ? R.string.vm_usb_attached_to_vm : R.string.vm_usb_host_only));
                    String detail = live ? usb.attachmentDetail(d) : "";
                    if (!detail.isEmpty()) details.append("\n    ").append(detail);
                    details.append('\n');
                }
            } catch (Throwable ignored) {
            }
            final int finalAttached = attached;
            final int finalTotal = total;
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (vmUsb == null || context == null) return;
                String summary;
                if (finalTotal == 0) {
                    summary = context.getString(R.string.vm_usb_none);
                } else if (finalAttached > 0) {
                    summary = finalAttached + "/" + finalTotal + " · "
                            + context.getString(R.string.vm_usb_attached_to_vm);
                } else {
                    summary = finalTotal + " · " + context.getString(R.string.vm_usb_host_only);
                }
                vmUsb.setText(summary);
                if (vmUsbManage != null) {
                    boolean can = engine != null && finalTotal > 0
                            && engine.supports(GuestEngine.Capability.USB_PASSTHROUGH);
                    vmUsbManage.setVisibility(can ? View.VISIBLE : View.GONE);
                }
                if (vmUsbDetails != null) {
                    vmUsbDetails.setText(details.length() == 0
                            ? context.getString(R.string.vm_usb_none) : details.toString().trim());
                }
            });
        }, "vm-usb-scan").start();
    }

    private void showUsbPicker() {
        final GuestEngine engine = vmEngine;
        final Activity host = activity;
        if (engine == null || host == null || context == null) return;
        final com.zalexdev.stryker.engine.GuestUsb usb = engine.usb();
        if (usb == null) return;

        final java.util.List<android.hardware.usb.UsbDevice> devs = usb.devices();
        if (devs.isEmpty()) return;

        CharSequence[] labels = new CharSequence[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            android.hardware.usb.UsbDevice d = devs.get(i);
            boolean live = usb.isAttached(d);
            String detail = live ? usb.attachmentDetail(d) : "";
            labels[i] = (live ? "● " : "○ ") + describeUsb(d)
                    + (detail.isEmpty() ? "" : "\n    " + detail);
        }

        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.vm_usb_pick_title)
                .setItems(labels, (dialog, which) -> {
                    android.hardware.usb.UsbDevice d = devs.get(which);
                    if (usb.isAttached(d)) {
                        toast(getString(R.string.vm_usb_detaching, describeUsb(d)));
                        new Thread(() -> {
                            usb.detach(d);
                            postRefreshUsb();
                        }, "usb-detach").start();
                        return;
                    }
                    if (!engine.isRunning()) {
                        toast(getString(R.string.vm_usb_needs_guest));
                        return;
                    }
                    toast(getString(R.string.vm_usb_attaching, describeUsb(d)));
                    usb.attachAsync(d, (ok, dev) -> {
                        Activity a = activity;
                        if (a == null) return;
                        a.runOnUiThread(() -> toast(getString(ok
                                ? R.string.vm_usb_attach_ok
                                : R.string.vm_usb_attach_failed, describeUsb(d))));
                        postRefreshUsb();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toast(String text) {
        Activity host = activity;
        if (host == null) return;
        Snackbar.make(host.findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG).show();
    }

    private void postRefreshUsb() {
        Activity host = activity;
        if (host == null) return;
        host.runOnUiThread(this::refreshUsb);
    }

    private static String describeUsb(android.hardware.usb.UsbDevice d) {
        String name = null;
        try {
            name = d.getProductName();
        } catch (Throwable ignored) {
        }
        String ids = String.format(Locale.ENGLISH, "%04x:%04x", d.getVendorId(), d.getProductId());
        return name == null || name.trim().isEmpty() ? ids : name.trim() + " (" + ids + ")";
    }

    private static String readableMb(int mb) {
        if (mb >= 1024) return String.format(Locale.ENGLISH, "%.1f GB", mb / 1024f);
        return mb + " MB";
    }


    @SuppressLint("SetTextI18n")
    private void refreshVmStatus() {
        if (vmEngine == null || vmBadge == null) return;
        GuestEngine.State st = vmEngine.status();
        String badge;
        int color, ring;
        switch (st) {
            case READY: {
                String prompt = vmEngine.guestPrompt();
                badge = prompt.isEmpty() ? "Ready" : "Ready · " + prompt;
                color = R.color.green;
                ring = VmRingView.STATE_READY;
                break;
            }
            case BOOTING:
                badge = "Booting…"; color = R.color.stryker_accent;
                ring = VmRingView.STATE_BOOTING; break;
            case STOPPED:
            default:
                badge = "Stopped"; color = R.color.grey;
                ring = VmRingView.STATE_STOPPED; break;
        }
        if (vmRing != null && st != GuestEngine.State.BOOTING) {
            vmRing.setProgress(-1f);
            vmRing.setState(ring);
        }
        applyAuroraState(st);
        if (st != GuestEngine.State.BOOTING) vmBadge.setText(badge);
        if (vmCardForeground != 0) {
            vmBadge.setTextColor(vmCardForeground);
        } else {
            try { vmBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, color)); } catch (Exception ignored) {}
        }

        int cpus = com.zalexdev.stryker.engine.VmSpecs.effectiveCpus(context, core);
        int ram = com.zalexdev.stryker.engine.VmSpecs.effectiveRamMb(context, core);
        String disk = com.zalexdev.stryker.engine.VmSpecs.humanBytes(
                com.zalexdev.stryker.engine.VmSpecs.diskAllocatedBytes(context));
        boolean uml = vmEngine != null && vmEngine.type() == com.zalexdev.stryker.engine.EngineType.UML;
        String how = uml
                ? "native"
                : (com.zalexdev.stryker.engine.VmSpecs.kvmAvailable() ? "KVM" : "TCG");
        vmSpecs.setText(cpus + (uml ? " CPU · " : " vCPU · ") + ram + " MB · " + disk
                + " disk · " + how);
    }

    private void refreshVmLog() {
        if (vmEngine == null || vmLogText == null) return;
        new Thread(() -> {
            List<String> lines = vmEngine.consoleTail(400);
            final StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            final String text = sb.length() == 0 ? "(no console output yet)" : sb.toString();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> applyVmLog(text));
        }, "vm-log-tail").start();
    }

    private void copyVmLog() {
        final GuestEngine engine = vmEngine;
        if (engine == null || context == null) return;
        new Thread(() -> {
            List<String> lines = engine.consoleTail(5000);
            final StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            final int count = lines.size();
            Activity host = activity;
            if (host == null) return;
            host.runOnUiThread(() -> {
                if (context == null) return;
                if (count == 0) {
                    toast(getString(R.string.vm_log_copy_empty));
                    return;
                }
                android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cb == null) return;
                cb.setPrimaryClip(android.content.ClipData.newPlainText(
                        engine.displayName() + " boot console", sb.toString()));
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    toast(getString(R.string.vm_log_copied, count));
                }
            });
        }, "vm-log-copy").start();
    }

    private void applyVmLog(String text) {
        if (vmLogText == null || text == null) return;
        if (text.equals(lastVmLog)) return;
        boolean stick = isLogAtBottom();
        lastVmLog = text;
        vmLogText.setText(text);
        if (stick) scrollLogToBottom();
    }

    private boolean isLogAtBottom() {
        if (vmLogScroll == null || vmLogText == null) return true;
        int content = vmLogText.getHeight();
        if (content <= 0) return true;
        int viewport = vmLogScroll.getHeight();
        int slack = Math.round(24f * getResources().getDisplayMetrics().density);
        return content - (viewport + vmLogScroll.getScrollY()) <= slack;
    }

    private void scrollLogToBottom() {
        final android.widget.ScrollView scroll = vmLogScroll;
        final TextView text = vmLogText;
        if (scroll == null || text == null) return;
        scroll.post(() -> {
            if (vmLogScroll == null || vmLogText == null) return;
            int target = Math.max(0, vmLogText.getHeight() - vmLogScroll.getHeight());
            vmLogScroll.scrollTo(0, target);
        });
    }

    @SuppressLint("SetTextI18n")
    private void refreshChrootStatus() {
        if (vmBadge == null) return;
        new Thread(() -> {
            boolean mounted = core.isMounted();
            EngineStatus es = EngineStatus.current(core, mounted);
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (vmBadge == null) return;
                if (vmRing != null) {
                    vmRing.setState(mounted ? VmRingView.STATE_READY : VmRingView.STATE_STOPPED);
                }
                applyAuroraState(mounted
                        ? GuestEngine.State.READY : GuestEngine.State.STOPPED);
                vmBadge.setText(es.label);
                if (vmCardForeground != 0) {
                    vmBadge.setTextColor(vmCardForeground);
                } else {
                    try {
                        vmBadge.setTextColor(androidx.core.content.ContextCompat.getColor(context, es.colorRes));
                    } catch (Exception ignored) {}
                }
                if (vmSpecs != null) {
                    vmSpecs.setText(mounted
                            ? "Debian toolset at " + Core.CHROOT_ROOT
                            : "Not mounted — reopen the app to remount");
                }
            });
        }).start();
    }

    private void hide(View root, int... ids) {
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (vmEngine != null && vmTick != null) {
            vmRefreshing = true;
            vmHandler.post(vmTick);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        vmRefreshing = false;
        vmHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroyView() {
        vmRefreshing = false;
        vmHandler.removeCallbacksAndMessages(null);
        if (vmStatsExec != null) {
            vmStatsExec.shutdownNow();
            vmStatsExec = null;
        }
        vmSampling.set(false);
        vmCollector = null;
        vmStatsExpand = null;
        vmUsbExpand = null;
        vmStatsSummary = null;
        vmUsbDetails = null;
        vmUsbManage = null;
        vmAurora = null;
        vmLogScroll = null;
        lastVmLog = null;
        vmRing = null;
        vmCpuGraph = null;
        vmRamGraph = null;
        vmCpuValue = null;
        vmRamValue = null;
        super.onDestroyView();
    }

    private void renderCounts() {
        savedCount.setText(String.valueOf(core.getSavedNetworks().size()));
        recentScanCount.setText(String.valueOf(core.getLastNetworkScan().size()));
    }


    private static final class Tool {
        final String key;
        final int label;
        final int icon;
        final int tile;
        final int tint;
        final boolean on;
        final Runnable open;

        Tool(String key, int label, int icon, int tile, int tint, boolean on, Runnable open) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.tile = tile;
            this.tint = tint;
            this.on = on;
            this.open = open;
        }
    }

    private List<Tool> tools() {
        int accent = androidx.core.content.ContextCompat.getColor(context, R.color.stryker_accent);
        List<Tool> t = new ArrayList<>();
        t.add(new Tool("wifi", R.string.wifi_networks, R.drawable.wifi,
                R.drawable.dashboard_tile_bg_blue, accent, true,
                () -> receiver.changeFragment(R.id.wifi_item)));
        t.add(new Tool("lan", R.string.local_network, R.drawable.lan,
                R.drawable.dashboard_tile_bg_purple, 0xFFAB47BC, true,
                () -> receiver.changeFragment(R.id.lan_item)));
        t.add(new Tool("handshakes", R.string.dashboard_tile_handshakes, R.drawable.file,
                R.drawable.dashboard_tile_bg_teal, 0xFF00897B, true,
                () -> receiver.changeFragment(R.id.hs_item)));
        t.add(new Tool("exploits", R.string.dashboard_tile_exploits, R.drawable.motion_blur,
                R.drawable.dashboard_tile_bg_orange, 0xFFEF6C00, true,
                () -> receiver.changeFragment(R.id.arsenal_item,
                        ArsenalFragment.forTab(ArsenalFragment.TAB_HUB))));
        t.add(new Tool("searchsploit", R.string.dashboard_tile_searchsploit, R.drawable.database,
                R.drawable.dashboard_tile_bg_pink, 0xFFD81B60, true,
                () -> receiver.changeFragment(R.id.arsenal_item,
                        ArsenalFragment.forTab(ArsenalFragment.TAB_DB))));
        t.add(new Tool("nuclei", R.string.website, R.drawable.webscan,
                R.drawable.dashboard_tile_bg_indigo, 0xFF3949AB, true,
                () -> receiver.changeFragment(R.id.nuclei_item)));
        t.add(new Tool("geomac", R.string.dashboard_tile_geomac, R.drawable.map,
                R.drawable.dashboard_tile_bg_cyan, 0xFF00838F, true,
                () -> receiver.changeFragment(R.id.geomac_item)));
        t.add(new Tool("metasploit", R.string.dashboard_tile_metasploit, R.drawable.shield,
                R.drawable.dashboard_tile_bg_red, 0xFFC62828, true,
                () -> receiver.changeFragment(R.id.metasploit_item)));

        t.add(new Tool("nmap", R.string.dashboard_tile_nmap, R.drawable.scanner,
                R.drawable.dashboard_tile_bg_purple, 0xFFAB47BC, false,
                () -> receiver.changeFragment(R.id.nmap_item)));
        t.add(new Tool("hid", R.string.dashboard_tile_hid, R.drawable.keyboard,
                R.drawable.dashboard_tile_bg_red, 0xFFC62828, false,
                () -> receiver.changeFragment(R.id.hid_item)));
        t.add(new Tool("wordlists", R.string.dashboard_tile_wordlists, R.drawable.summarize,
                R.drawable.dashboard_tile_bg_indigo, 0xFF5E35B1, false,
                () -> receiver.changeFragment(R.id.wordlists_item)));
        t.add(new Tool("macchanger", R.string.dashboard_tile_macchanger, R.drawable.password,
                R.drawable.dashboard_tile_bg_blue, accent, false,
                () -> receiver.changeFragment(R.id.macchanger_item)));
        t.add(new Tool("wpair", R.string.dashboard_tile_wpair, R.drawable.wpair,
                R.drawable.dashboard_tile_bg_indigo, 0xFF3949AB, false,
                () -> receiver.changeFragment(R.id.wpair_item)));
        t.add(new Tool("vnc", R.string.dashboard_tile_vnc, R.drawable.vnc,
                R.drawable.dashboard_tile_bg_purple, 0xFF5E35B1, false,
                () -> receiver.changeFragment(R.id.vnc_item)));
        t.add(new Tool("usb", R.string.dashboard_tile_usb, R.drawable.usb,
                R.drawable.dashboard_tile_bg_blue, accent, false,
                () -> receiver.changeFragment(R.id.usb_arsenal_item)));
        t.add(new Tool("manager", R.string.dashboard_tile_manager, R.drawable.tune,
                R.drawable.dashboard_tile_bg_teal, 0xFF00897B, false,
                () -> receiver.changeFragment(R.id.manager_item)));
        t.add(new Tool("logs", R.string.dashboard_tile_logs, R.drawable.bug_report,
                R.drawable.dashboard_tile_bg_orange, 0xFFEF6C00, false,
                () -> receiver.changeFragment(R.id.logs_item)));
        return t;
    }

    private boolean toolShown(Tool t) {
        String key = PREF_TOOL + t.key;
        return core.contains(key) ? core.getBoolean(key) : t.on;
    }

    private void buildTools() {
        if (toolsGrid == null) return;
        toolsGrid.removeAllViews();

        List<Tool> shown = new ArrayList<>();
        for (Tool t : tools()) if (toolShown(t)) shown.add(t);

        final int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                toolsGrid.addView(row);
            }
            row.addView(tileView(shown.get(i)));
        }
        if (row != null) {
            for (int i = shown.size() % perRow; i > 0 && i < perRow; i++) {
                View pad = new View(context);
                pad.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                row.addView(pad);
            }
        }
    }

    private View tileView(Tool tool) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(0, dp(10), 0, dp(10));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cell.setClickable(true);
        cell.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(
                androidx.appcompat.R.attr.selectableItemBackgroundBorderless, ripple, true);
        cell.setBackgroundResource(ripple.resourceId);
        cell.setOnClickListener(v -> tool.open.run());

        android.widget.FrameLayout badge = new android.widget.FrameLayout(context);
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(58), dp(58)));
        badge.setBackgroundResource(tool.tile);

        android.widget.ImageView icon = new android.widget.ImageView(context);
        android.widget.FrameLayout.LayoutParams ilp =
                new android.widget.FrameLayout.LayoutParams(dp(28), dp(28));
        ilp.gravity = android.view.Gravity.CENTER;
        icon.setLayoutParams(ilp);
        icon.setImageResource(tool.icon);
        icon.setColorFilter(tool.tint);
        badge.addView(icon);
        cell.addView(badge);

        TextView label = new TextView(context);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        label.setLayoutParams(llp);
        label.setText(tool.label);
        label.setGravity(android.view.Gravity.CENTER);
        label.setTextSize(11.5f);
        label.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary));
        cell.addView(label);
        return cell;
    }

    private int resolveThemeColor(int attr) {
        android.util.TypedValue out = new android.util.TypedValue();
        context.getTheme().resolveAttribute(attr, out, true);
        if (out.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(context, out.resourceId);
        }
        return out.data;
    }

    private void showCustomize() {
        if (context == null) return;
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        View body = LayoutInflater.from(context).inflate(R.layout.dashboard_customize_sheet, null);
        sheet.setContentView(body);

        LinearLayout list = body.findViewById(R.id.customize_tools);
        final List<Tool> all = tools();
        final List<com.google.android.material.switchmaterial.SwitchMaterial> switches =
                new ArrayList<>();
        for (Tool tool : all) {
            com.google.android.material.switchmaterial.SwitchMaterial sw =
                    new com.google.android.material.switchmaterial.SwitchMaterial(context);
            sw.setText(getString(tool.label).replace('\n', ' '));
            sw.setTextSize(15f);
            sw.setChecked(toolShown(tool));
            sw.setPadding(0, dp(12), 0, dp(12));
            list.addView(sw);
            switches.add(sw);
        }

        View.OnClickListener setAll = v -> {
            boolean on = v.getId() == R.id.customize_all;
            for (com.google.android.material.switchmaterial.SwitchMaterial sw : switches) {
                sw.setChecked(on);
            }
        };
        body.findViewById(R.id.customize_all).setOnClickListener(setAll);
        body.findViewById(R.id.customize_none).setOnClickListener(setAll);

        body.findViewById(R.id.customize_done).setOnClickListener(v -> {
            for (int i = 0; i < all.size(); i++) {
                core.putBoolean(PREF_TOOL + all.get(i).key, switches.get(i).isChecked());
            }
            sheet.dismiss();
            buildTools();
        });
        sheet.show();
    }

    private void reportBug() {
        if (context == null) return;
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dashboard_report_title)
                .setPositiveButton(R.string.dashboard_report_send, (d, w) -> sendDiagnostics())
                .setNegativeButton(R.string.dashboard_report_tracker,
                        (d, w) -> openUrl(com.zalexdev.stryker.utils.Links.ISSUES))
                .show();
    }

    private void sendDiagnostics() {
        core.toaster(getString(R.string.dashboard_report_working));
        new Thread(() -> {
            final java.io.File bundle =
                    com.zalexdev.stryker.engine.Diagnostics.collect(context.getApplicationContext());
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (bundle == null) {
                    core.toaster(getString(R.string.dashboard_report_failed));
                    return;
                }
                try {
                    startActivity(com.zalexdev.stryker.engine.Diagnostics.shareIntent(
                            context.getApplicationContext(), bundle));
                } catch (Throwable t) {
                    core.toaster(getString(R.string.dashboard_report_failed));
                }
            });
        }, "diagnostics").start();
    }

    private void openUrl(String url) {
        if (context == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            core.toaster(url);
        }
    }

    private void openTerminal() {
        if (!core.isRootless()) {
            launchTerminal();
            return;
        }
        if (core.guest().status() == GuestEngine.State.READY) {
            launchTerminal();
            return;
        }
        new Thread(() -> {
            boolean ready = core.guest().statusBlocking() == GuestEngine.State.READY;
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ready) launchTerminal();
                else core.toaster(getString(R.string.vm_required_terminal));
            });
        }, "terminal-vm-check").start();
    }

    private void launchTerminal() {
        Intent terminal = new Intent(context, com.stryker.terminal.ui.term.NeoTermActivity.class);
        terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        terminal.putExtra(com.stryker.terminal.ui.term.NeoTermActivity.EXTRA_NEW_SESSION, true);
        context.startActivity(terminal);
    }

    private void showFirstScanTip(View target) {
        if (core.getInt("dashboard_open") == 12 && !core.getBoolean("firstscan")) {
            TapTargetView.showFor(activity,
                    TapTarget.forView(target, "Tip: Networks with ⭐",
                                    "Networks with ⭐ are likely vulnerable to Pixie Dust")
                            .outerCircleColor(R.color.stryker_accent)
                            .outerCircleAlpha(0.96f)
                            .targetCircleColor(android.R.color.white)
                            .titleTextSize(20)
                            .titleTextColor(android.R.color.white)
                            .descriptionTextSize(16)
                            .descriptionTextColor(android.R.color.white)
                            .textColor(android.R.color.white)
                            .dimColor(android.R.color.black)
                            .drawShadow(true)
                            .cancelable(true)
                            .tintTarget(true)
                            .transparentTarget(true)
                            .targetRadius(60));
            core.putBoolean("firstscan", true);
        }
    }

    private void checkPermission() {
        if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{WRITE_EXTERNAL_STORAGE},
                    123
            );
        }
    }
}
