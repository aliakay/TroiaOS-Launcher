package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Set Up's first screen: on black, "hello" is written by hand (assets/setup/hello.txt, from the Hershey Script font:
 * tools/make_hello.py), then the word in other languages follows, and "hello" is written again. TroiaOS's horse rests at
 * the bottom with "Press OK to set up" under it. The keys are SetupActivity's.
 */
final class Hello extends View implements Choreographer.FrameCallback {
    // The timeline, in seconds from start().
    private static final float WRITE_AT = 0.6f, WRITE_FOR = 2.4f, FADE_AT = 4.2f, FADE_FOR = 0.5f, WORDS_AT = 4.5f,
            WORD_FOR = 1.6f, CROSS = 0.4f, HORSE_AT = 1.2f, HINT_AT = 3.4f, BREATH = 2.4f;
    private static final int CREAM = 0xFFFFF4E6, PEACH = 0xFFFFC9A8;
    /** Languages (their codes) and their hello; the TV's own language moves to the front. */
    private static final String[][] WORDS = {{"tr", "Merhaba"}, {"es", "Hola"}, {"fr", "Bonjour"}, {"de", "Hallo"},
            {"it", "Ciao"}, {"pt", "Olá"}, {"sv da nb no", "Hej"}, {"ru", "Привет"}, {"ja", "こんにちは"}, {"ko", "안녕하세요"},
            {"zh", "你好"}, {"ar", "مرحبا"}};

    private final String[] words;
    private final float loop;               // one round: written, held, every language
    private final Path source;              // in the file's 1000 × 400 box
    private final Path drawn = new Path();  // the part written so far (refilled every frame)
    private final Matrix matrix = new Matrix();
    private PathMeasure[] strokes = new PathMeasure[0];
    private float[] lengths = new float[0];
    private float total;
    private Paint pen, word, hint;
    private boolean running;
    private long startedAt;
    private int w, h;
    private float k = 1;

    Hello(Context c) {
        super(c);
        source = SvgPath.parse(read(c, "setup/hello.txt"));
        String lang = Locale.getDefault().getLanguage();
        List<String> list = new ArrayList<>();
        for (String[] wd : WORDS) {
            if ((" " + wd[0] + " ").contains(" " + lang + " ")) list.add(0, wd[1]);
            else list.add(wd[1]);
        }
        words = list.toArray(new String[0]);
        loop = WORDS_AT + words.length * WORD_FOR + CROSS - WRITE_AT;
        setBackgroundColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        startedAt = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        // ~900 px wide, centered at y ≈ 470; one measure per stroke, written one after the other.
        float s = 0.9f * k, left = w / 2f - 500 * s;
        matrix.setScale(s, s);
        matrix.postTranslate(left, 470 * k - 200 * s);
        Path scaled = new Path();
        source.transform(matrix, scaled);
        List<PathMeasure> list = new ArrayList<>();
        List<Float> lens = new ArrayList<>();
        PathMeasure all = new PathMeasure(scaled, false);
        do {
            float len = all.getLength();
            if (len <= 0) continue;
            Path one = new Path();
            all.getSegment(0, len, one, true);
            list.add(new PathMeasure(one, false));
            lens.add(len);
        } while (all.nextContour());
        strokes = list.toArray(new PathMeasure[0]);
        lengths = new float[lens.size()];
        total = 0;
        for (int i = 0; i < lengths.length; i++) total += lengths[i] = lens.get(i);

        Shader warm = new LinearGradient(left, 0, left + 1000 * s, 0, CREAM, PEACH, Shader.TileMode.CLAMP);
        pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(16 * k);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        pen.setShader(warm);
        word = Ui.text(Ui.displaySemibold, 150 * k, 0xFFFFFFFF);
        word.setTextAlign(Paint.Align.CENTER);
        word.setShader(warm);
        hint = Ui.text(Ui.regular, 26 * k, 0xFFFFFFFF);
        hint.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0) return;
        float t = (SystemClock.uptimeMillis() - startedAt) / 1000f;
        if (t >= WRITE_AT) {
            float local = WRITE_AT + (t - WRITE_AT) % loop;
            drawHand(c, local);
            drawWords(c, local);
        }

