package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the person changed on the TV (Customize): the dock's apps, Control Center's modules, the greeting, the weather's
 * city, the temperature unit, the watchlist and the wallpaper. Anything not set falls back to config.properties, so a fresh build
 * works as configured. Kept in its own file (custom.json), read by both processes: `load` re-reads it only when it
 * changed, so calling it on every resume is enough.
 */
final class Prefs {
    private Prefs() {}

    /** Control Center's modules: the round buttons, then the tiles (each group keeps its own order). */
    static final String[] BUTTONS = {"live", "inputs", "wifi", "settings", "sleep", "boost"};
    static final String[] TILES = {"media", "lights", "markets", "volume"};

    private static final Object LOCK = new Object();
    private static JSONObject data = new JSONObject();
    private static String loaded;       // the file's text as last read (tiny: comparing it is cheaper than a mistake)
    /** Bumped on every change (load or save): views compare it to know when to rebuild. */
    static volatile int version;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "custom.json");
    }

    /** Re-reads the file; true if another process (or screen) changed it since. */
    static boolean load(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            String text = "";
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[(int) f.length()];
                    int read = 0;
                    while (read < buf.length) {
                        int n = in.read(buf, read, buf.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    text = new String(buf, 0, read, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    AppLog.w("prefs: " + e.getMessage());
                }
            }
            if (text.equals(loaded)) return false;
            loaded = text;
            try {
                data = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                AppLog.w("prefs: " + e.getMessage());
                data = new JSONObject();
            }
            version++;
            return true;
        }
    }

    private static void save(Context c) {
        synchronized (LOCK) {
            File dir = c.getFilesDir(), tmp = new File(dir, "custom.json.tmp");
            String text = data.toString();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("prefs save: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(file(c))) AppLog.w("prefs: could not replace custom.json");
            loaded = text;
            version++;
        }
    }

    private static String string(String key, String fallback) {
        synchronized (LOCK) {
            return data.has(key) ? data.optString(key, fallback).trim() : fallback.trim();
        }
    }

    static void put(Context c, String key, Object value) {
        synchronized (LOCK) {
            try {
                if (value == null) data.remove(key);
                else data.put(key, value);
            } catch (JSONException ignored) {
            }
            save(c);
        }
    }

    // MARK: - Home screen

    static String name() {
        return string("name", Config.NAME);
    }

    static String city() {
        return string("city", Config.CITY);
    }

    static String latitude() {
        return string("latitude", Config.LATITUDE);
    }

    static String longitude() {
        return string("longitude", Config.LONGITUDE);
    }

    /** How the home screen shows the apps: "dock" (the glass bar) or "list" (big names over the photo). */
    static String layout() {
        return string("layout", Config.LAYOUT).equals("list") ? "list" : "dock";
    }

    /** C, F or auto. */
    static String temperature() {
        return string("temperature", Config.TEMPERATURE);
    }

    /** Yahoo symbols with optional labels: "^GSPC:SPX,BTC-USD:BTC". */
    static String stocks() {
        return string("stocks", Config.STOCKS);
    }

    // MARK: - Wallpaper

    /** What's behind the home screen: nature photos, NASA's space photos, NASA's picture of the day, or soft colors. */
    static final String[] WALLPAPERS = {"nature", "space", "apod", "colors"};

    static String wallpaper() {
        String w = string("wallpaper", "nature");
        return Arrays.asList(WALLPAPERS).contains(w) ? w : "nature";
    }

    /** The one photo (its file) kept on screen, or "" for a new one every hour. */
    static String wallpaperPhoto() {
        return string("wallpaper_photo", "");
    }

    /** How much the photo is darkened, in percent: 0, 15, 30 or 45. */
    static int wallpaperDim() {
        try {
            return Math.max(0, Math.min(60, Integer.parseInt(string("wallpaper_dim", "0"))));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void setPlace(Context c, String city, double lat, double lon) {
        synchronized (LOCK) {
            try {
                data.put("city", city).put("latitude", String.valueOf(lat)).put("longitude", String.valueOf(lon));
            } catch (JSONException ignored) {
            }
            save(c);
        }
    }

    // MARK: - Dock

    /** The dock's packages in order, or null for the default (config.properties `dock`, else the known apps). */
    static List<String> dock() {
        synchronized (LOCK) {
            JSONArray a = data.optJSONArray("dock");
            if (a == null) {
                String d = Config.DOCK.trim();
                return d.isEmpty() ? null : split(d);
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            return out;
        }
    }

    static void setDock(Context c, List<String> pkgs) {
        put(c, "dock", pkgs == null ? null : new JSONArray(pkgs));
    }

    // MARK: - Control Center

    /** The group's modules in order, each with whether it shows. */
    static List<Module> modules(String[] group) {
        List<Module> out = new ArrayList<>();
        synchronized (LOCK) {
            JSONArray order = data.optJSONArray(group == BUTTONS ? "buttons" : "tiles");
            JSONArray hidden = data.optJSONArray("hidden");
            List<String> off = new ArrayList<>();
            if (hidden != null) for (int i = 0; i < hidden.length(); i++) off.add(hidden.optString(i));
            List<String> ids = new ArrayList<>();
            if (order != null) {
                for (int i = 0; i < order.length(); i++) {
                    String id = order.optString(i);
                    if (Arrays.asList(group).contains(id) && !ids.contains(id)) ids.add(id);
                }
            }
            for (String id : group) if (!ids.contains(id)) ids.add(id);   // a new module shows up at the end
            for (String id : ids) out.add(new Module(id, !off.contains(id)));
        }
        return out;
    }

    static void setModules(Context c, List<Module> buttons, List<Module> tiles) {
        synchronized (LOCK) {
            JSONArray b = new JSONArray(), t = new JSONArray(), hidden = new JSONArray();
            for (Module m : buttons) {
                b.put(m.id);
                if (!m.on) hidden.put(m.id);
            }
            for (Module m : tiles) {
                t.put(m.id);
                if (!m.on) hidden.put(m.id);
            }
            try {
                data.put("buttons", b).put("tiles", t).put("hidden", hidden);
            } catch (JSONException ignored) {
            }
            save(c);
        }
    }

    static void resetModules(Context c) {
        synchronized (LOCK) {
            data.remove("buttons");
            data.remove("tiles");
            data.remove("hidden");
            save(c);
        }
    }

    static final class Module {
        final String id;
        boolean on;

        Module(String id, boolean on) {
            this.id = id;
            this.on = on;
        }
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
