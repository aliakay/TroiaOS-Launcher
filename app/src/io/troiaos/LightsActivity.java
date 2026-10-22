package io.troiaos;

import android.app.Activity;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The lights panel (any brand, through Lights): a small window under the home screen's bulb (hold OK on the bulb, or "Lights" in the bottom
 * drawer), the home screen still visible behind it. At the top, what to control: All, Lamp 1, Lamp 2… (← →); below,
 * on/off (OK), brightness (← →) and color (← →) of that choice. Everything goes over the home network (Wiz) on a
 * background thread; ← → changes are sent once the keys rest. Back closes it.
 */
public class LightsActivity extends Activity {
    private LightsView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new LightsView(this);
        setContentView(view);
        // A panel at the top right, under the corner buttons; the home screen dimmed a little behind it.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        Window win = getWindow();
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(70 * k);
        lp.y = Math.round(196 * k);
        lp.width = Math.round(LightsView.W * k);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.3f;
        win.setAttributes(lp);
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /** A color choice: white at a temperature (K), a WiZ scene, or a color (r, g, b); `swatch` is how it looks here. */
    private static final class Preset {
        final String tr, en;
        final int temp, scene, rgb, swatch;

        Preset(String tr, String en, int temp, int scene, int rgb, int swatch) {
            this.tr = tr;
            this.en = en;
            this.temp = temp;
            this.scene = scene;
            this.rgb = rgb;
            this.swatch = swatch;
        }

        String name() { return Ui.tr(tr, en); }
    }

    private static final Preset[] PRESETS = {
        new Preset("Sıcak beyaz", "Warm white", 2700, 0, 0, 0xFFFFC27A),
        new Preset("Doğal beyaz", "Neutral white", 4000, 0, 0, 0xFFFFE6C8),
        new Preset("Gün ışığı", "Daylight", 6500, 0, 0, 0xFFE8F0FF),
        new Preset("Kırmızı", "Red", 0, 0, 0xFF0000, 0xFFFF453A),
        new Preset("Turuncu", "Orange", 0, 0, 0xFF6A00, 0xFFFF9F0A),
        new Preset("Sarı", "Yellow", 0, 0, 0xFFC800, 0xFFFFD60A),
        new Preset("Yeşil", "Green", 0, 0, 0x00FF00, 0xFF30D158),
        new Preset("Turkuaz", "Turquoise", 0, 0, 0x00C8C8, 0xFF40C8E0),
        new Preset("Mavi", "Blue", 0, 0, 0x0000FF, 0xFF0A84FF),
        new Preset("Mor", "Purple", 0, 0, 0x8C00FF, 0xFFBF5AF2),
        new Preset("Pembe", "Pink", 0, 0, 0xFF0090, 0xFFFF375F),
    };

    /**
     * A scene: WiZ's own (sceneId) on WiZ lamps; on other brands, its stand-in (a white in K, or a color), with its
     * icon and color here.
     */
    private static final class Mood {
        final String tr, en, symbol;
        final int id, color, temp, rgb;

        Mood(String tr, String en, int id, String symbol, int color, int temp, int rgb) {
            this.tr = tr;
            this.en = en;
            this.id = id;
            this.symbol = symbol;
            this.color = color;
            this.temp = temp;
            this.rgb = rgb;
        }

        String name() { return Ui.tr(tr, en); }
    }

    private static final Mood[] MOODS = {
        new Mood("Gün ışığı", "Daylight", 12, "sunny", 0xFFFFC300, 5500, -1),
        new Mood("Rahat", "Cozy", 6, "sofa", 0xFFFF9F0A, 2700, -1),
        new Mood("TV zamanı", "TV time", 18, "tv", 0xFF8E7CF8, 0, 0x5A3CFF),
        new Mood("Odak", "Focus", 15, "focus", 0xFF32ADE6, 4500, -1),
        new Mood("Rahatla", "Relax", 16, "leaf", 0xFF30D158, 0, 0x7CD45A),
        new Mood("Mum ışığı", "Candlelight", 29, "candle", 0xFFFFB347, 2200, -1),
        new Mood("Şömine", "Fireplace", 5, "flame", 0xFFFF6B35, 0, 0xFF5A1E),
        new Mood("Gece ışığı", "Night light", 14, "bedtime", 0xFFBF5AF2, 0, 0x8C3CFF),
    };

