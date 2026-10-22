package io.freemytv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Any other brand of light, through the person's own Home Assistant (its REST API, https://developers.home-assistant.io/docs/api/rest):
 * the lights it knows (light.* entities) show up here. Connected once in Customize → Lights with its address (found on
 * the network as "_home-assistant._tcp", or typed) and a long-lived access token, which is kept encrypted on the TV.
 * Plain http:// only inside the home network (Net). Blocking: never on the UI thread.
 */
final class HomeAssistant {
    private HomeAssistant() {}

    static final String SERVICE = "_home-assistant._tcp.";

    /** "http://192.168.1.5:8123/" → "http://192.168.1.5:8123" (and http:// added when there's no scheme). */
    static String clean(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty() && !u.contains("://")) u = "http://" + u;
        return u;
    }

    /** Null if the address and token work; otherwise the reason. */
    static String check(String url, String token) {
        try {
            Net.Response r = Net.request("GET", clean(url) + "/api/", token, null, 6000);
            if (r.code == 401 || r.code == 403) return Ui.tr("Anahtar kabul edilmedi", "The token wasn't accepted");
            if (r.code != 200) return "HTTP " + r.code;
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    static List<Lights.Lamp> lamps(String url, String token) throws IOException, JSONException {
        Net.Response r = Net.request("GET", clean(url) + "/api/states", token, null, 8000);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        JSONArray all = new JSONArray(r.body);
        List<Lights.Lamp> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.getJSONObject(i);
            String id = e.optString("entity_id");
            String state = e.optString("state");
            if (!id.startsWith("light.") || state.equals("unavailable") || state.equals("unknown")) continue;
            JSONObject a = e.optJSONObject("attributes");
            if (a == null) a = new JSONObject();
            if (a.has("entity_id")) continue;     // a light group: its lamps are listed one by one already
            Lights.Lamp l = new Lights.Lamp();
            l.brand = Lights.HA;
            l.id = id;
            l.ip = "";
            l.deviceName = a.optString("friendly_name", id);
            l.on = state.equals("on");
            l.dimming = a.has("brightness") && !a.isNull("brightness") ? Math.max(1, Math.round(a.optInt("brightness") * 100f / 255)) : 100;
            JSONArray modes = a.optJSONArray("supported_color_modes");
            String m = modes == null ? "" : modes.toString();
            l.color = m.contains("hs") || m.contains("xy") || m.contains("rgb");
            l.white = m.contains("color_temp") || l.color;
            JSONArray rgb = a.optJSONArray("rgb_color");
            if ("color_temp".equals(a.optString("color_mode")) && a.optInt("color_temp_kelvin") > 0) {
                l.temp = a.optInt("color_temp_kelvin");
            } else if (rgb != null && rgb.length() == 3) {
                l.r = rgb.optInt(0);
                l.g = rgb.optInt(1);
                l.b = rgb.optInt(2);
            }
            out.add(l);
        }
        java.util.Collections.sort(out, (x, y) -> x.deviceName.compareToIgnoreCase(y.deviceName));
        return out;
    }

    static boolean set(String url, String token, List<Lights.Lamp> lamps, Lights.Pilot p) {
        boolean ok = true;
        try {
            if (Boolean.FALSE.equals(p.on)) {
                JSONArray ids = new JSONArray();
                for (Lights.Lamp l : lamps) ids.put(l.id);
                return call(url, token, "turn_off", new JSONObject().put("entity_id", ids));
            }
            for (Lights.Lamp l : lamps) {      // one by one: each lamp only gets what it can do
                JSONObject d = new JSONObject().put("entity_id", l.id);
                if (p.dimming >= 0) d.put("brightness_pct", Math.max(1, Math.min(100, p.dimming)));
                if (p.temp > 0 && l.white) d.put("color_temp_kelvin", p.temp);
                else if (p.rgb >= 0 && l.color) d.put("rgb_color", new JSONArray().put((p.rgb >> 16) & 255).put((p.rgb >> 8) & 255).put(p.rgb & 255));
                ok &= call(url, token, "turn_on", d);
            }
        } catch (JSONException e) {
            return false;
        }
        return ok;
    }

    private static boolean call(String url, String token, String service, JSONObject data) {
        try {
            Net.Response r = Net.request("POST", clean(url) + "/api/services/light/" + service, token, data.toString(), 8000);
            return r.code == 200;
        } catch (IOException e) {
            AppLog.w("home assistant: " + e.getMessage());
            return false;
        }
    }
}
