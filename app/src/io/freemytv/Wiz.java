package io.freemytv;

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

    static final class Lamp {
        String ip, mac;
        boolean on;
        int dimming = 100, temp, scene, r = -1, g, b;
    }

    /**
     * Finds the lamps: asks for their state on the network's broadcast addresses and at the addresses found last time
     * (twice: a lost packet gets a second chance), and collects the answers for `waitMs`. Sorted by address, so they
     * keep their order (Lamp 1, Lamp 2…).
     */
    static List<Lamp> discover(Context c, int waitMs) {
        SharedPreferences prefs = c.getSharedPreferences("wiz", Context.MODE_PRIVATE);
        Map<String, Lamp> found = new LinkedHashMap<>();
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
        List<Lamp> lamps = new ArrayList<>(found.values());
        Collections.sort(lamps, (x, y) -> Long.compare(ipValue(x.ip), ipValue(y.ip)));
        if (!lamps.isEmpty()) {
            StringBuilder ips = new StringBuilder();
            for (Lamp l : lamps) ips.append(ips.length() == 0 ? "" : ",").append(l.ip);
            prefs.edit().putString("ips", ips.toString()).apply();
        }
        return lamps;
    }

    /** True once lamps were found on this network (the home screen only asks them then). */
    static boolean known(Context c) {
        return !c.getSharedPreferences("wiz", Context.MODE_PRIVATE).getString("ips", "").isEmpty();
    }

    /** True if any lamp answered and is on; null if none answered. */
    static Boolean anyOn(List<Lamp> lamps) {
        if (lamps.isEmpty()) return null;
        for (Lamp l : lamps) if (l.on) return true;
        return false;
    }

    /**
     * The bulb on the home screen: all lamps off if any is on, otherwise all on. Returns the new state, or null if no
     * lamp answered.
     */
    static Boolean toggleAll(Context c) {
        List<Lamp> lamps = discover(c, 1200);
        Boolean on = anyOn(lamps);
        if (on == null) return null;
        set(lamps, params("state", !on));
        return !on;
    }

    /** Sends one setPilot to every lamp, again to the ones that didn't confirm; true when all of them confirmed. */
    static boolean set(List<Lamp> lamps, JSONObject params) {
        byte[] msg;
        try {
            msg = new JSONObject().put("method", "setPilot").put("params", params).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return false;
        }
        Set<String> waiting = new HashSet<>();
        for (Lamp l : lamps) waiting.add(l.ip);
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] buf = new byte[512];
            for (int attempt = 0; attempt < 3 && !waiting.isEmpty(); attempt++) {
                for (String ip : waiting) s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(ip), PORT));
                long until = System.currentTimeMillis() + 400;
                while (!waiting.isEmpty()) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    s.setSoTimeout((int) left);
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String body = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (body.contains("\"success\":true")) waiting.remove(p.getAddress().getHostAddress());
                }
            }
        } catch (IOException e) {
            AppLog.w("wiz set: " + e.getMessage());
            return false;
        }
        if (!waiting.isEmpty()) AppLog.w("wiz: no answer from " + waiting);
        return waiting.isEmpty();
    }

    static JSONObject params(Object... keysAndValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) o.put((String) keysAndValues[i], keysAndValues[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /**
     * Collects answers until `until`; once every lamp known from last time has answered, only a short moment more
     * (for a new one). True in that case: no second round needed.
     */
    private static boolean receive(DatagramSocket s, Map<String, Lamp> found, Set<String> known, long until) throws IOException {
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
            Lamp l = parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress().getHostAddress());
            if (l != null) found.put(l.mac, l);
            if (!complete && !known.isEmpty()) {
                Set<String> answered = new HashSet<>();
                for (Lamp f : found.values()) answered.add(f.ip);
                if (answered.containsAll(known)) {
                    complete = true;
                    until = Math.min(until, System.currentTimeMillis() + 150);
                }
            }
        }
    }

    /** {"method":"getPilot","result":{"mac":…,"state":true,"temp":2700,"dimming":72}} (or r, g, b; or sceneId). */
    private static Lamp parse(String body, String ip) {
        try {
            JSONObject o = new JSONObject(body);
            if (!"getPilot".equals(o.optString("method"))) return null;
            JSONObject r = o.getJSONObject("result");
            Lamp l = new Lamp();
            l.ip = ip;
            l.mac = r.optString("mac", ip);
            l.on = r.optBoolean("state");
            l.dimming = r.optInt("dimming", 100);
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
    private static List<InetAddress> broadcasts() {
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