    private static Mood mood(int id) {
        for (Mood m : MOODS) if (m.id == id) return m;
        return null;
    }

    static final class LightsView extends View implements Choreographer.FrameCallback {
        /** Panel width and layout, in the 1920 × 1080 space. */
        static final float W = 600;
        private static final float PAD = 30, CHIPS_TOP = 30, CHIP_H = 52, POWER_TOP = 104, POWER_D = 84,
                SCENE_LABEL = 238, SCENE_TOP = 254, SCENE_H = 92, BAR_LABEL = 500, BAR_TOP = 516, BAR_H = 48,
                COLOR_LABEL = 608, SWATCH_CY = 648, SWATCH_D = 34, HEIGHT = 692, EMPTY_HEIGHT = 250;
        private static final int TARGET = 0, POWER = 1, SCENES_A = 2, SCENES_B = 3, BRIGHTNESS = 4, COLOR = 5, ROWS = 6;
        private static final int INK = 0xFF1C1C1E, AMBER = 0xFFFFC266;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Spring[] pop = new Spring[ROWS];
        private final RectF tmp = new RectF(), panel = new RectF();
        private final LiquidGlass glass = new LiquidGlass();
        private final int[] onScreen = new int[2];
        private List<Lights.Lamp> lamps = new ArrayList<>();
        /** 0 = all lamps, 1… = that lamp. */
        private int target;
        private boolean searching = true, running, okDown, sendQueued, colorChanged;
        private int focus = POWER, brightness = 70, preset;
        /** The WiZ scene the lamps are in (0 = none: a color or a white), and the scene card in focus (0 to 3). */
        private int scene, sceneCol;
        private String problem = "";
        private long lastNanos;
        private int w;
        private float k = 1;
        private Paint title, status, label, chip, chipOn, percent, warn, fill, ring, shadow, sceneName;
        /** Light glass with dark text by day; smoked glass with white text at night. */
        private boolean light;
        private int soft, softer, iconOff, barFill, barText, swatchRing;

        /** Brightness or color changed with ← →: sent once the keys rest (holding a key doesn't flood the lamps). */
        private final Runnable send = new Runnable() {
            @Override public void run() {
                sendQueued = false;
                List<Lights.Lamp> targets = targets();
                if (targets.isEmpty()) return;
                Lights.Pilot params = colorChanged ? presetParams(PRESETS[preset], brightness) : Lights.Pilot.level(brightness);
                if (colorChanged) scene = 0;
                colorChanged = false;
                for (Lights.Lamp l : targets) {
                    l.on = true;
                    l.dimming = brightness;
                    if (scene == 0) l.scene = 0;
                }
                command(targets, params);
            }
        };

        LightsView(LightsActivity a) {
            super(a);
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.3f, 0.85f, 0);
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            running = true;
            lastNanos = 0;
            search();
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            if (sendQueued) {      // a change still waiting: send it now
                handler.removeCallbacks(send);
                send.run();
            }
        }

        private void search() {
            searching = true;
            io.execute(() -> {
                final List<Lights.Lamp> found = Lights.discover(getContext(), 1500);
                handler.post(() -> {
                    boolean resize = found.isEmpty() != lamps.isEmpty();
                    searching = false;
                    lamps = found;
                    target = Math.min(target, lamps.size());
                    readState();
                    if (resize) requestLayout();
                    invalidate();
                });
            });
        }

        /** The lamps the controls act on: all of them, or the chosen one. */
        private List<Lights.Lamp> targets() {
            if (target == 0 || target > lamps.size()) return new ArrayList<>(lamps);
            List<Lights.Lamp> one = new ArrayList<>();
            one.add(lamps.get(target - 1));
            return one;
        }

