package com.zalexdev.stryker.geomac.io;

import com.zalexdev.stryker.geomac.model.GeoPin;
import com.zalexdev.stryker.utils.Core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class GeoExporter {

    public static File dir(Core core) {
        File dir = new File(core.getShareRoot(), "geomac");
        dir.mkdirs();
        return dir;
    }

    private GeoExporter() {}

    public static File exportJson(Core core, List<GeoPin> pins) throws IOException, JSONException {
        File out = new File(dir(core), "export-" + ts() + ".json");
        JSONArray arr = new JSONArray();
        for (GeoPin p : pins) arr.put(p.toJson());
        JSONObject root = new JSONObject();
        root.put("version", 2);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("pins", arr);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(root.toString(2).getBytes());
        }
        return out;
    }

    public static File exportKml(Core core, List<GeoPin> pins) throws IOException {
        File out = new File(dir(core), "export-" + ts() + ".kml");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n");
        sb.append("  <name>Stryker GeoMac export</name>\n");
        for (GeoPin p : pins) {
            sb.append("  <Placemark>\n");
            sb.append("    <name>").append(xml(p.ssid == null || p.ssid.isEmpty() ? p.bssid : p.ssid)).append("</name>\n");
            sb.append("    <description>BSSID: ").append(xml(p.bssid))
                    .append(" · ").append(p.category.name()).append("</description>\n");
            sb.append("    <Point><coordinates>")
                    .append(p.lon).append(',').append(p.lat).append(",0")
                    .append("</coordinates></Point>\n");
            sb.append("  </Placemark>\n");
        }
        sb.append("</Document></kml>\n");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(sb.toString().getBytes());
        }
        return out;
    }

    public static List<File> listJsonExports(Core core) {
        File[] files = dir(core).listFiles((f, name) -> name != null && name.endsWith(".json"));
        ArrayList<File> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) out.add(f);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static List<GeoPin> importJson(File source) throws IOException, JSONException {
        StringBuilder buf = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(source))) {
            String line;
            while ((line = br.readLine()) != null) buf.append(line).append('\n');
        }
        JSONObject root = new JSONObject(buf.toString());
        JSONArray arr = root.optJSONArray("pins");
        if (arr == null) arr = new JSONArray(buf.toString());
        ArrayList<GeoPin> result = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            GeoPin pin = GeoPin.fromJson(arr.getJSONObject(i));
            if (pin.bssid != null && !pin.bssid.isEmpty()) result.add(pin);
        }
        return result;
    }

    private static String ts() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
