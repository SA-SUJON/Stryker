package com.zalexdev.stryker.localnetwork.utils;

import com.zalexdev.stryker.localnetwork.nonroot.IpRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HostSweep {

    public static final String SELF_MARK = "__SELF__";

    private static final String REPORT = "Nmap scan report for ";
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9.])((?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
                    + "(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3})(?![0-9.])");

    public static final class Host {
        public final String ip;
        public String mac = "";
        public String vendor = "";
        public String name = "";
        public String macSource = "";
        public boolean self;

        Host(String ip) {
            this.ip = ip;
        }

        public boolean hasMac() {
            return !mac.isEmpty();
        }
    }

    private final Map<String, Host> hosts = new LinkedHashMap<>();
    private Host current;
    private String selfIp = "";
    private String selfMac = "";

    public synchronized void nmapLine(String raw) {
        if (raw == null) return;
        String line = raw.trim();
        if (line.isEmpty()) return;
        if (line.startsWith(REPORT)) {
            current = null;
            String target = line.substring(REPORT.length()).trim();
            String name = "";
            String ip;
            int open = target.lastIndexOf('(');
            int close = target.lastIndexOf(')');
            if (open > 0 && close > open) {
                ip = target.substring(open + 1, close).trim();
                name = target.substring(0, open).trim();
            } else {
                ip = target;
            }
            if (!IpRange.isIpv4(ip)) return;
            current = host(ip);
            if (!name.isEmpty() && current.name.isEmpty()) current.name = name;
            return;
        }
        if (line.startsWith("MAC Address:") && current != null) {
            String mac = MacLine.macOf(line);
            if (!mac.isEmpty()) {
                current.mac = mac;
                current.macSource = "nmap";
            }
            String vendor = MacLine.vendorOf(line);
            if (!vendor.isEmpty()) current.vendor = vendor;
            return;
        }
        if (line.startsWith("Nmap done")) current = null;
    }

    public synchronized void nmap(List<String> lines) {
        current = null;
        if (lines == null) return;
        for (String l : lines) nmapLine(l);
        current = null;
    }

    public synchronized void arpScan(List<String> lines) {
        if (lines == null) return;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split("\\s+", 3);
            if (cols.length < 2 || !IpRange.isIpv4(cols[0])) continue;
            String mac = MacLine.macOf(cols[1]);
            if (mac.isEmpty()) continue;
            Host h = host(cols[0]);
            if (!h.hasMac()) {
                h.mac = mac;
                h.macSource = "arp-scan";
            }
            if (h.vendor.isEmpty() && cols.length > 2) {
                String vendor = cols[2].replaceAll("\\(DUP: \\d+\\)", "").trim();
                if (!vendor.isEmpty() && !vendor.equalsIgnoreCase("(Unknown)")) h.vendor = vendor;
            }
        }
    }

    public synchronized void neighbours(List<String> lines) {
        if (lines == null) return;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.startsWith(SELF_MARK)) {
                String mac = MacLine.macOf(line);
                if (!mac.isEmpty()) selfMac = mac;
                continue;
            }
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("failed") || low.contains("incomplete")) continue;
            Matcher ip = IPV4.matcher(line);
            if (!ip.find() || ip.start() != 0) continue;
            String mac = MacLine.macOf(line);
            if (mac.isEmpty() || "00:00:00:00:00:00".equals(mac) || "FF:FF:FF:FF:FF:FF".equals(mac)) {
                continue;
            }
            Host h = hosts.get(ip.group(1));
            if (h != null && !h.hasMac()) {
                h.mac = mac;
                h.macSource = "neighbour table";
            }
        }
        applySelf();
    }

    public synchronized void self(String ip) {
        if (ip == null) return;
        int slash = ip.indexOf('/');
        String clean = (slash >= 0 ? ip.substring(0, slash) : ip).trim();
        if (IpRange.isIpv4(clean)) selfIp = clean;
        applySelf();
    }

    private void applySelf() {
        if (selfIp.isEmpty()) return;
        Host h = hosts.get(selfIp);
        if (h == null) return;
        h.self = true;
        if (!h.hasMac() && !selfMac.isEmpty()) {
            h.mac = selfMac;
            h.macSource = "this device";
        }
    }

    private Host host(String ip) {
        Host h = hosts.get(ip);
        if (h == null) {
            h = new Host(ip);
            hosts.put(ip, h);
        }
        return h;
    }

    public synchronized List<Host> hosts() {
        List<Host> out = new ArrayList<>(hosts.values());
        Collections.sort(out, (a, b) -> IpRange.compare(a.ip, b.ip));
        return out;
    }

    public synchronized int size() {
        return hosts.size();
    }

    public synchronized int withoutMac() {
        int n = 0;
        for (Host h : hosts.values()) if (!h.hasMac()) n++;
        return n;
    }

    public static boolean outsideTarget(String target, String localCidr) {
        IpRange range = IpRange.parse(target);
        if (range == null || localCidr == null) return false;
        int slash = localCidr.indexOf('/');
        String ip = (slash >= 0 ? localCidr.substring(0, slash) : localCidr).trim();
        return IpRange.isIpv4(ip) && !range.contains(ip);
    }
}
