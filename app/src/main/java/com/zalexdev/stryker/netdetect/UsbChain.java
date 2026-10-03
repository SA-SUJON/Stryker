package com.zalexdev.stryker.netdetect;

import com.zalexdev.stryker.R;

import java.util.ArrayList;
import java.util.List;

public final class UsbChain {

    public enum State {
        DONE,
        WARN,
        RUNNING,
        FAIL,
        PENDING
    }

    public enum Fix {
        NONE,
        SETUP_GUEST,
        SEARCH_DRIVER,
        RETRY
    }

    public static final class Stage {
        public final String label;
        public final int icon;
        public final State state;

        Stage(String label, int icon, State state) {
            this.label = label;
            this.icon = icon;
            this.state = state;
        }
    }

    public final List<Stage> stages;
    public final boolean busy;
    public final boolean ok;
    public final String noteTitle;
    public final String noteBody;
    public final String actionLabel;
    public final Fix fix;
    public final String iface;

    UsbChain(List<Stage> stages, boolean busy, boolean ok, String noteTitle, String noteBody,
             String actionLabel, Fix fix, String iface) {
        this.stages = stages;
        this.busy = busy;
        this.ok = ok;
        this.noteTitle = noteTitle;
        this.noteBody = noteBody;
        this.actionLabel = actionLabel;
        this.fix = fix;
        this.iface = iface;
    }

    static final class Builder {
        private final List<String> labels = new ArrayList<>();
        private final List<Integer> icons = new ArrayList<>();
        private final List<State> states = new ArrayList<>();

        Builder add(String label, int icon) {
            labels.add(label);
            icons.add(icon);
            states.add(State.PENDING);
            return this;
        }

        void label(int index, String label) {
            labels.set(index, label);
        }

        void set(int index, State state) {
            states.set(index, state);
        }

        void running(int index) {
            for (int i = 0; i < index; i++) {
                if (states.get(i) == State.PENDING || states.get(i) == State.RUNNING) {
                    states.set(i, State.DONE);
                }
            }
            states.set(index, State.RUNNING);
            for (int i = index + 1; i < states.size(); i++) states.set(i, State.PENDING);
        }

        UsbChain snapshot(boolean busy, boolean ok, String title, String body,
                          String action, Fix fix, String iface) {
            List<Stage> out = new ArrayList<>(labels.size());
            for (int i = 0; i < labels.size(); i++) {
                out.add(new Stage(labels.get(i), icons.get(i), states.get(i)));
            }
            return new UsbChain(out, busy, ok, title, body, action, fix, iface);
        }
    }

    public static String searchUrl(UsbDeviceReport r) {
        String chip = r != null && r.chipset != null && r.chipset.chipset != null
                ? r.chipset.chipset : "";
        String q = "install driver for " + (chip.isEmpty() ? "usb wifi adapter" : chip)
                + " wifi usb dongle debian";
        return "https://letmegooglethat.com/?q=" + android.net.Uri.encode(q);
    }

    static int portIcon()    { return R.drawable.usb; }
    static int androidIcon() { return R.drawable.ic_phone; }
    static int vmIcon()      { return R.drawable.ic_vm; }
    static int wlanIcon()    { return R.drawable.wifi; }
}
