package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * The background where the TV has no AGSL (Android 12): soft clouds of warm color, like the macOS and iOS wallpapers —
 * cream, peach and sand by day, graphite with amber, copper and rose at night (no blue) — and the same picture heavily
 * blurred (the "frost" the glass
 * shows through). Made once per process at a low resolution (they're soft: scaling up costs nothing in quality) with
 * a little noise so the gradients don't band.
 */
final class Wallpaper {
    static final int W = 640, H = 360, FW = 320, FH = 180;

    private static Wallpaper instance;

    final Bitmap day, night, dayFrost, nightFrost;
    /** A photo (Photos) in place of the clouds, 1920 × 1080, and its blur for the glass; null = the clouds. */
    volatile Bitmap photo, photoFrost;

    static synchronized Wallpaper get() {
        if (instance == null) instance = new Wallpaper();
        return instance;
    }

    /** Clouds: center (fraction of the width and height) and radius (fraction of the width). */
    private static final float[][] CLOUDS = {
        {0.06f, 0.10f, 0.62f}, {0.92f, 0.06f, 0.55f}, {0.74f, 0.98f, 0.62f}, {0.16f, 0.94f, 0.55f}, {0.52f, 0.42f, 0.38f},
    };

    private Wallpaper() {
        day = paint(0xFFF3EFEA, new int[] {0xFFFFCDAA, 0xFFF5C3CF, 0xFFFFE1A6, 0xFFD9E6CF, 0xFFEFD8C2}, 0.9f, 11);
        night = paint(0xFF13110F, new int[] {0xFFA8652A, 0xFF853A2C, 0xFF77344C, 0xFF6A5836, 0xFF3A2B22}, 0.62f, 23);
        dayFrost = frost(day, 0xFFFFFFFF, 0.16f);
        nightFrost = frost(night, 0xFFFFE2C2, 0.05f);
    }

    private static Bitmap paint(int base, int[] colors, float strength, long seed) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(base);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        for (int i = 0; i < CLOUDS.length; i++) {
            float[] d = CLOUDS[i];
            int color = colors[i % colors.length];
            p.setShader(new RadialGradient(d[0] * W, d[1] * H, d[2] * W,
                    new int[] {Ui.alpha(color, strength), Ui.alpha(color, strength * 0.42f), Ui.alpha(color, 0)},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, p);
        }
        int[] px = new int[W * H];
        b.getPixels(px, 0, W, 0, 0, W, H);
        blur(px, W, H, 8, 2);
        Random r = new Random(seed);
        for (int i = 0; i < px.length; i++) {         // ±1 of noise: no visible steps in the gradients
            int n = r.nextInt(3) - 1, p0 = px[i];
            px[i] = 0xFF000000 | (clamp(((p0 >> 16) & 255) + n) << 16) | (clamp(((p0 >> 8) & 255) + n) << 8) | clamp((p0 & 255) + n);
        }
        b.setPixels(px, 0, W, 0, 0, W, H);
        return b;
    }

    /** The photo is replaced on the main thread (the old one is left to the garbage collector: it may still be drawn). */
    void setPhoto(Bitmap image, Bitmap frost) {
        photoFrost = frost;
        photo = image;
    }

    boolean hasPhoto() {
        return photo != null;
    }

    /** The glass's view of a photo: small and heavily blurred. */
    static Bitmap frostOf(Bitmap image) {
        Bitmap small = Bitmap.createScaledBitmap(image, FW, FH, true);
        int[] px = new int[FW * FH];
        small.getPixels(px, 0, FW, 0, 0, FW, FH);
        small.recycle();
        blur(px, FW, FH, 12, 3);
        Bitmap out = Bitmap.createBitmap(FW, FH, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, FW, 0, 0, FW, FH);
        return out;
    }

    /** Half size, blurred a lot, with a veil of `veil` (milky by day, a cool sheen at night). */
    private static Bitmap frost(Bitmap src, int veil, float amount) {
        Bitmap small = Bitmap.createScaledBitmap(src, FW, FH, true);
        int[] px = new int[FW * FH];
        small.getPixels(px, 0, FW, 0, 0, FW, FH);
        small.recycle();
        blur(px, FW, FH, 14, 3);
        int vr = (veil >> 16) & 255, vg = (veil >> 8) & 255, vb = veil & 255;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + vr * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + vg * amount);
            int b = Math.round((p & 255) * (1 - amount) + vb * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(FW, FH, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, FW, 0, 0, FW, FH);
        return out;
    }

    /** Box blur, `passes` times (three passes ≈ a gaussian); edges clamp. */
    static void blur(int[] px, int w, int h, int radius, int passes) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            boxLine(px, tmp, w, h, radius, true);
            boxLine(tmp, px, w, h, radius, false);
        }
    }

    private static void boxLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, step = horizontal ? 1 : w;
        float norm = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * w : line;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[start + Math.max(0, Math.min(len - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[start + i * step] = 0xFF000000 | (Math.round(sr * norm) << 16) | (Math.round(sg * norm) << 8) | Math.round(sb * norm);
                int add = in[start + Math.min(len - 1, i + r + 1) * step], drop = in[start + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((drop >> 16) & 255);
                sg += ((add >> 8) & 255) - ((drop >> 8) & 255);
                sb += (add & 255) - (drop & 255);
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
