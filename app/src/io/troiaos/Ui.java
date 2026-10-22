package io.troiaos;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    static final Locale TR = new Locale("tr", "TR");
    /** English or Turkish: the choice in TroiaOS's settings; without one, config.properties ("language"). */
    static volatile boolean en = english();
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        loadLanguage(c);
        Prefs.load(c);
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /** "en"/"tr" in config.properties, or "auto": Turkish when the TV is set to Turkish, English otherwise. */
    static boolean english() {
        String lang = Config.LANGUAGE.trim().toLowerCase(Locale.ROOT);
        if (lang.startsWith("tr")) return false;
        if (lang.startsWith("en")) return true;
        return !"tr".equals(Locale.getDefault().getLanguage());
    }

    /**
     * The language picked in the settings (saved in the state file, shared by the app's processes); true if it
     * changed since the last call.
     */
    static boolean loadLanguage(Context c) {
        String saved = HomeMode.state(c).optString("language", "");
        boolean next = saved.equals("en") || (!saved.equals("tr") && english());
        boolean changed = next != en;
        en = next;
        return changed;
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String turkish, String english) {
        return en ? english : turkish;
    }

    /** "21:47" (24-hour in both languages, as in Germany and Turkey). */
    static String clock(long now) {
        return new SimpleDateFormat("HH:mm", en ? Locale.UK : TR).format(new Date(now));
    }

    /** "7:21" (sunrise and sunset). */
    static String time(long at) {
        return new SimpleDateFormat("H:mm", en ? Locale.UK : TR).format(new Date(at));
    }

    /** "19 Ekim Pazartesi" in Turkish; "Monday, 19 October" in English. */
    static String date(long now) {
        return en ? new SimpleDateFormat("EEEE, d MMMM", Locale.UK).format(new Date(now))
                : new SimpleDateFormat("d MMMM EEEE", TR).format(new Date(now));
    }

    /** "SALI · 20 EKİM" / "TUESDAY · 20 OCTOBER": the date next to the clock, in capitals. */
    static String dateLine(long now) {
        Locale l = en ? Locale.UK : TR;
        return new SimpleDateFormat("EEEE · d MMMM", l).format(new Date(now)).toUpperCase(l);
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
