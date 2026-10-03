package com.zalexdev.stryker.engine;

import android.hardware.usb.UsbDevice;

import java.util.List;

public interface GuestUsb {

    interface AttachCallback {
        void onResult(boolean attached, UsbDevice device);
    }

    List<UsbDevice> devices();

    boolean isWifiCandidate(UsbDevice device);

    UsbDevice findByVidPid(String vidPid);

    boolean hasPermission(UsbDevice device);

    boolean requestPermission(UsbDevice device, long waitMs);

    boolean isAttached(UsbDevice device);

    boolean hasAttached();

    int attachedCount();

    boolean attach(UsbDevice device);

    void attachAsync(UsbDevice device, AttachCallback done);

    void detach(UsbDevice device);

    void detachAll();

    List<UsbDevice> pickWifiDevices();

    int attachAllWifiDongles(long waitMs);

    String attachmentDetail(UsbDevice device);
}
