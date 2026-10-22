package io.troiaos;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Every smart light the TV can reach, whatever the brand, all on the home network with no cloud and no account:
 * WiZ, LIFX and Govee are found by themselves (each answers a broadcast); a Philips Hue Bridge is paired once with its
 * button, and Home Assistant (for any other brand) is connected once with its address and a token (Customize →
 * Lights). The names given on the TV and the hidden lamps are in Prefs. Every call blocks: never on the UI thread.
 */
final class Lights {
    private Lights() {}

    static final String WIZ = "wiz", LIFX = "lifx", GOVEE = "govee", HUE = "hue", HA = "ha";
    private static final String[] BRANDS = {WIZ, LIFX, GOVEE, HUE, HA};

    /** A lamp: its brand's address for it, what the brand calls it, and its state (as last read or set). */
    static final class Lamp {
        String brand, id, ip, deviceName = "";
        boolean on, color = true, white = true;
        /** Brightness 1–100; white temperature in K (0: a color or a scene); WiZ's scene (0: none); color (r < 0: none). */
        int dimming = 100, temp, scene, r = -1, g, b;
        /** LIFX: the lamp's MAC as the protocol's target, and its hue and saturation (0–65535). */
        byte[] target;
        int hue, sat;

        /** Unique and stable: what names and hidden lamps are saved under. */
        String key() { return brand + ":" + id; }

        String name() {
            String n = Prefs.lampName(key());
            return !n.isEmpty() ? n : !deviceName.isEmpty() ? deviceName : brandName(brand);
        }
    }

    /** What to do: switch on or off, and/or a brightness, a white, a color or a WiZ scene (with a color for others). */
    static final class Pilot {
        Boolean on;
        int dimming = -1, temp, scene, rgb = -1;

        static Pilot power(boolean on) {
            Pilot p = new Pilot();
            p.on = on;
            return p;
        }

        static Pilot level(int dimming) {
            Pilot p = power(true);
            p.dimming = dimming;
            return p;
        }
    }

    static String brandName(String brand) {
        switch (brand) {
            case WIZ: return "WiZ";
            case LIFX: return "LIFX";
            case GOVEE: return "Govee";
            case HUE: return "Philips Hue";
            default: return "Home Assistant";
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    /** The shown lamps (hidden ones left out), brand by brand, each brand's in its own order. */
    static List<Lamp> discover(Context c, int waitMs) {
        List<Lamp> out = new ArrayList<>();
        for (Lamp l : all(c, waitMs)) if (!Prefs.lampHidden(l.key())) out.add(l);
        return out;
    }

    /** Every lamp that answered, hidden ones too (Customize lists them all). Brands are asked at the same time. */
    static List<Lamp> all(final Context c, final int waitMs) {
        Prefs.load(c);
        Map<String, Future<List<Lamp>>> asks = new HashMap<>();
        for (final String brand : BRANDS) {
            if (!enabled(brand)) continue;
            asks.put(brand, POOL.submit(() -> find(c, brand, waitMs)));
        }
        List<Lamp> out = new ArrayList<>();
        for (String brand : BRANDS) {
            Future<List<Lamp>> f = asks.get(brand);
            if (f == null) continue;
            try {
                out.addAll(f.get(waitMs + 6000L, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                AppLog.w("lights " + brand + ": " + e.getMessage());
                f.cancel(true);
            }
        }
        if (!out.isEmpty() && !Prefs.has("lights_seen")) Prefs.put(c, "lights_seen", true);
        return out;
    }

    /** WiZ, LIFX and Govee are always looked for; Hue and Home Assistant once they're connected. */
    private static boolean enabled(String brand) {
        // Only whether a key is saved: no decrypting just to know.
        if (brand.equals(HUE)) return !Prefs.hueBridge().isEmpty() && Prefs.has("hue_user");
        if (brand.equals(HA)) return !Prefs.haUrl().isEmpty() && Prefs.has("ha_token");
        return true;
    }

    private static List<Lamp> find(Context c, String brand, int waitMs) throws Exception {
        switch (brand) {
            case WIZ: return Wiz.discover(c, waitMs);
            case LIFX: return Lifx.discover(waitMs);
            case GOVEE: return Govee.discover(waitMs);
            case HUE: return Hue.lamps(Prefs.hueBridge(), Prefs.hueUser());
            default: return HomeAssistant.lamps(Prefs.haUrl(), Prefs.haToken());
        }
    }

    /** True once any lamp was ever found, or Hue or Home Assistant is connected (the home screen only asks then). */
    static boolean known(Context c) {
        Prefs.load(c);
        return Prefs.has("lights_seen") || Wiz.known(c) || enabled(HUE) || enabled(HA);
    }

    /** True if any lamp is on; null if there is none. */
    static Boolean anyOn(List<Lamp> lamps) {
        if (lamps == null || lamps.isEmpty()) return null;
        for (Lamp l : lamps) if (l.on) return true;
        return false;
    }

    /** All the shown lamps off if any is on, otherwise all on. The new state, or null if no lamp answered. */
    static Boolean toggleAll(Context c) {
        List<Lamp> lamps = discover(c, 1200);
        Boolean on = anyOn(lamps);
        if (on == null) return null;
        set(c, lamps, Pilot.power(!on));
        return !on;
    }

    /** Sends to each brand's lamps (brands at the same time); true when every brand confirmed (or can't confirm). */
    static boolean set(final Context c, List<Lamp> lamps, final Pilot p) {
        Map<String, List<Lamp>> byBrand = new HashMap<>();
        for (Lamp l : lamps) {
            List<Lamp> list = byBrand.get(l.brand);
            if (list == null) byBrand.put(l.brand, list = new ArrayList<>());
            list.add(l);
        }
        List<Future<Boolean>> sends = new ArrayList<>();
        for (final Map.Entry<String, List<Lamp>> e : byBrand.entrySet()) {
            sends.add(POOL.submit(() -> {
                switch (e.getKey()) {
                    case WIZ: return Wiz.set(e.getValue(), p);
                    case LIFX: return Lifx.set(e.getValue(), p);
                    case GOVEE: return Govee.set(e.getValue(), p);
                    case HUE: return Hue.set(Prefs.hueBridge(), Prefs.hueUser(), e.getValue(), p);
                    default: return HomeAssistant.set(Prefs.haUrl(), Prefs.haToken(), e.getValue(), p);
                }
            }));
        }
        boolean ok = true;
        for (Future<Boolean> f : sends) {
            try {
                ok &= f.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                AppLog.w("lights set: " + e.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    /** Blinks a lamp twice (Customize: which one is it?), then puts it back as it was. */
    static void blink(Context c, Lamp l) {
        List<Lamp> one = Collections.singletonList(l);
        boolean was = l.on;
        try {
            for (int i = 0; i < 2; i++) {
                set(c, one, Pilot.power(!was));
                Thread.sleep(700);
                set(c, one, Pilot.power(was));
                Thread.sleep(700);
            }
        } catch (InterruptedException ignored) {
        }
    }

    /** rgb → hue (0–360), saturation and value (0–1). */
    static float[] hsv(int rgb) {
        float[] out = new float[3];
        android.graphics.Color.colorToHSV(0xFF000000 | rgb, out);
        return out;
    }
}
