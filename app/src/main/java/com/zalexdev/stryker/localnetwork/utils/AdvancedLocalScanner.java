package com.zalexdev.stryker.localnetwork.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import com.zalexdev.stryker.custom.Device;
import com.zalexdev.stryker.custom.Port;
import com.zalexdev.stryker.localnetwork.nonroot.IpRange;
import com.zalexdev.stryker.localnetwork.nonroot.NonRootScanner;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.GuestFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AdvancedLocalScanner {
    @SuppressLint("StaticFieldLeak")
    public static Activity activity;
    @SuppressLint("StaticFieldLeak")
    public static Context context;
    public static Process process;
    @SuppressLint("StaticFieldLeak")
    public static Core core;
    public InputStream output;
    public InputStream error;
    public OutputStream input;
    public Logger logger;
    public String gateway;
    public Thread mainThread;
    public String iface;
    public NonRootScanner nonRoot;
    public volatile boolean detached;
    public ArrayList<Device> devicesOld = new ArrayList<>();

    public AdvancedLocalScanner(Activity activity, Context context, String iface) {
        AdvancedLocalScanner.activity = activity;
        AdvancedLocalScanner.context = context;
        this.iface = iface;
        core = new Core(context);
        if (!core.isRootless()) {
            process = core.generateSuProcess();
            output = process.getInputStream();
            error = process.getErrorStream();
            input = process.getOutputStream();
        }
        logger = new Logger();
        activity.runOnUiThread(this::onStarted);

        mainThread = new Thread(() -> {
            logger.writeLine("Starting local network scanner", 3);
            startScan();
        });
        mainThread.start();
    }

    public static String convertNetmaskToCIDR(InetAddress netmask) {
        byte[] netmaskBytes = netmask.getAddress();
        int cidr = 0;
        boolean zero = false;
        for (byte b : netmaskBytes) {
            int mask = 0x80;

            for (int i = 0; i < 8; i++) {
                int result = b & mask;
                if (result == 0) {
                    zero = true;
                } else if (zero) {
                    throw new IllegalArgumentException("Invalid netmask.");
                } else {
                    cidr++;
                }
                mask >>>= 1;
            }
        }
        return String.valueOf(cidr);
    }

    public void startScan() {
        if (core.isRootless()) {
            startScanRootless();
            return;
        }
        final String localCidr = localCidr(iface);
        String override = core.getString("local_scan_target");
        boolean manual = override != null && !override.trim().isEmpty();
        final String target = manual ? override.trim()
                : localCidr != null ? localCidr : "192.168.1.1/24";
        if (manual && HostSweep.outsideTarget(override, localCidr)) {
            logger.writeLine("Scan target " + override.trim() + " does not contain this device ("
                    + localCidr + "), so only hosts the router routes to can answer, and none of"
                    + " them will have a MAC address. Clear the manual target to scan the subnet"
                    + " you are on.", 3);
        }
        final HostSweep sweep = new HostSweep();
        String cmd = "nmap " + target + " -sn -PE -n -PP -T4 --stats-every 1s";
        new AdvancedProcess(activity, context, cmd, true) {
            @Override
            public void onFinished(ArrayList<String> outputList) {
                new Thread(() -> {
                    try {
                        activity.runOnUiThread(() -> onProgressUpdate(100));
                        logger.writeLine("Started local network scanner", 1);
                        sweep.nmap(outputList);
                        int answered = sweep.size();
                        sweep.arpScan(core.customChrootCommand(
                                "arp-scan -I " + GuestFiles.shellQuote(iface) + " -l"));
                        sweep.neighbours(core.customChrootCommand(neighbourCommand(iface), true));
                        sweep.self(localCidr);
                        ArrayList<Device> devices = toDevices(sweep);
                        logger.writeLine("nmap found " + answered + " host(s) up, " + devices.size()
                                + " after arp-scan", 1);
                        int blind = sweep.withoutMac();
                        if (blind > 0) {
                            logger.writeLine(blind + " host(s) answered without a MAC address. That"
                                    + " happens when they sit behind a router or the probe went out"
                                    + " through a VPN; they are listed with the MAC left blank.", 3);
                        }
                        activity.runOnUiThread(() -> onProgressUpdate(110));
                        core.saveLastNetworkScan(devices);
                        for (Device d : devices) {
                            logger.writeLine("Found device: " + d.getIp() + " " + d.getMac() + " " + d.getVendor(), 2);
                            activity.runOnUiThread(() -> onDeviceAdded(d));
                        }
                        if (core.getBoolean("autoScan")){
                            AtomicInteger finished = new AtomicInteger(0);
                            if (devices.size() < 4) {
                                for (Device newDevice : devices) {
                                    if (isNewDevice(newDevice)) {
                                        int index = devices.indexOf(newDevice);
                                        String bannerArg = core.isBannerScanEnabled() ? " --script=banner" : "";
                                        new Thread(() -> {
                                            ArrayList<String> output = core.customChrootCommand("nmap " + newDevice.getIp() + " -n -Pn -O -F --max-os-tries=3" + bannerArg);
                                            logger.writeLine("Finished scanning device: " + newDevice.getIp(), 1);
                                            Device temp = scanLocalDevice(output, newDevice.getIp());
                                            if (temp.getMac().contains("Scanning")) {
                                                temp.setMac(newDevice.getMac());
                                                temp.setVendor(newDevice.getVendor());
                                            }
                                            try {
                                                InetAddress address = InetAddress.getByName(newDevice.getIp());
                                                if (address.isReachable(1000)) {
                                                    String sub = address.getCanonicalHostName();

                                                    if (sub.contains(".") && !sub.equals(newDevice.getIp())) {
                                                        sub = sub.split("\\.")[0];
                                                    }
                                                    temp.setSubname(sub);
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            activity.runOnUiThread(() -> onDeviceChanged(temp, index));
                                            finished.getAndIncrement();
                                            onEvent(String.valueOf(index));
                                        }).start();
                                    } else {
                                        finished.getAndIncrement();
                                    }
                                }
                            } else {
                                int maxThreads = core.getInt("max_par", 3);
                                if (maxThreads < 1) maxThreads = 3;
                                logger.writeLine("Max threads: " + maxThreads, 1);
                                String bulkBannerArg = core.isBannerScanEnabled() ? " --script=banner" : "";
                                java.util.concurrent.ExecutorService pool =
                                        java.util.concurrent.Executors.newFixedThreadPool(maxThreads);
                                for (Device newDevice : devices) {
                                    if (isNewDevice(newDevice)) {
                                        int index = devices.indexOf(newDevice);
                                        pool.submit(() -> {
                                            ArrayList<String> output = core.customChrootCommand("nmap " + newDevice.getIp() + " -n -Pn -O -F --max-os-tries=3" + bulkBannerArg);
                                            logger.writeLine("Finished scanning device: " + newDevice.getIp(), 1);
                                            Device temp = scanLocalDevice(output, newDevice.getIp());
                                            if (temp.getMac().contains("Scanning")) {
                                                temp.setMac(newDevice.getMac());
                                                temp.setVendor(newDevice.getVendor());
                                            }
                                            try {
                                                InetAddress address = InetAddress.getByName(newDevice.getIp());
                                                if (address.isReachable(1000)) {
                                                    String sub = address.getCanonicalHostName();
                                                    if (sub.contains(".") && !sub.equals(newDevice.getIp())) {
                                                        sub = sub.split("\\.")[0];
                                                    }
                                                    temp.setSubname(sub);
                                                }
                                            } catch (IOException e) {
                                                e.printStackTrace();
                                            }
                                            temp.setShim(false);
                                            activity.runOnUiThread(() -> onDeviceChanged(temp, index));
                                            finished.getAndIncrement();
                                            onEvent(String.valueOf(index));
                                        });
                                    } else {
                                        finished.getAndIncrement();
                                    }
                                }
                                pool.shutdown();
                            }
                            new Thread(() -> {
                                while (finished.get() != devices.size()) {
                                    try {
                                        Thread.sleep(300);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                devicesOld = devices;
                                activity.runOnUiThread(AdvancedLocalScanner.this::onFinishedScan);
                            }).start();
                        }else{
                            new Thread(() -> {
                                for (Device newDevice : devices) {
                                    try {
                                        InetAddress address = InetAddress.getByName(newDevice.getIp());
                                        if (address.isReachable(1000)) {
                                            String sub = address.getCanonicalHostName();

                                            if (sub.contains(".") && !sub.equals(newDevice.getIp())) {
                                                sub = sub.split("\\.")[0];
                                            }
                                            newDevice.setSubname(sub);

                                        }
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                    newDevice.setShim(false);
                                    activity.runOnUiThread(() -> onDeviceChanged(newDevice, devices.indexOf(newDevice)));
                                }
                            }).start();

                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();

            }

            @Override
            public void onNewLine(String line) {
                sweep.nmapLine(line);
                Matcher per = Pattern.compile("[0-9]*\\.[0-9]+%").matcher(line);
                if (line.contains("Nmap done")) {
                    process.destroy();
                }
                if (per.find()) {
                    activity.runOnUiThread(() -> onProgressUpdate((int) Double.parseDouble(per.group().replace("%", ""))));
                }
            }

            @Override
            public void onEvent(String line) {

            }
        };
    }

    private void startScanRootless() {
        logger.writeLine("Starting non-root LAN discovery (ICMP + TCP + NetBIOS + mDNS + SSDP + SNMP)", 1);
        nonRoot = new NonRootScanner(context, core, new NonRootScanner.Callback() {
            @Override
            public void onStatus(String message) {
                if (detached) return;
                logger.writeLine(message, 1);
            }

            @Override
            public void onProgress(int percent) {
                if (detached) return;
                activity.runOnUiThread(() -> {
                    if (!detached) AdvancedLocalScanner.this.onProgressUpdate(percent);
                });
            }

            @Override
            public void onDeviceAdded(Device device, int index) {
                if (detached) return;
                activity.runOnUiThread(() -> {
                    if (!detached) AdvancedLocalScanner.this.onDeviceAdded(device);
                });
            }

            @Override
            public void onDeviceChanged(Device device, int index) {
                if (detached) return;
                activity.runOnUiThread(() -> {
                    if (!detached) AdvancedLocalScanner.this.onDeviceChanged(device, index);
                });
            }

            @Override
            public void onFinished(ArrayList<Device> found) {
                if (detached) return;
                devicesOld = found;
                activity.runOnUiThread(() -> {
                    if (!detached) AdvancedLocalScanner.this.onFinishedScan();
                });
            }
        });
        nonRoot.start();
    }

    public void stopScan() {
        detached = true;
        if (nonRoot != null) {
            nonRoot.cancel();
            nonRoot = null;
        }
        if (mainThread != null) mainThread.interrupt();
    }

    public String getGateway() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        return intToIP(dhcp.gateway);
    }

    public String getGateway(String wlan) {
        String override = core.getString("local_scan_target");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        String local = localCidr(wlan);
        return local != null ? local : "192.168.1.1/24";
    }

    private String localCidr(String wlan) {
        ArrayList<String> output = core.customChrootCommand(
                "ip -o -f inet addr show dev " + GuestFiles.shellQuote(wlan)
                        + " 2>/dev/null | awk '/scope global/ {print $4}'", true);
        for (String line : output) {
            String t = line == null ? "" : line.trim();
            int slash = t.indexOf('/');
            if (slash > 0 && IpRange.isIpv4(t.substring(0, slash))) return t;
        }
        return null;
    }

    private static String neighbourCommand(String wlan) {
        return "ip -4 neigh show 2>/dev/null; cat /proc/net/arp 2>/dev/null; "
                + "printf '" + HostSweep.SELF_MARK + " %s\\n' \"$(cat "
                + GuestFiles.shellQuote("/sys/class/net/" + wlan + "/address")
                + " 2>/dev/null)\"";
    }

    private ArrayList<Device> toDevices(HostSweep sweep) {
        ArrayList<Device> out = new ArrayList<>();
        for (HostSweep.Host h : sweep.hosts()) {
            Device d = new Device();
            d.setIp(h.ip);
            d.setMac(h.mac);
            String vendor = h.vendor;
            if (vendor.isEmpty() && h.hasMac()) vendor = core.getVendorByMacFromDB(h.mac);
            d.setVendor(vendor == null ? "" : vendor);
            if (!h.name.isEmpty()) d.setSubname(h.name);
            out.add(d);
        }
        return out;
    }

    private String intToIP(int ipAddress) {
        return String.format(Locale.ENGLISH, "%d.%d.%d.%d",
                ipAddress & 0xff,
                (ipAddress >> 8) & 0xff,
                (ipAddress >> 16) & 0xff,
                (ipAddress >> 24) & 0xff);
    }

    public Device scanLocalDevice(ArrayList<String> output, String ip) {
        Device device = new Device();
        ArrayList<Port> ports = new ArrayList<>();
        device.setIp(ip);
        device.setShim(false);
        for (int i = 0; i < output.size(); i++) {
            String temp = output.get(i).replaceAll("\\s+", " ").replace("*", "");

            String portNum;
            String service;
            String banner;
            if (temp.contains("/tcp")) {
                Port port = new Port();
                String r = temp.replace("/tcp", "").replace("open", "").replace("filtered", "").replaceAll("\\\\x([A-Z]|[0-9])([A-Z]|[0-9])", "");
                Matcher m = Pattern.compile("[0-9]+").matcher(r);
                if (m.find()) {
                    portNum = m.group();
                    service = r.replaceAll("\\s+", "").replace(portNum, "");
                    port.setPortNumber(portNum);
                    port.setPortName(service);
                    ports.add(port);
                }
            } else if (temp.contains("banner")) {
                StringBuilder sb = new StringBuilder();
                banner = temp.replace("banner:", "").replace("|", "").replace("_", "").replaceAll("\\\\x([A-Z]|[0-9])([A-Z]|[0-9])", "");
                sb.append(banner).append("\n");
                String banner2 = "";
                String banner3 = "";
                try {
                    if (output.get(i + 1).contains("|")) {
                        banner2 = output.get(i + 1).replace("|", "").replace("_", "").replaceAll("\\\\x([A-Z]|[0-9])([A-Z]|[0-9])", "");
                    }
                    if (output.get(i + 2).contains("|")) {
                        banner3 = output.get(i + 2).replace("|", "").replace("_", "").replaceAll("\\\\x([A-Z]|[0-9])([A-Z]|[0-9])", "");
                    }
                } catch (Exception ignored) {
                }
                sb.append(banner2).append("\n");
                sb.append(banner3).append("\n");
                ports.get(ports.size() - 1).setBanner(banner);
            } else if (temp.contains("MAC Address")) {
                Matcher mac = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(temp);
                if (mac.find()) {
                    device.setMac(Objects.requireNonNull(mac.group(0)).toUpperCase(Locale.ROOT));
                }
                String vendor = com.zalexdev.stryker.localnetwork.utils.MacLine.vendorOf(temp);
                device.setVendor(vendor);
            } else if (temp.contains("Running:")) {
                device.setOs(temp.replace("Running: ", "").replace("Microsoft", ""));
            } else if (temp.contains("No exact matches")) {
                device.setOs("Unknown");
            }

        }
        device.setPorts(ports);
        device.setNmapoutput(output);
        return device;
    }

    public abstract void onProgressUpdate(int progress);

    public abstract void onDeviceAdded(Device device);

    public abstract void onDeviceChanged(Device device, int pos);

    public abstract void onStarted();

    public abstract void onFinishedScan();

    public boolean isNewDevice(Device device) {
        for (Device d : devicesOld) {
            if (d.getIp().equals(device.getIp())) {
                return false;
            }
        }
        return true;
    }

    public void start() {
        detached = false;
        if (!core.isRootless()) {
            process = core.generateSuProcess();
            output = process.getInputStream();
            error = process.getErrorStream();
            input = process.getOutputStream();
        }
        activity.runOnUiThread(this::onStarted);
        mainThread = new Thread(() -> {
            logger.writeLine("Rescan", 3);
            startScan();
        });
        mainThread.start();
    }
}