        /** Brightness and color shown: the chosen lamp's, or (All) those of the first lamp that is on. */
        private void readState() {
            List<Lights.Lamp> list = targets();
            Lights.Lamp ref = null;
            for (Lights.Lamp l : list) if (l.on) { ref = l; break; }
            if (ref == null && !list.isEmpty()) ref = list.get(0);
            if (ref == null) return;
            brightness = Math.max(10, Math.min(100, Math.round(ref.dimming / 10f) * 10));
            preset = nearestPreset(ref);
            scene = mood(ref.scene) != null ? ref.scene : 0;
        }

        private static int nearestPreset(Lights.Lamp l) {
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < PRESETS.length; i++) {
                Preset p = PRESETS[i];
                double d;
                if (l.scene != 0) d = p.scene == l.scene ? 0 : Double.MAX_VALUE;
                else if (l.temp > 0) d = p.temp > 0 ? Math.abs(p.temp - l.temp) : Double.MAX_VALUE;
                else if (l.r >= 0 && p.scene == 0 && p.temp == 0) {
                    int dr = ((p.rgb >> 16) & 255) - l.r, dg = ((p.rgb >> 8) & 255) - l.g, db = (p.rgb & 255) - l.b;
                    d = dr * dr + dg * dg + db * db;
                } else d = Double.MAX_VALUE;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = i;
                }
            }
            return best;
        }

        private static Lights.Pilot presetParams(Preset p, int dimming) {
            Lights.Pilot pilot = Lights.Pilot.level(dimming);
            pilot.scene = p.scene;
            if (p.temp != 0) pilot.temp = p.temp;
            else pilot.rgb = p.rgb;
            return pilot;
        }

        /** Sends in the background; if a lamp doesn't confirm, a notice and a new search (to show the real state). */
        private void command(final List<Lights.Lamp> targets, final Lights.Pilot params) {
            problem = "";
            invalidate();
            io.execute(() -> {
                final boolean ok = Lights.set(getContext(), targets, params);
                handler.post(() -> {
                    if (!ok) {
                        problem = Ui.tr("Bir lamba cevap vermedi.", "A lamp didn't answer.");
                        search();
                    }
                    invalidate();
                });
            });
        }

        private boolean targetOn() {
            for (Lights.Lamp l : targets()) if (l.on) return true;
            return false;
        }

        private String chipLabel(int i) {
            if (i == 0) return Ui.tr("Tümü", "All");
            if (lamps.size() > 3) return String.valueOf(i);
            String n = lamps.get(i - 1).name();
            return n.length() > 12 ? n.substring(0, 11).trim() + "…" : n;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            float kk = width / W;
            boolean empty = lamps.isEmpty();
            setMeasuredDimension(width, Math.round((empty ? EMPTY_HEIGHT : HEIGHT) * kk + (problem.isEmpty() ? 0 : 0)));
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = searching;
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            if (width == w) return;
            w = width;
            k = w / W;
            title = Ui.text(Ui.semibold, 30 * k, 0xF2FFFFFF);
            status = Ui.text(Ui.regular, 23 * k, 0x99FFFFFF);
            label = Ui.text(Ui.semibold, 18 * k, 0x80FFFFFF);
            label.setLetterSpacing(0.08f);
            chip = Ui.text(Ui.medium, 23 * k, 0xD9FFFFFF);
            chip.setTextAlign(Paint.Align.CENTER);
            chipOn = Ui.text(Ui.semibold, 23 * k, INK);
            chipOn.setTextAlign(Paint.Align.CENTER);
            percent = Ui.numbers(Ui.semibold, 22 * k, INK);
            sceneName = Ui.text(Ui.medium, 21 * k, 0xF2FFFFFF);
            warn = Ui.text(Ui.regular, 21 * k, AMBER);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(14 * k, BlurMaskFilter.Blur.NORMAL));
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && !lamps.isEmpty() ? 1 : 0;
                pop[i].step(dt);
            }
            // The panel: smoked Liquid Glass over the wallpaper that is really behind it on screen.
            getLocationOnScreen(onScreen);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float night = HomeData.get(getContext()).isNight(System.currentTimeMillis()) || Wallpaper.get().hasPhoto() ? 1 : 0;   // dark glass on photos
            applyColors(night < 0.5f);
            panel.set(0, 0, getWidth(), getHeight());
            glass.draw(c, panel, 36 * k, light ? 0xFFFFFFFF : 0xFF121212, light ? 0.62f : 0.5f, dm.widthPixels, dm.heightPixels,
                    onScreen[0], onScreen[1], night, 1, 1);

            float x = PAD * k;
            if (lamps.isEmpty()) {
                Symbols.draw(getContext(), c, "lightbulb", x + 20 * k, 70 * k, 40 * k, 0x80FFFFFF);
                c.drawText(Ui.tr("Işıklar", "Lights"), x + 56 * k, 70 * k + Ui.halfCap(title), title);
                if (searching) {
                    c.drawText(Ui.tr("Lambalar aranıyor…", "Looking for lamps…"), x, 140 * k, status);
                } else {
                    c.drawText(Ui.tr("Lamba bulunamadı. OK: tekrar ara", "No lamps found. OK: search again"), x, 140 * k, status);
                    c.drawText(Ui.tr("Kurulum: Özelleştir → Işıklar", "Set up in Customize → Lights"), x, 180 * k, status);
                }
                return;
            }
            drawChips(c);
            drawPower(c);
            drawScenes(c);
            drawBrightness(c);
            drawColors(c);
            if (!problem.isEmpty()) c.drawText(problem, x, (HEIGHT - 12) * k, warn);
        }

        private void applyColors(boolean day) {
            light = day;
            title.setColor(day ? 0xFF1C1C1E : 0xF2FFFFFF);
            status.setColor(day ? 0xFF6E6E73 : 0x99FFFFFF);
            label.setColor(day ? 0x991C1C1E : 0x80FFFFFF);
            chip.setColor(day ? 0xD91C1C1E : 0xD9FFFFFF);
            warn.setColor(day ? 0xFFC93400 : AMBER);
            soft = day ? 0x14000000 : 0x1FFFFFFF;        // unchosen tabs, the focus pill
            softer = day ? 0x1F000000 : 0x33FFFFFF;      // the bulb button when off, the bar's track
            iconOff = day ? 0xCC1C1C1E : 0xE6FFFFFF;
            barFill = day ? 0xE61C1C1E : 0xF2F5F5F7;
            barText = day ? 0xFFFFFFFF : INK;
            swatchRing = day ? 0xE61C1C1E : 0xF2FFFFFF;
        }

        /** Focus highlight behind a row: a soft pill, like the drawer's focus. */
        private void highlight(Canvas c, int row, float top, float bottom) {
            float f = Ui.clamp(pop[row].value);
            if (f <= 0.01f) return;
            tmp.set((PAD - 14) * k, top, getWidth() - (PAD - 14) * k, bottom);
            fill.setColor(Ui.alpha(soft, f));
            c.drawRoundRect(tmp, 20 * k, 20 * k, fill);
        }

        /** All · Lamp 1 · Lamp 2…: the chosen one white, like the tvOS tab bar. */
        private void drawChips(Canvas c) {
            int n = lamps.size() + 1;
            float gap = 10 * k, top = CHIPS_TOP * k, ch = CHIP_H * k;
            highlight(c, TARGET, top - 8 * k, top + ch + 8 * k);
            float[] widths = new float[n];
            float total = gap * (n - 1);
            for (int i = 0; i < n; i++) {
                widths[i] = chipOn.measureText(chipLabel(i)) + 44 * k;
                total += widths[i];
            }
            float room = getWidth() - 2 * PAD * k, scale = total > room ? room / total : 1;
            float x = PAD * k;
            for (int i = 0; i < n; i++) {
                float cw = widths[i] * scale;
                tmp.set(x, top, x + cw, top + ch);
                boolean chosen = i == target;
                fill.setColor(chosen ? (light ? 0xFF1C1C1E : 0xFFF5F5F7) : soft);
                c.drawRoundRect(tmp, ch / 2, ch / 2, fill);
                chipOn.setColor(light ? 0xFFFFFFFF : INK);
                Paint p = chosen ? chipOn : chip;
                c.drawText(chipLabel(i), tmp.centerX(), tmp.centerY() + Ui.halfCap(p), p);
                x += cw + gap;
            }
        }

        /** The round bulb button (in the lights' color when on) and the state of the choice. */
        private void drawPower(Canvas c) {
            float top = POWER_TOP * k, d = POWER_D * k, cx = PAD * k + d / 2, cy = top + d / 2;
            highlight(c, POWER, top - 10 * k, top + d + 10 * k);
            boolean on = targetOn();
            float f = Ui.clamp(pop[POWER].value), r = d / 2 * (1 + 0.06f * f);
            if (f > 0.01f) {
                shadow.setColor(Ui.alpha(0xFF000000, (light ? 0.16f : 0.5f) * f));
                c.drawCircle(cx, cy + 6 * k, r, shadow);
            }
            Mood active = mood(scene);
            fill.setColor(!on ? softer : active != null ? active.color : PRESETS[preset].swatch);
            c.drawCircle(cx, cy, r, fill);
            Symbols.draw(getContext(), c, "lightbulb", cx, cy, 34 * k, on ? INK : iconOff);
            float tx = cx + d / 2 + 24 * k;
            String name = target == 0 ? Ui.tr("Tüm ışıklar", "All lights") : lamps.get(target - 1).name();
            c.drawText(name, tx, cy - 6 * k, title);
            String state = searching && lamps.isEmpty() ? "" : on
                    ? Ui.tr("Açık · ", "On · ") + (active != null ? active.name() : PRESETS[preset].name()) + " · "
                            + (Ui.en ? brightness + "%" : "%" + brightness)
                    : Ui.tr("Kapalı · OK ile aç", "Off · OK to turn on");
            c.drawText(state, tx, cy + 30 * k, status);
        }

        private void drawBrightness(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, top = BAR_TOP * k, bh = BAR_H * k;
            highlight(c, BRIGHTNESS, (BAR_LABEL - 32) * k, top + bh + 10 * k);
            c.drawText(Ui.tr("PARLAKLIK", "BRIGHTNESS"), x, BAR_LABEL * k, label);
            tmp.set(x, top, right, top + bh);
            fill.setColor(softer);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            tmp.right = x + (right - x) * brightness / 100f;
            fill.setColor(barFill);
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            percent.setColor(barText);
            String text = Ui.en ? brightness + "%" : "%" + brightness;
            c.drawText(text, x + 20 * k, top + bh / 2 + Ui.halfCap(percent), percent);
        }

        /** WiZ's scenes: two rows of four cards (icon in the scene's color, name); the one in use has a colored rim. */
        private void drawScenes(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, gap = 10 * k;
            c.drawText(Ui.tr("SAHNELER", "SCENES"), x, SCENE_LABEL * k, label);
            float cw = (right - x - 3 * gap) / 4, ch = SCENE_H * k, radius = 20 * k;
            boolean on = targetOn();
            for (int i = 0; i < MOODS.length; i++) {
                Mood m = MOODS[i];
                int row = i / 4, col = i % 4;
                float top = (SCENE_TOP + row * (SCENE_H + 10)) * k, left = x + col * (cw + gap);
                float f = sceneCol == col ? Ui.clamp(pop[SCENES_A + row].value) : 0;
                float grow = 1 + 0.04f * f;
                tmp.set(left + cw / 2 - cw * grow / 2, top + ch / 2 - ch * grow / 2, left + cw / 2 + cw * grow / 2, top + ch / 2 + ch * grow / 2);
                if (f > 0.01f) {
                    shadow.setColor(Ui.alpha(0xFF000000, (light ? 0.16f : 0.45f) * f));
                    tmp.offset(0, 6 * k);
                    c.drawRoundRect(tmp, radius, radius, shadow);
                    tmp.offset(0, -6 * k);
                }
                fill.setColor(soft);
                c.drawRoundRect(tmp, radius, radius, fill);
                if (f > 0.01f) {
                    fill.setColor(Ui.alpha(light ? 0xFF1C1C1E : 0xFFF5F5F7, f));
                    c.drawRoundRect(tmp, radius, radius, fill);
                }
                if (on && m.id == scene) {
                    ring.setColor(m.color);
                    ring.setStrokeWidth(3 * k);
                    c.drawRoundRect(tmp, radius, radius, ring);
                }
                float icx = tmp.left + 34 * k, icy = tmp.top + 32 * k;
                fill.setColor(Ui.alpha(m.color, 0.24f));
                c.drawCircle(icx, icy, 20 * k, fill);
                Symbols.draw(getContext(), c, m.symbol, icx, icy, 20 * k, m.color);
                sceneName.setColor(f > 0.5f ? (light ? 0xFFFFFFFF : INK) : title.getColor());
                c.drawText(m.name(), tmp.left + 16 * k, tmp.bottom - 16 * k, sceneName);
            }
        }

        private void drawColors(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, cy = SWATCH_CY * k, d = SWATCH_D * k;
            highlight(c, COLOR, (COLOR_LABEL - 32) * k, cy + d / 2 + 14 * k);
            c.drawText(Ui.tr("RENK", "COLOR"), x, COLOR_LABEL * k, label);
            label.setTextAlign(Paint.Align.RIGHT);
            c.drawText(PRESETS[preset].name().toUpperCase(Ui.en ? java.util.Locale.UK : Ui.TR), right, COLOR_LABEL * k, label);
            label.setTextAlign(Paint.Align.LEFT);
            int n = PRESETS.length;
            float step = (right - x - d) / (n - 1);
            for (int i = 0; i < n; i++) {
                float sx = x + d / 2 + step * i;
                boolean chosen = i == preset && scene == 0;
                fill.setColor(PRESETS[i].swatch);
                c.drawCircle(sx, cy, d / 2 * (chosen ? 1.08f : 0.86f), fill);
                if (chosen) {
                    ring.setColor(swatchRing);
                    ring.setStrokeWidth(3 * k);
                    c.drawCircle(sx, cy, d / 2 + 6 * k, ring);
                }
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0 && !lamps.isEmpty()) focus--;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < ROWS - 1 && !lamps.isEmpty()) focus++;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && !lamps.isEmpty()) adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // Only a press that started here (the OK held on the home screen's bulb ends here as a release).
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) activate();
                        okDown = false;
                    }
                    return true;
                default:
                    return false;    // Back closes the panel
            }
        }

        private void adjust(int dir) {
            if (focus == TARGET) {
                int next = Math.max(0, Math.min(lamps.size(), target + dir));
                if (next == target) return;
                if (sendQueued) {      // a change for the previous choice still waiting: send it first
                    handler.removeCallbacks(send);
                    send.run();
                }
                target = next;
                readState();
                invalidate();
                return;
            }
            if (focus == SCENES_A || focus == SCENES_B) {
                sceneCol = Math.max(0, Math.min(3, sceneCol + dir));
                invalidate();
                return;
            }
            if (focus == BRIGHTNESS) {
                int next = Math.max(10, Math.min(100, brightness + 10 * dir));
                if (next == brightness) return;
                brightness = next;
            } else if (focus == COLOR) {
                preset = (preset + dir + PRESETS.length) % PRESETS.length;
                colorChanged = true;
            } else {
                return;
            }
            handler.removeCallbacks(send);
            handler.postDelayed(send, 250);
            sendQueued = true;
            invalidate();
        }

        private void activate() {
            if (lamps.isEmpty()) {
                if (!searching) search();
                invalidate();
                return;
            }
            if (focus == SCENES_A || focus == SCENES_B) {
                Mood m = MOODS[(focus - SCENES_A) * 4 + sceneCol];
                List<Lights.Lamp> list = targets();
                for (Lights.Lamp l : list) {
                    l.on = true;
                    l.scene = m.id;
                }
                scene = m.id;
                Lights.Pilot p = Lights.Pilot.level(brightness);
                p.scene = m.id;
                p.temp = m.temp;
                p.rgb = m.rgb;
                command(list, p);
            } else if (focus == POWER || focus == TARGET) {
                boolean next = !targetOn();
                List<Lights.Lamp> list = targets();
                for (Lights.Lamp l : list) l.on = next;
                command(list, Lights.Pilot.power(next));
            } else {
                // OK on brightness or color: the choice right away (and the lights on).
                colorChanged |= focus == COLOR;
                handler.removeCallbacks(send);
                send.run();
            }
            invalidate();
        }
    }
}
