package io.freemytv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Philips Hue lights through the Hue Bridge on the home network (its local API, v1): no Hue account and no cloud. The
 * bridge is found on the network (Nsd, "_hue._tcp") or typed in, and paired once: press its round button, then Connect
 * on the TV; the bridge hands out a key, kept encrypted on the TV (Prefs.putSecret). Blocking: never on the UI thread.
 */
final class Hue {
    private Hue() {}

    static final String SERVICE = "_hue._tcp.";

    /** Thrown by `pair` while the bridge's button hasn't been pressed. */
    static final class ButtonNotPressed extends IOException {
        ButtonNotPressed() { super("press the button on the Hue Bridge"); }
    }

    /** Asks the bridge for a key. The bridge only says yes for 30 seconds after its button was pressed. */
    static String pair(String ip) throws IOException {
        Net.Response r = Net.request("POST", "http://" + ip + "/api", null, "{\"devicetype\":\"toria#tv\"}", 6000);
        try {
            JSONObject first = new JSONArray(r.body).getJSONObject(0);
            if (first.has("success")) return first.getJSONObject("success").getString("username");
            if (first.optJSONObject("error") != null && first.getJSONObject("error").optInt("type") == 101) throw new ButtonNotPressed();
            throw new IOException(first.optJSONObject("error") != null ? first.getJSONObject("error").optString("description") : r.body);
        } catch (JSONException e) {
            throw new IOException("not a Hue Bridge");
        }
    }

    static List<Lights.Lamp> lamps(String ip, String user) throws IOException, JSONException {
        Net.Response r = Net.request("GET", "http://" + ip + "/api/" + user + "/lights", null, null, 5000);
        if (r.body.trim().startsWith("[")) throw new IOException("the bridge refused the key (pair it again)");
        JSONObject all = new JSONObject(r.body);
        List<Lights.Lamp> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject o = all.getJSONObject(id), st = o.getJSONObject("state");
            if (!st.optBoolean("reachable", true)) continue;
            Lights.Lamp l = new Lights.Lamp();
            l.brand = Lights.HUE;
            l.id = id;
            l.ip = ip;
            l.deviceName = o.optString("name", "Hue " + id);
            l.on = st.optBoolean("on");
            l.dimming = st.has("bri") ? Math.max(1, Math.round(st.optInt("bri") * 100f / 254)) : 100;
            l.color = st.has("xy") || st.has("hue");
            l.white = st.has("ct") || l.color;
            if ("ct".equals(st.optString("colormode")) && st.optInt("ct") > 0) {
                l.temp = Math.round(1_000_000f / st.optInt("ct"));
            } else if (st.has("hue") && st.has("sat")) {
                int rgb = android.graphics.Color.HSVToColor(new float[] {st.optInt("hue") * 360f / 65535, st.optInt("sat") / 254f, 1});
                l.r = (rgb >> 16) & 255;
                l.g = (rgb >> 8) & 255;
                l.b = rgb & 255;
            }
            out.add(l);
        }
        java.util.Collections.sort(out, (a, b) -> a.deviceName.compareToIgnoreCase(b.deviceName));
        return out;
    }

    static boolean set(String ip, String user, List<Lights.Lamp> lamps, Lights.Pilot p) {
        boolean ok = true;
        for (Lights.Lamp l : lamps) {
            try {
                JSONObject s = new JSONObject();
                if (p.on != null) s.put("on", p.on);
                if (!Boolean.FALSE.equals(p.on)) {
                    if (p.dimming >= 0) s.put("bri", Math.max(1, Math.min(254, Math.round(p.dimming * 254 / 100f))));
                    if (p.temp > 0 && l.white) s.put("ct", Math.max(153, Math.min(500, Math.round(1_000_000f / p.temp))));
                    else if (p.rgb >= 0 && l.color) s.put("xy", xy(p.rgb));
                }
                s.put("transitiontime", 3);
                Net.Response r = Net.request("PUT", "http://" + ip + "/api/" + user + "/lights/" + l.id + "/state", null, s.toString(), 5000);
                ok &= r.code == 200 && !r.body.contains("\"error\"");
            } catch (IOException | JSONException e) {
                AppLog.w("hue set: " + e.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    /** sRGB → the CIE xy the bridge takes (gamma removed, Wide RGB D65). */
    static JSONArray xy(int rgb) throws JSONException {
        double r = gamma(((rgb >> 16) & 255) / 255.0), g = gamma(((rgb >> 8) & 255) / 255.0), b = gamma((rgb & 255) / 255.0);
        double x = r * 0.649926 + g * 0.103455 + b * 0.197109;
        double y = r * 0.234327 + g * 0.743075 + b * 0.022598;
        double z = g * 0.053077 + b * 1.035763;
        double sum = x + y + z;
        if (sum <= 0) return new JSONArray().put(0.3227).put(0.329);   // black: a neutral white point
        return new JSONArray().put(Math.round(x / sum * 10000) / 10000.0).put(Math.round(y / sum * 10000) / 10000.0);
    }

    private static double gamma(double v) {
        return v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92;
    }
}
