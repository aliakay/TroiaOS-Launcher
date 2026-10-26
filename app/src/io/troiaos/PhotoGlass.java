package io.troiaos;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;

/**
 * Liquid Glass for Android 13+ (AGSL), drawn over the wallpaper photo. The inside of the glass is almost clear and
 * flat; the rim is a thick bezel (a convex lens): the photo behind it bends strongly, its red and blue channels part
 * a little, and a bright edge catches the light at the top left with an echo on the opposite side. Android 11 and 12
 * never create this class: they keep LiquidGlass. Where it can't draw (no photo yet, a shader error, switched off in
 * Customize, a TV that is too slow) `draw` returns false and the caller draws LiquidGlass instead.
 */
final class PhotoGlass {
    /** Customize → Glass is "Automatic" on Android 13+. */
    static boolean wanted() {
        return Build.VERSION.SDK_INT >= 33 && "auto".equals(Prefs.glass());
    }

    // The same math as the design preview. The inside reads 2 textures, the bezel up to 4.
    private static final String AGSL =
        "uniform shader sharp;\n" +
        "uniform shader frost;\n" +
        "uniform float2 sharpScale;\n" +
        "uniform float2 frostScale;\n" +
        "uniform float2 offset;\n" +          // layer offset on screen (a moved layer)
        "uniform float2 center;\n" +
        "uniform float2 halfSize;\n" +
        "uniform float radius;\n" +
        "uniform float band;\n" +             // width of the bezel (px)
        "uniform float refr;\n" +             // how far the bezel bends the photo (px)
        "uniform half4 tint;\n" +
        "uniform float lift;\n" +
        "uniform float spec;\n" +
        "uniform float alpha;\n" +
        "float hash(float2 p) {\n" +
        "  p = fract(p * float2(123.34, 456.21));\n" +
        "  p += dot(p, p + 45.32);\n" +
        "  return fract(p.x * p.y);\n" +
        "}\n" +
        "half4 main(float2 xy) {\n" +
        "  float2 d = xy - center;\n" +
        "  float2 q = abs(d) - (halfSize - radius);\n" +
        "  float sd = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n" +
        "  float aa = clamp(0.5 - sd, 0.0, 1.0) * alpha;\n" +
        "  if (aa <= 0.0) { return half4(0.0); }\n" +
        "  float2 sg = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);\n" +
        "  float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) * sg : (q.x > q.y ? float2(sg.x, 0.0) : float2(0.0, sg.y));\n" +
        "  float inner = -sd;\n" +
        "  float t = 1.0 - clamp(inner / band, 0.0, 1.0);\n" +
        "  float lens = 1.0 - sqrt(max(1.0 - t * t, 0.0));\n" +    // flat inside, steep at the rim
        "  float shift = lens * refr;\n" +
        "  float2 scr = center + offset;\n" +
        "  float2 p = scr + (xy + offset - scr) / 1.02 - n * shift;\n" +
        "  half3 s;\n" +
        "  if (shift > 0.5) {\n" +
        "    float2 ca = n * (shift * 0.35);\n" +
        "    s = half3(sharp.eval((p + ca) * sharpScale).r, sharp.eval(p * sharpScale).g, sharp.eval((p - ca) * sharpScale).b);\n" +
        "  } else {\n" +
        "    s = sharp.eval(p * sharpScale).rgb;\n" +
        "  }\n" +
        "  half3 f = frost.eval(p * frostScale).rgb;\n" +
        "  half3 bg = mix(s, f, half(0.3 * (1.0 - 0.75 * t)));\n" +
        "  half l = dot(bg, half3(0.2126, 0.7152, 0.0722));\n" +
        "  bg = mix(half3(l), bg, 1.35) * 1.05;\n" +
        "  half3 col = mix(bg, tint.rgb, tint.a) + half3(lift + 0.04);\n" +
        "  float ny = d.y / halfSize.y;\n" +
        "  col += half3(0.07 * (1.0 - smoothstep(-1.0, 0.1, ny)));\n" +
        "  float2 L = float2(-0.4, -0.917);\n" +
        "  float facing = pow(clamp(dot(n, L), 0.0, 1.0), 1.4);\n" +
        "  float back = pow(clamp(-dot(n, L), 0.0, 1.0), 1.4);\n" +
        "  col += half3(0.2 * t * t * t * (0.35 + 0.65 * max(facing, back)));\n" +
        "  float rim = 1.0 - smoothstep(0.0, 2.2, inner);\n" +
        "  float halo = 1.0 - smoothstep(0.0, 15.0, inner);\n" +
        "  float sp = rim * (0.25 + 1.15 * facing + 0.65 * back) + halo * (0.36 * facing + 0.18 * back);\n" +
        "  col += half3(sp * spec);\n" +
        "  col += half3((hash(xy) - 0.5) * 0.0039);\n" +
        "  return half4(col * half(aa), half(aa));\n" +
        "}\n";

