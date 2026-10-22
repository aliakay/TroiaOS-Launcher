package io.freemytv;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Govee lights through Govee's LAN API (https://app-h5.govee.com/user-manual/wlan-guide): a scan to the multicast
 * address 239.255.255.250:4001 is answered on our port 4002, and commands go to the lamp's port 4003. No account, no
 * cloud, but each lamp needs "LAN Control" switched on in the Govee app. Govee doesn't confirm commands, so they're
 * sent twice. Blocking: never on the UI thread.
 */
final class Govee {
    private Govee() {}

    private static final int SCAN_PORT = 4001, LISTEN_PORT = 4002, COMMAND_PORT = 4003;
    private static final String GROUP = "239.255.255.250";

    /** Port 4002 can only be ours once at a time: one search after the other. */
    static synchronized List<Lights.Lamp> discover(int waitMs) {
        Map<String, Lights.Lamp> found = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(LISTEN_PORT));
            byte[] scan = msg("scan", new JSONObject().put("account_topic", "reserve"));
            s.send(new DatagramPacket(scan, scan.length, InetAddress.getByName(GROUP), SCAN_PORT));
            receive(s, found, System.currentTimeMillis() + waitMs / 2);
            if (found.isEmpty()) return new ArrayList<>();
            byte[] status = msg("devStatus", new JSONObject());
            for (Lights.Lamp l : found.values()) s.send(new DatagramPacket(status, status.length, InetAddress.getByName(l.ip), COMMAND_PORT));
            receive(s, found, System.currentTimeMillis() + Math.max(400, waitMs / 2));
        } catch (IOException | JSONException e) {
            AppLog.w("govee: " + e.getMessage());
        }
        List<Lights.Lamp> lamps = new ArrayList<>(found.values());
        Collections.sort(lamps, (a, b) -> a.ip.compareTo(b.ip));
        return lamps;
    }

    private static void receive(DatagramSocket s, Map<String, Lights.Lamp> found, long until) throws IOException {
        byte[] buf = new byte[1024];
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return;
            s.setSoTimeout((int) left);
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException e) {
                return;
            }
            try {
                JSONObject m = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)).getJSONObject("msg");
                JSONObject d = m.getJSONObject("data");
                String ip = p.getAddress().getHostAddress();
                if ("scan".equals(m.optString("cmd"))) {
                    Lights.Lamp l = new Lights.Lamp();
                    l.brand = Lights.GOVEE;
                    l.ip = d.optString("ip", ip);
                    l.id = d.optString("device", l.ip);
                    l.deviceName = "Govee " + d.optString("sku", "").trim();
                    found.put(l.id, l);
                } else if ("devStatus".equals(m.optString("cmd"))) {
                    for (Lights.Lamp l : found.values()) {
                        if (!l.ip.equals(ip)) continue;
                        l.on = d.optInt("onOff") == 1;
                        l.dimming = Math.max(1, d.optInt("brightness", 100));
                        int kelvin = d.optInt("colorTemInKelvin", 0);
                        JSONObject c = d.optJSONObject("color");
                        if (kelvin > 0) {
                            l.temp = kelvin;
                        } else if (c != null) {
                            l.r = c.optInt("r");
                            l.g = c.optInt("g");
                            l.b = c.optInt("b");
                        }
                    }
                }
            } catch (JSONException ignored) {
            }
        }
    }

    static boolean set(List<Lights.Lamp> lamps, Lights.Pilot p) {
        try (DatagramSocket s = new DatagramSocket()) {
            List<byte[]> msgs = new ArrayList<>();
            if (p.on != null) msgs.add(msg("turn", new JSONObject().put("value", p.on ? 1 : 0)));
            if (!Boolean.FALSE.equals(p.on)) {
                if (p.temp > 0) {
                    msgs.add(msg("colorwc", new JSONObject().put("color", rgb(0)).put("colorTemInKelvin", Math.max(2000, Math.min(9000, p.temp)))));
                } else if (p.rgb >= 0) {
                    msgs.add(msg("colorwc", new JSONObject().put("color", rgb(p.rgb)).put("colorTemInKelvin", 0)));
                }
                if (p.dimming >= 0) msgs.add(msg("brightness", new JSONObject().put("value", Math.max(1, Math.min(100, p.dimming)))));
            }
            for (int round = 0; round < 2; round++) {
                for (Lights.Lamp l : lamps) {
                    InetAddress a = InetAddress.getByName(l.ip);
                    for (byte[] m : msgs) s.send(new DatagramPacket(m, m.length, a, COMMAND_PORT));
                }
                Thread.sleep(60);
            }
            return true;
        } catch (IOException | JSONException | InterruptedException e) {
            AppLog.w("govee set: " + e.getMessage());
            return false;
        }
    }

    private static JSONObject rgb(int rgb) throws JSONException {
        return new JSONObject().put("r", (rgb >> 16) & 255).put("g", (rgb >> 8) & 255).put("b", rgb & 255);
    }

    private static byte[] msg(String cmd, JSONObject data) throws JSONException {
        return new JSONObject().put("msg", new JSONObject().put("cmd", cmd).put("data", data)).toString().getBytes(StandardCharsets.UTF_8);
    }
}