        float horse = Ui.clamp((t - HORSE_AT) / 1f);
        if (horse > 0) Symbols.draw(getContext(), c, "horse", w / 2f, 900 * k, 96 * k, Ui.alpha(0xFFFFFFFF, 0.85f * horse));
        float shown = Ui.clamp((t - HINT_AT) / 0.8f);
        if (shown > 0) {
            float breath = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * (t - HINT_AT) / BREATH);
            hint.setAlpha(Math.round(255 * shown * (0.45f + 0.4f * breath)));
            c.drawText(Ui.tr("Kurmak için OK'e basın", "Press OK to set up"), w / 2f, 1012 * k, hint);
        }
    }

    /** "hello", written up to where the pen is, then fading. */
    private void drawHand(Canvas c, float local) {
        float alpha = 1 - Ui.clamp((local - FADE_AT) / FADE_FOR);
        if (alpha <= 0) return;
        float p = ease(Ui.clamp((local - WRITE_AT) / WRITE_FOR)), left = p * total;
        drawn.reset();
        for (int i = 0; i < strokes.length && left > 0; i++) {
            strokes[i].getSegment(0, Math.min(lengths[i], left), drawn, true);
            left -= lengths[i];
        }
        pen.setAlpha(Math.round(255 * alpha));
        c.drawPath(drawn, pen);
    }

    /** The other languages, one after the other: each rises a little into place, then on as the next comes. */
    private void drawWords(Canvas c, float local) {
        for (int i = 0; i < words.length; i++) {
            float at = WORDS_AT + i * WORD_FOR;
            if (local < at || local > at + WORD_FOR + CROSS) continue;
            float in = Ui.clamp((local - at) / CROSS), out = Ui.clamp((local - at - WORD_FOR) / CROSS);
            float rise = 12 * k * (1 - ease(in)) - 12 * k * ease(out);
            word.setAlpha(Math.round(255 * in * (1 - out)));
            c.drawText(words[i], w / 2f, 470 * k + Ui.halfCap(word) + rise, word);
        }
    }

    private static float ease(float x) {
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    private static String read(Context c, String asset) {
        try (InputStream in = c.getAssets().open(asset); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.w("hello: " + e.getMessage());
            return "";
        }
    }

    /** SVG path data with absolute M, L, C, Q and Z (all tools/make_hello.py writes). */
    static final class SvgPath {
        private SvgPath() {}

        static Path parse(String d) {
            Path p = new Path();
            float[] v = new float[6];
            char cmd = 'M';
            int i = 0, n = d.length();
            while (i < n) {
                char ch = d.charAt(i);
                if (Character.isWhitespace(ch) || ch == ',') {
                    i++;
                    continue;
                }
                if (Character.isLetter(ch)) {
                    cmd = ch;
                    i++;
                    if (cmd == 'Z' || cmd == 'z') p.close();
                    continue;
                }
                int count = cmd == 'C' ? 6 : cmd == 'Q' ? 4 : 2;
                for (int j = 0; j < count; j++) {
                    while (i < n && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) i++;
                    int from = i;
                    while (i < n && (Character.isDigit(d.charAt(i)) || d.charAt(i) == '.' || d.charAt(i) == '-'
                            || d.charAt(i) == 'e')) i++;
                    if (from == i) return p;     // malformed: what was read so far
                    v[j] = Float.parseFloat(d.substring(from, i));
                }
                switch (cmd) {
                    case 'M': p.moveTo(v[0], v[1]); cmd = 'L'; break;   // further pairs after M are lines
                    case 'L': p.lineTo(v[0], v[1]); break;
                    case 'C': p.cubicTo(v[0], v[1], v[2], v[3], v[4], v[5]); break;
                    case 'Q': p.quadTo(v[0], v[1], v[2], v[3]); break;
                    default: return p;
                }
            }
            return p;
        }
    }
}
