package com.zalexdev.stryker.engine;

import com.zalexdev.stryker.R;
import com.zalexdev.stryker.utils.Core;

public final class EngineStatus {

    public final String label;
    public final int colorRes;
    public final String badge;
    public final boolean isVm;
    public final boolean ready;

    private EngineStatus(String label, int colorRes, String badge, boolean isVm, boolean ready) {
        this.label = label;
        this.colorRes = colorRes;
        this.badge = badge;
        this.isVm = isVm;
        this.ready = ready;
    }

    public static EngineStatus current(Core core, boolean chrootMounted) {
        return current(core, chrootMounted, false);
    }

    public static EngineStatus current(Core core, boolean chrootMounted, boolean probeGuest) {
        if (core.isRootless()) {
            GuestEngine engine = core.guest();
            GuestEngine.State st;
            try {
                st = probeGuest ? engine.statusBlocking() : engine.status();
            } catch (Throwable t) {
                st = GuestEngine.State.STOPPED;
            }
            boolean uml = engine.type() == EngineType.UML;
            String what = uml ? "Guest" : "VM";
            String tag = uml ? "UML" : "VM";
            switch (st) {
                case READY:   return new EngineStatus(what + " ready",    R.color.accent_vm,      tag, true, true);
                case BOOTING: return new EngineStatus(what + " booting…", R.color.status_booting, tag, true, false);
                case STOPPED:
                default:      return new EngineStatus(what + " stopped",  R.color.status_offline, tag, true, false);
            }
        }
        return new EngineStatus(
                chrootMounted ? "Chroot mounted" : "Chroot detached",
                chrootMounted ? R.color.accent_chroot : R.color.status_offline,
                "CHROOT", false, chrootMounted);
    }
}
