package io.freemytv;

import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid Glass without AGSL (Android 12): inside the shape, the blurred wallpaper that is really behind it (Wallpaper
 * frost, at its place on screen), magnified a little around the glass's center like a lens; a tint; light falling on
 * the top; and a rim that catches the light at the top left and bottom right. All plain Canvas drawing, with
 * anti-aliased edges.
 */
final class LiquidGlass {
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF edge = new RectF();
    private BitmapShader dayShader, nightShader, photoShader;
    private android.graphics.Bitmap photoFrost;

    LiquidGlass() {
        rim.setStyle(Paint.Style.STROKE);
    }

    /**
     * Glass in `r` (canvas coordinates). screenW/H: the screen size; (ox, oy): where the canvas's origin is on screen
     * (a moved layer, or a small window); night: 0 = day wallpaper, 1 = night; body = 0: only the rim; alpha: opacity.
     */
    /** How bright the rim is (1: the usual; the dock asks for a softer one). */
    float rimStrength = 1;

    void draw(Canvas c, RectF r, float radius, int tint, float tintAlpha, int screenW, int screenH, float ox, float oy,
              float night, float body, float alpha) {
        if (alpha <= 0.004f) return;
        radius = Math.min(radius, Math.min(r.width(), r.height()) / 2);
        float k = screenW / 1920f;
        if (body > 0) {
            Wallpaper wp = Wallpaper.get();
            if (dayShader == null) {
                dayShader = new BitmapShader(wp.dayFrost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                nightShader = new BitmapShader(wp.nightFrost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            // The frost that is behind the glass, magnified 6% around its center (the lens).
            matrix.setScale((float) screenW / Wallpaper.FW, (float) screenH / Wallpaper.FH);
            matrix.postTranslate(-ox, -oy);
            matrix.postScale(1.06f, 1.06f, r.centerX(), r.centerY());
            android.graphics.Bitmap photo = wp.photoFrost;
            if (photo != null) {
                if (photo != photoFrost) {
                    photoFrost = photo;
                    photoShader = new BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
                layer(c, r, radius, photoShader, alpha * body);
            } else {
                if (night < 0.999f) layer(c, r, radius, dayShader, alpha * body);
                if (night > 0.001f) layer(c, r, radius, nightShader, alpha * body * night);
            }
            fill.setShader(null);
            fill.setColor(Ui.alpha(tint | 0xFF000000, tintAlpha * alpha * body));
            c.drawRoundRect(r, radius, radius, fill);
            // Light on the upper part of the glass.
            light.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.55f,
                    Ui.alpha(0xFFFFFFFF, 0.16f * alpha * body), 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, radius, radius, light);
        }
        // The rim: bright where the light hits (top left, a little at the bottom right), thin elsewhere.
        float stroke = Math.max(1, 1.8f * k);
        edge.set(r);
        edge.inset(stroke / 2, stroke / 2);
        rim.setStrokeWidth(stroke);
        rim.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {Ui.alpha(0xFFFFFFFF, 0.85f * rimStrength * alpha), Ui.alpha(0xFFFFFFFF, 0.10f * rimStrength * alpha),
                        Ui.alpha(0xFFFFFFFF, 0.42f * rimStrength * alpha)},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(edge, radius, radius, rim);
    }

    private void layer(Canvas c, RectF r, float radius, BitmapShader shader, float alpha) {
        shader.setLocalMatrix(matrix);
        frostPaint.setShader(shader);
        frostPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRoundRect(r, radius, radius, frostPaint);
    }

    /**
     * The wallpaper behind everything, the canvas being the whole screen: the photo (with a soft shade at the top and
     * the bottom, so the white text on it reads), or the clouds (day and night blended).
     */
    static void background(Canvas c, int screenW, int screenH, float night, Paint paint) {
        Wallpaper wp = Wallpaper.get();
        RectF dst = new RectF(0, 0, screenW, screenH);
        android.graphics.Bitmap photo = wp.photo;
        if (photo != null) {
            c.drawBitmap(photo, null, dst, paint);
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, screenH * 0.42f, 0x80000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, screenW, screenH * 0.42f, shade);
            shade.setShader(new LinearGradient(0, screenH * 0.55f, 0, screenH, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
            c.drawRect(0, screenH * 0.55f, screenW, screenH, shade);
            return;
        }
        int alpha = paint.getAlpha();
        if (night < 0.999f) c.drawBitmap(wp.day, null, dst, paint);
        if (night > 0.001f) {
            paint.setAlpha(Math.round(alpha * night));
            c.drawBitmap(wp.night, null, dst, paint);
            paint.setAlpha(alpha);
        }
    }
}