    private static volatile boolean failed;

    private final RuntimeShader shader = new RuntimeShader(AGSL);
    private final Paint paint = new Paint();
    private Bitmap sharpSrc, frostSrc;

    PhotoGlass() {
        paint.setShader(shader);
    }

    /**
     * Glass in `r` (canvas coordinates; (ox, oy) is where the canvas's origin is on screen). tintAlpha is the strength
     * the shelf asks for; the glass is much clearer than the flat one, so it uses less of it. Returns false when
     * LiquidGlass has to draw it.
     */
    boolean draw(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float specular, float rim,
                 float alpha, int screenW, int screenH, float ox, float oy) {
        Wallpaper wp = Wallpaper.get();
        Bitmap sharp = wp.photoSharp, frost = wp.photoFrost;
        if (failed || sharp == null || frost == null || !wanted()) return false;
        try {
            if (sharp != sharpSrc) {
                sharpSrc = sharp;
                shader.setInputShader("sharp", linear(sharp));
            }
            if (frost != frostSrc) {
                frostSrc = frost;
                shader.setInputShader("frost", linear(frost));
            }
            float k = screenW / 1920f;
            float band = Math.max(4, Math.min(Math.min(r.width(), r.height()) * 0.36f, 52 * k));
            shader.setFloatUniform("sharpScale", (float) sharp.getWidth() / screenW, (float) sharp.getHeight() / screenH);
            shader.setFloatUniform("frostScale", (float) frost.getWidth() / screenW, (float) frost.getHeight() / screenH);
            shader.setFloatUniform("offset", ox, oy);
            shader.setFloatUniform("center", r.centerX(), r.centerY());
            shader.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
            shader.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
            shader.setFloatUniform("band", band);
            shader.setFloatUniform("refr", band * 1.3f);
            shader.setFloatUniform("tint", ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f,
                    Math.min(0.6f, tintAlpha * 0.45f));
            shader.setFloatUniform("lift", lift);
            shader.setFloatUniform("spec", specular * 1.6f * rim);
            shader.setFloatUniform("alpha", Math.min(1, alpha));
            c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, paint);
            return true;
        } catch (RuntimeException | LinkageError e) {
            AppLog.w("photo glass off: " + e.getMessage());
            failed = true;
            return false;
        }
    }

    /** Whether `draw` would draw now. */
    boolean ready() {
        Wallpaper wp = Wallpaper.get();
        return !failed && wanted() && wp.photoSharp != null && wp.photoFrost != null;
    }

    /** The focus lens in the dock (a clear glass pill over the photo). Same false as `draw` when LiquidGlass must do it. */
    boolean lens(Canvas c, RectF r, float radius, float alpha, int screenW, int screenH, float ox, float oy) {
        return draw(c, r, radius, 0xFFFFFFFF, 0.22f, 0f, 0.8f, 1f, alpha, screenW, screenH, ox, oy);
    }

    private static BitmapShader linear(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return s;
    }

    // MARK: - Slow TVs

    // If the home screen can't hold its frame rate with this glass, go back to LiquidGlass for good (until Customize
    // → Glass is switched again): two windows of 240 frames in a row where half of them took over 25 ms.
    private static final Window.OnFrameMetricsAvailableListener[] LISTENER = new Window.OnFrameMetricsAvailableListener[1];
    private static Window watched;

    static void watch(final Activity a) {
        if (!wanted() || failed || watched != null) return;
        final Window w = a.getWindow();
        final long start = System.nanoTime();
        LISTENER[0] = new Window.OnFrameMetricsAvailableListener() {
            private int frames, slow, bad;

            @Override
            public void onFrameMetricsAvailable(Window window, FrameMetrics m, int dropped) {
                if (System.nanoTime() - start < 6_000_000_000L) return;      // the start-up is allowed to hitch
                frames++;
                if (m.getMetric(FrameMetrics.TOTAL_DURATION) > 25_000_000L) slow++;
                if (frames < 240) return;
                bad = slow * 2 >= frames ? bad + 1 : 0;
                frames = slow = 0;
                if (bad >= 2) {
                    AppLog.w("photo glass: frames too slow, back to the classic glass");
                    Prefs.put(a, "glass", "slow");
                    unwatch();
                }
            }
        };
        w.addOnFrameMetricsAvailableListener(LISTENER[0], new Handler(Looper.getMainLooper()));
        watched = w;
    }

    static void unwatch() {
        if (watched != null && LISTENER[0] != null) watched.removeOnFrameMetricsAvailableListener(LISTENER[0]);
        watched = null;
    }
}
