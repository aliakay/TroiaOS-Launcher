package io.troiaos;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WiZ smart lights on the home network, through their local UDP API (port 38899): no account and no cloud. The lamps
 * answer only while "Allow local communication" is on in the WiZ app (the default). Every call blocks: run them off
 * the UI thread.
 */
final class Wiz {
    private Wiz() {}

    static final int PORT = 38899;

    /**
     * Finds the lamps: asks for their state on the network's broadcast addresses and at the addresses found last time
     * (twice: a lost packet gets a second chance), and collects the answers for `waitMs`. Sorted by address, so they
     * keep their order (Lamp 1, Lamp 2…).
     */
    static List<Lights.Lamp> discover(Context c, int waitMs) {
        SharedPreferences prefs = c.getSharedPreferences("wiz", Context.MODE_PRIVATE);
        Map<String, Lights.Lamp> found = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] ask = "{\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
            List<InetAddress> targets = new ArrayList<>(broadcasts());
            targets.add(InetAddress.getByName("255.255.255.255"));
            Set<String> known = new HashSet<>();
            for (String ip : prefs.getString("ips", "").split(",")) {
                if (ip.isEmpty()) continue;
                known.add(ip);
                targets.add(InetAddress.getByName(ip));
            }
            long start = System.currentTimeMillis();
            for (int round = 0; round < 2; round++) {
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(ask, ask.length, t, PORT));
                    } catch (IOException ignored) {
                    }
                }
                if (receive(s, found, known, start + (round == 0 ? waitMs / 2 : waitMs))) break;
            }
        } catch (IOException e) {
            AppLog.w("wiz: " + e.getMessage());
        }
        List<Lights.Lamp> lamps = new ArrayList<>(found.values());
        Collections.sort(lamps, (x, y) -> Long.compare(ipValue(x.ip), ipValue(y.ip)));
        if (!lamps.isEmpty()) {
            StringBuilder ips = new StringBuilder();
            for (Lights.Lamp l : lamps) ips.append(ips.length() == 0 ? "" : ",").append(l.ip);
            prefs.edit().putString("ips", ips.toString()).apply();
        }
        for (int i = 0; i < lamps.size(); i++) lamps.get(i).deviceName = "WiZ " + (i + 1);
        return lamps;
    }

    /** True once lamps were found on this network (the home screen only asks them then). */
    static boolean known(Context c) {
        return !c.getSharedPreferences("wiz", Context.MODE_PRIVATE).getString("ips", "").isEmpty();
    }

    /** Sends one setPilot to every lamp, again to the ones that didn't confirm; true when all of them confirmed. */
    static boolean set(List<Lights.Lamp> lamps, Lights.Pilot p) {
        byte[] msg;
        try {
            msg = new JSONObject().put("method", "setPilot").put("params", params(p)).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return false;
        }
        Set<String> waiting = new HashSet<>();
        for (Lights.Lamp l : lamps) waiting.add(l.ip);
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] buf = new byte[512];
            for (int attempt = 0; attempt < 3 && !waiting.isEmpty(); attempt++) {
                for (String ip : waiting) s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(ip), PORT));
                long until = System.currentTimeMillis() + 400;
                while (!waiting.isEmpty()) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    s.setSoTimeout((int) left);
                    DatagramPacket answer = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(answer);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String body = new String(answer.getData(), 0, answer.getLength(), StandardCharsets.UTF_8);
                    if (body.contains("\"success\":true")) waiting.remove(answer.getAddress().getHostAddress());
                }
            }
        } catch (IOException e) {
            AppLog.w("wiz set: " + e.getMessage());
            return false;
        }
        if (!waiting.isEmpty()) AppLog.w("wiz: no answer from " + waiting);
        return waiting.isEmpty();
    }

    /** setPilot's params: state, dimming (10–100), then a scene, a white (temp) or a color (r, g, b). */
    private static JSONObject params(Lights.Pilot p) throws JSONException {
        JSONObject o = new JSONObject();
        if (p.on != null) o.put("state", p.on);
        if (Boolean.FALSE.equals(p.on)) return o;
        if (p.dimming >= 0) o.put("dimming", Math.max(10, Math.min(100, p.dimming)));
        if (p.scene != 0) o.put("sceneId", p.scene);
        else if (p.temp > 0) o.put("temp", Math.max(2200, Math.min(6500, p.temp)));
        else if (p.rgb >= 0) o.put("r", (p.rgb >> 16) & 255).put("g", (p.rgb >> 8) & 255).put("b", p.rgb & 255);
        return o;
    }

    /**
     * Collects answers until `until`; once every lamp known from last time has answered, only a short moment more
     * (for a new one). True in that case: no second round needed.
     */
    private static boolean receive(DatagramSocket s, Map<String, Lights.Lamp> found, Set<String> known, long until) throws IOException {
        byte[] buf = new byte[1024];
        boolean complete = false;
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return complete;
            s.setSoTimeout((int) left);
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return complete;
            }
            Lights.Lamp l = parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress().getHostAddress());
            if (l != null) found.put(l.id, l);
            if (!complete && !known.isEmpty()) {
                Set<String> answered = new HashSet<>();
                for (Lights.Lamp f : found.values()) answered.add(f.ip);
                if (answered.containsAll(known)) {
                    complete = true;
                    until = Math.min(until, System.currentTimeMillis() + 150);
                }
            }
        }
    }

    /** {"method":"getPilot","result":{"mac":…,"state":true,"temp":2700,"dimming":72}} (or r, g, b; or sceneId). */
    private static Lights.Lamp parse(String body, String ip) {
        try {
            JSONObject o = new JSONObject(body);
            if (!"getPilot".equals(o.optString("method"))) return null;
            JSONObject r = o.getJSONObject("result");
            Lights.Lamp l = new Lights.Lamp();
            l.brand = Lights.WIZ;
            l.ip = ip;
            l.id = r.optString("mac", ip);
            l.on = r.optBoolean("state");
            l.dimming = r.optInt("dimming", 100);
            l.color = l.white = true;
            l.temp = r.optInt("temp", 0);
            l.scene = r.optInt("sceneId", 0);
            if (r.has("r")) {
                l.r = r.optInt("r");
                l.g = r.optInt("g");
                l.b = r.optInt("b");
            }
            return l;
        } catch (JSONException e) {
            return null;
        }
    }

    /** Broadcast addresses of the networks the TV is on (Ethernet or Wi-Fi; not the VPN). */
    static List<InetAddress> broadcasts() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> list = NetworkInterface.getNetworkInterfaces();
            while (list != null && list.hasMoreElements()) {
                NetworkInterface n = list.nextElement();
                if (!n.isUp() || n.isLoopback() || n.isPointToPoint()) continue;
                for (InterfaceAddress a : n.getInterfaceAddresses()) if (a.getBroadcast() != null) out.add(a.getBroadcast());
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static long ipValue(String ip) {
        long v = 0;
        for (String part : ip.split("\\.")) {
            try {
                v = v * 256 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        return v;
    }
}
