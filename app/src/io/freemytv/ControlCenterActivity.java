package io.freemytv;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control Center, like Apple's: one glass panel at the right with the time, the connection, round buttons (Live TV,
 * inputs, Wi-Fi, settings, sleep), what's playing (YouTube Music), the lights, the markets and the volume. The corner
 * of the home screen keeps a single button that opens it.
 */
public class ControlCenterActivity extends Activity {
    private CenterView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new CenterView(this);
        setContentView(view);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        Window win = getWindow();
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(64 * k);
        lp.y = Math.round(48 * k);
        lp.width = Math.round(CenterView.W * k);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.45f;
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

    static final class CenterView extends View implements Choreographer.FrameCallback, Stocks.Listener {
        static final float W = 620;
        private static final float PAD = 34, BTN_TOP = 160, BTN_D = 84, MEDIA_TOP = 312, MEDIA_H = 128, LIGHTS_TOP = 454,
                LIGHTS_H = 104, MARKETS_TOP = 572, MARKETS_H = 76, VOL_TOP = 662, VOL_H = 72, MAIN_H = 768, ROW_H = 66,
                TILE_R = 30;
        private static final int BUTTONS = 0, MEDIA = 1, LIGHTS = 2, MARKETS = 3, VOLUME = 4;
        private static final int LIVE = 0, INPUTS = 1, WIFI = 2, SETTINGS = 3, SLEEP = 4;
        /** Asked once per process: notification access (for what's playing), through the TV's own ADB. */
        private static boolean askedAccess;

        private final ControlCenterActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final LiquidGlass glass = new LiquidGlass();
        private final AudioManager audio;
        private final RectF panel = new RectF(), tmp = new RectF();
        private final Path clip = new Path();
        private final int[] onScreen = new int[2];
        private final Spring[] pop = new Spring[9];        // 0-4 buttons, then the media, lights, markets and volume rows
        private final Spring[] rowPop = new Spring[8];     // inputs page
        private final Spring[] press = new Spring[3];      // media: previous, play/pause, next (a short flash)
        private boolean inputsPage, running, okDown, okHeld;
        private int row = BUTTONS, col, inputIndex;
        private List<HomeApps.Item> inputs = new ArrayList<>();
        private List<Wiz.Lamp> lamps;           // null: still looking
        private int brightness = -1;            // waiting to be sent
        private MediaController media;
        private Bitmap art;
        private String mediaApp = "", title = "", artist = "";
        private long lastNanos;
        private int w;
        private float k = 1;
        private Paint time, date, label, text, dim, small, caption, fill, shadow, bitmapPaint;

        CenterView(ControlCenterActivity a) {
            super(a);
            activity = a;
            audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.3f, 0.85f, 0);
            for (int i = 0; i < rowPop.length; i++) rowPop[i] = new Spring(0.3f, 0.85f, 0);
            for (int i = 0; i < press.length; i++) press[i] = new Spring(0.35f, 0.9f, 0);
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            running = true;
            lastNanos = 0;
            final Context c = getContext().getApplicationContext();
            if (Wiz.known(c)) {
                io.execute(() -> {
                    final List<Wiz.Lamp> found = Wiz.discover(c, 900);
                    handler.post(() -> {
                        lamps = found;
                        invalidate();
                    });
                });
            } else {
                lamps = new ArrayList<>();
            }
            Stocks st = Stocks.get(c);
            st.addListener(this);
            st.refreshIfStale();
            if (MediaListener.allowed(c)) watchMedia();
            else askAccess();
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
            Stocks.get(getContext()).removeListener(this);
            unwatchMedia();
            if (brightness >= 0) handler.removeCallbacks(sendBrightness);
            if (brightness >= 0) sendBrightness.run();
        }

        @Override public void onStocks() {
            invalidate();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = false;
            for (Spring s : pop) moving |= !s.settled();
            for (Spring s : rowPop) moving |= !s.settled();
            for (Spring s : press) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            float kk = width / W;
            float h = inputsPage ? 168 + Math.max(1, inputs.size()) * ROW_H + 30 : MAIN_H;
            setMeasuredDimension(width, Math.round(h * kk));
        }

        @Override protected void onSizeChanged(int width, int height, int ow, int oh) {
            if (width == w) return;
            w = width;
            k = w / W;
            time = Ui.numbers(Ui.semibold, 64 * k, 0xFFFFFFFF);
            date = Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF);
            label = Ui.text(Ui.medium, 20 * k, 0xD9FFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            text = Ui.text(Ui.semibold, 26 * k, 0xF2FFFFFF);
            dim = Ui.text(Ui.regular, 21 * k, 0xA6FFFFFF);
            caption = Ui.text(Ui.semibold, 16 * k, 0x99FFFFFF);
            caption.setLetterSpacing(0.08f);
            small = Ui.numbers(Ui.semibold, 23 * k, 0xF2FFFFFF);
            small.setTextAlign(Paint.Align.RIGHT);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(16 * k, BlurMaskFilter.Blur.NORMAL));
            bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                boolean on = !inputsPage && (i < 5 ? row == BUTTONS && col == i : row == i - 4);
                pop[i].target = on ? 1 : 0;
                pop[i].step(dt);
            }
            for (int i = 0; i < rowPop.length; i++) {
                rowPop[i].target = inputsPage && inputIndex == i ? 1 : 0;
                rowPop[i].step(dt);
            }
            for (Spring s : press) s.step(dt);
            getLocationOnScreen(onScreen);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            panel.set(0, 0, getWidth(), getHeight());
            glass.draw(c, panel, 48 * k, 0xFF121214, 0.62f, dm.widthPixels, dm.heightPixels, onScreen[0], onScreen[1],
                    Wallpaper.get().hasPhoto() || HomeData.get(getContext()).isNight(System.currentTimeMillis()) ? 1 : 0, 1, 1);
            drawHeader(c);
            if (inputsPage) drawInputs(c);
            else {
                drawButtons(c);
                drawMedia(c);
                drawLights(c);
                drawMarkets(c);
                drawVolume(c);
            }
        }

        private void drawHeader(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k;
            long t = System.currentTimeMillis();
            c.drawText(Ui.clock(t), x - Ui.leftBearing(time, Ui.clock(t)), 92 * k, time);
            c.drawText(Ui.date(t), x, 128 * k, date);
            String net = HomeApps.network(getContext());
            String name = net.equals("ethernet") ? "Ethernet" : net.equals("wifi") ? "Wi-Fi" : Ui.tr("Bağlantı yok", "Offline");
            dim.setColor(0xA6FFFFFF);
            dim.setTextAlign(Paint.Align.RIGHT);
            c.drawText(name, right, 128 * k, dim);
            float nw = dim.measureText(name);
            dim.setTextAlign(Paint.Align.LEFT);
            Symbols.draw(getContext(), c, net, right - nw - 20 * k, 128 * k - Ui.halfCap(dim), 20 * k, 0xA6FFFFFF);
        }

        private static final String[] BUTTON_SYMBOLS = {"live_tv", "hdmi", "wifi", "settings", "bedtime"};

        private String buttonLabel(int i) {
            switch (i) {
                case LIVE: return Ui.tr("Canlı TV", "Live TV");
                case INPUTS: return Ui.tr("Girişler", "Inputs");
                case WIFI: return "Wi-Fi";
                case SETTINGS: return Ui.tr("Ayarlar", "Settings");
                default: return Ui.tr("Uyku", "Sleep");
            }
        }

        /** Round buttons, like Control Center's: smoked glass, white when focused. */
        private void drawButtons(Canvas c) {
            float step = (getWidth() - 2 * PAD * k) / 5f, d = BTN_D * k;
            for (int i = 0; i < 5; i++) {
                float f = Ui.clamp(pop[i].value), cx = PAD * k + step * (i + 0.5f), cy = BTN_TOP * k + d / 2, r = d / 2 * (1 + 0.08f * f);
                if (f > 0.01f) {
                    shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                    c.drawCircle(cx, cy + 6 * k, r, shadow);
                }
                fill.setColor(blend(0x29FFFFFF, 0xFFF5F5F7, f));
                c.drawCircle(cx, cy, r, fill);
                String symbol = i == WIFI && HomeApps.network(getContext()).equals("ethernet") && !HomeApps.guestWifi() ? "ethernet" : BUTTON_SYMBOLS[i];
                Symbols.draw(getContext(), c, symbol, cx, cy, 28 * k * (1 + 0.08f * f), blend(0xF2FFFFFF, 0xFF1C1C1E, f));
                label.setColor(blend(0xB3FFFFFF, 0xFFFFFFFF, f));
                c.drawText(buttonLabel(i), cx, cy + d / 2 + 36 * k, label);
            }
        }

        // MARK: - Now playing

        /**
         * What's playing, like Apple's Now Playing: the cover, the app, the song and the artist, ⏮ ⏯ ⏭. With nothing
         * playing it is YouTube Music's tile: OK opens it.
         */
        private void drawMedia(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, top = MEDIA_TOP * k, h = MEDIA_H * k;
            float f = Ui.clamp(pop[5].value);
            tile(c, x, top, right, top + h, f);
            // The cover (or YouTube Music's mark on its red).
            float s = 92 * k, ax = x + 18 * k, ay = top + (h - s) / 2;
            tmp.set(ax, ay, ax + s, ay + s);
            if (art != null) {
                clip.reset();
                clip.addRoundRect(tmp, 18 * k, 18 * k, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(art, null, tmp, bitmapPaint);
                c.restore();
            } else {
                boolean yt = media == null || MediaListener.YT_MUSIC.equals(media.getPackageName());
                fill.setShader(new LinearGradient(0, tmp.top, 0, tmp.bottom, yt ? 0xFFFF2D55 : 0xFF48484A, yt ? 0xFFD70022 : 0xFF2C2C2E,
                        Shader.TileMode.CLAMP));
                c.drawRoundRect(tmp, 18 * k, 18 * k, fill);
                fill.setShader(null);
                Symbols.draw(getContext(), c, yt ? "app_ytmusic" : "music_note", tmp.centerX(), tmp.centerY(), yt ? 50 * k : 40 * k, 0xFFFFFFFF);
            }
            // The controls at the right; the words fill what is left.
            boolean playing = media != null ? MediaListener.playing(media) : audio.isMusicActive();
            float cy = top + h / 2, gap = 58 * k, cNext = right - 40 * k, cPlay = cNext - gap, cPrev = cPlay - gap;
            int ink = ink(f, 0.95f);
            control(c, "skip_previous", cPrev, cy, 24, ink, press[0]);
            control(c, playing ? "pause" : "play_arrow", cPlay, cy, 32, ink, press[1]);
            control(c, "skip_next", cNext, cy, 24, ink, press[2]);
            float tx = ax + s + 20 * k, room = cPrev - 34 * k - tx;
            String app = media == null ? "YouTube Music" : mediaApp;
            String song = title.isEmpty() ? (media == null ? Ui.tr("Çalan bir şey yok", "Not playing") : app) : title;
            String sub = title.isEmpty() ? (media == null ? Ui.tr("OK ile aç", "Press OK to open") : "") : artist;
            caption.setColor(ink(f, 0.6f));
            c.drawText(fit(app.toUpperCase(Ui.en ? Locale.ROOT : Ui.TR), caption, room), tx, top + 40 * k, caption);
            text.setColor(ink(f, 0.95f));
            c.drawText(fit(song, text, room), tx, top + 76 * k, text);
            if (!sub.isEmpty()) {
                dim.setColor(ink(f, 0.62f));
                c.drawText(fit(sub, dim, room), tx, top + 106 * k, dim);
            }
        }

        private void control(Canvas c, String symbol, float cx, float cy, float size, int ink, Spring flash) {
            float p = Ui.clamp(flash.value);
            if (p > 0.01f) {
                fill.setColor(Ui.alpha(ink, 0.18f * p));
                c.drawCircle(cx, cy, 26 * k, fill);
            }
            Symbols.draw(getContext(), c, symbol, cx, cy, size * k * (1 - 0.1f * p), ink);
        }

        /** The text, cut with an ellipsis if wider than `room`. */
        private static String fit(String s, Paint p, float room) {
            if (s == null || p.measureText(s) <= room) return s == null ? "" : s;
            int n = p.breakText(s, true, Math.max(0, room - p.measureText("…")), null);
            return s.substring(0, Math.max(0, n)).trim() + "…";
        }

        private final MediaController.Callback mediaCallback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                invalidate();
            }

            @Override public void onMetadataChanged(MediaMetadata metadata) {
                readMetadata();
                invalidate();
            }

            @Override public void onSessionDestroyed() {
                pickMedia();
            }
        };

        private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = list -> pickMedia();

        private void watchMedia() {
            try {
                MediaSessionManager m = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
                m.addOnActiveSessionsChangedListener(sessionsListener, MediaListener.component(getContext()), handler);
            } catch (RuntimeException e) {
                AppLog.w("media: " + e.getMessage());
            }
            pickMedia();
        }

        private void unwatchMedia() {
            try {
                MediaSessionManager m = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
                m.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
            if (media != null) media.unregisterCallback(mediaCallback);
            media = null;   // so the next resume registers again
        }

        private void pickMedia() {
            MediaController next = MediaListener.pick(MediaListener.sessions(getContext()));
            if (media != null && (next == null || !media.getSessionToken().equals(next.getSessionToken()))) {
                media.unregisterCallback(mediaCallback);
            }
            boolean changed = next == null ? media != null : media == null || !media.getSessionToken().equals(next.getSessionToken());
            media = next;
            if (changed && media != null) media.registerCallback(mediaCallback, handler);
            readMetadata();
            invalidate();
        }

        private void readMetadata() {
            MediaMetadata m = media == null ? null : media.getMetadata();
            title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (artist.isEmpty()) artist = text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            Bitmap b = null;
            if (m != null) {
                b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            art = b;
            mediaApp = media == null ? "" : appName(media.getPackageName());
        }

        private static String text(MediaMetadata m, String key, String fallback) {
            if (m == null) return "";
            CharSequence s = m.getText(key);
            if (s == null || s.length() == 0) s = m.getText(fallback);
            return s == null ? "" : s.toString().trim();
        }

        private String appName(String pkg) {
            if (MediaListener.YT_MUSIC.equals(pkg)) return "YouTube Music";
            try {
                PackageManager pm = getContext().getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return pkg;
            }
        }

        /** Notification access, once, through the TV's own ADB (the key it already allowed for the home screen switch). */
        private void askAccess() {
            if (askedAccess) return;
            askedAccess = true;
            final Context c = getContext().getApplicationContext();
            io.execute(() -> {
                try (Adb adb = Adb.connect(c, 10_000, null)) {
                    adb.shell("cmd notification allow_listener " + MediaListener.component(c).flattenToString());
                } catch (Exception e) {
                    AppLog.w("media access: " + e.getMessage());
                }
                handler.post(() -> {
                    if (running && MediaListener.allowed(c)) watchMedia();
                });
            });
        }

        private void mediaKey(int which) {
            press[which].value = 1;
            press[which].target = 0;
            if (media != null) {
                MediaController.TransportControls t = media.getTransportControls();
                if (which == 0) t.skipToPrevious();
                else if (which == 2) t.skipToNext();
                else if (MediaListener.playing(media)) t.pause();
                else t.play();
            } else if (which != 1 || audio.isMusicActive()) {
                // No access (yet): the key goes to whichever app last played.
                int code = which == 0 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : which == 2 ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            } else {
                openMusicApp();
            }
            invalidate();
        }

        /** The playing app (its own screen for the song), or YouTube Music; the store page if it isn't installed. */
        private void openMusicApp() {
            if (media != null) {
                PendingIntent pi = media.getSessionActivity();
                if (pi != null) {
                    try {
                        pi.send();
                        activity.finish();
                        return;
                    } catch (PendingIntent.CanceledException ignored) {
                    }
                }
            }
            String pkg = media != null ? media.getPackageName() : MediaListener.YT_MUSIC;
            Intent launch = HomeApps.launchIntent(getContext().getPackageManager(), pkg);
            open(launch != null ? launch : new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        }

        // MARK: - Lights

        /** The lights: the bulb, on/off and the brightness bar. OK switches all, ← → dims, holding OK opens the panel. */
        private void drawLights(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, top = LIGHTS_TOP * k, h = LIGHTS_H * k;
            Boolean on = lamps == null ? null : Wiz.anyOn(lamps);
            boolean lit = Boolean.TRUE.equals(on);
            float f = Ui.clamp(pop[6].value);
            tile(c, x, top, right, top + h, f);
            float icx = x + 64 * k, icy = top + h / 2;
            fill.setColor(lit ? 0xFFFFC94D : blend(0x33FFFFFF, 0x1F1C1C1E, f));
            c.drawCircle(icx, icy, 32 * k, fill);
            Symbols.draw(getContext(), c, "lightbulb", icx, icy, 26 * k, lit ? 0xFF1C1C1E : ink(f, 0.95f));
            float tx = x + 118 * k;
            text.setColor(ink(f, 0.95f));
            c.drawText(Ui.tr("Işıklar", "Lights"), tx, top + 46 * k, text);
            String status;
            if (lamps == null) status = Ui.tr("Aranıyor…", "Looking…");
            else if (on == null) status = Ui.tr("Lamba yok", "No lamps");
            else status = lit ? Ui.tr("Açık", "On") : Ui.tr("Kapalı", "Off");
            dim.setColor(ink(f, 0.62f));
            c.drawText(status, tx, top + 80 * k, dim);
            if (on == null) return;
            // Brightness: a bar like the volume's, with the percentage.
            int level = lit ? maxDimming() : 0;
            float bl = x + 268 * k, br = right - 86 * k, bh = 10 * k, cy = top + h / 2;
            tmp.set(bl, cy - bh / 2, br, cy + bh / 2);
            fill.setColor(blend(0x33FFFFFF, 0x261C1C1E, f));
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            if (level > 0) {
                tmp.right = bl + (br - bl) * level / 100f;
                fill.setColor(f > 0.5f ? 0xFFE0A100 : 0xFFFFC94D);
                c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            }
            small.setColor(ink(f, lit ? 0.95f : 0.5f));
            c.drawText(lit ? (Ui.en ? level + "%" : "%" + level) : "—", right - 24 * k, cy + Ui.halfCap(small), small);
        }

        private int maxDimming() {
            int m = 0;
            if (lamps != null) for (Wiz.Lamp l : lamps) if (l.on) m = Math.max(m, l.dimming);
            return m;
        }

        /** ← → on the lights: 10% a step (from off, → turns them on at 10%); sent once the presses pause. */
        private void dim(int dx) {
            if (lamps == null || lamps.isEmpty()) return;
            boolean lit = Boolean.TRUE.equals(Wiz.anyOn(lamps));
            if (!lit && dx < 0) return;
            int next = Math.max(10, Math.min(100, (lit ? Math.round(maxDimming() / 10f) * 10 : 0) + 10 * dx));
            for (Wiz.Lamp l : lamps) {
                l.on = true;
                l.dimming = next;
            }
            brightness = next;
            handler.removeCallbacks(sendBrightness);
            handler.postDelayed(sendBrightness, 220);
            invalidate();
        }

        private final Runnable sendBrightness = new Runnable() {
            @Override public void run() {
                if (brightness < 0 || lamps == null) return;
                final List<Wiz.Lamp> targets = new ArrayList<>(lamps);
                final int b = brightness;
                brightness = -1;
                io.execute(() -> Wiz.set(targets, Wiz.params("state", true, "dimming", b)));
            }
        };

        private void toggleLights() {
            final Context c = getContext().getApplicationContext();
            io.execute(() -> {
                Wiz.toggleAll(c);
                final List<Wiz.Lamp> found = Wiz.discover(c, 900);
                handler.post(() -> {
                    lamps = found;
                    invalidate();
                });
            });
        }

        // MARK: - Markets and volume

        /** The first three of the watchlist with the day's change; OK opens the whole list. */
        private void drawMarkets(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, top = MARKETS_TOP * k, h = MARKETS_H * k, cy = top + h / 2;
            float f = Ui.clamp(pop[7].value);
            tile(c, x, top, right, top + h, f);
            Symbols.draw(getContext(), c, "chart", x + 40 * k, cy, 22 * k, ink(f, 0.9f));
            Stocks st = Stocks.get(getContext());
            float left = x + 76 * k, colW = (right - left) / 3f;
            text.setColor(ink(f, 0.95f));
            if (st.quotes.isEmpty()) {
                c.drawText(Ui.tr("Piyasalar", "Markets"), left, cy + Ui.halfCap(text), text);
                return;
            }
            synchronized (st.quotes) {
                for (int i = 0; i < 3 && i < st.quotes.size(); i++) {
                    Stocks.Quote q = st.quotes.get(i);
                    float cx = left + colW * i;
                    String name = q.label.equals("SPX") ? "S&P" : q.label;
                    dim.setColor(ink(f, 0.7f));
                    c.drawText(name, cx, cy + Ui.halfCap(dim), dim);
                    double ch = q.change();
                    small.setColor(Double.isNaN(ch) ? ink(f, 0.6f) : ch >= 0 ? (f > 0.5f ? 0xFF248A3D : 0xFF30D158) : (f > 0.5f ? 0xFFD70015 : 0xFFFF453A));
                    small.setTextAlign(Paint.Align.LEFT);
                    c.drawText(Double.isNaN(ch) ? "—" : Stocks.change(ch), cx + dim.measureText(name) + 10 * k, cy + Ui.halfCap(small), small);
                    small.setTextAlign(Paint.Align.RIGHT);
                }
            }
        }

        /** The volume: ← → down and up, OK mutes. */
        private void drawVolume(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k, top = VOL_TOP * k, h = VOL_H * k;
            float f = Ui.clamp(pop[8].value);
            tile(c, x, top, right, top + h, f);
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
            float cy = top + h / 2;
            Symbols.draw(getContext(), c, "speaker", x + 40 * k, cy, 24 * k, ink(f, muted ? 0.4f : 0.9f));
            float bl = x + 76 * k, br = right - 76 * k, bh = 10 * k;
            tmp.set(bl, cy - bh / 2, br, cy + bh / 2);
            fill.setColor(blend(0x33FFFFFF, 0x261C1C1E, f));
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            tmp.right = bl + (br - bl) * (muted ? 0 : vol / (float) max);
            fill.setColor(blend(0xF2FFFFFF, 0xFF1C1C1E, f));
            c.drawRoundRect(tmp, bh / 2, bh / 2, fill);
            small.setColor(ink(f, 0.95f));
            c.drawText(muted ? "—" : String.valueOf(vol), right - 24 * k, cy + Ui.halfCap(small), small);
        }

        private void drawInputs(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k;
            dim.setColor(0xA6FFFFFF);
            c.drawText(Ui.tr("GİRİŞLER", "INPUTS"), x, 186 * k, dim);
            for (int i = 0; i < inputs.size() && i < rowPop.length; i++) {
                HomeApps.Item it = inputs.get(i);
                float top = (204 + i * ROW_H) * k, f = Ui.clamp(rowPop[i].value);
                tile(c, x, top, right, top + (ROW_H - 10) * k, f);
                float cy = top + (ROW_H - 10) * k / 2;
                boolean off = it.kind == HomeApps.INPUT && !it.connected;
                Symbols.draw(getContext(), c, it.symbol, x + 40 * k, cy, 24 * k, ink(f, off ? 0.4f : 0.9f));
                text.setColor(ink(f, off ? 0.45f : 0.95f));
                c.drawText(it.label, x + 78 * k, cy + Ui.halfCap(text), text);
            }
        }

        /** A rounded tile: smoked by default, white when focused. */
        private void tile(Canvas c, float l, float t, float r, float b, float f) {
            float s = 1 + 0.02f * f, cx = (l + r) / 2, cy = (t + b) / 2, hw = (r - l) / 2 * s, hh = (b - t) / 2 * s;
            float rad = Math.min(TILE_R * k, hh);
            tmp.set(cx - hw, cy - hh, cx + hw, cy + hh);
            if (f > 0.01f) {
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                tmp.offset(0, 6 * k);
                c.drawRoundRect(tmp, rad, rad, shadow);
                tmp.offset(0, -6 * k);
            }
            fill.setColor(blend(0x1FFFFFFF, 0xFFF5F5F7, f));
            c.drawRoundRect(tmp, rad, rad, fill);
        }

        private static int ink(float focus, float alpha) {
            return blend(Ui.alpha(0xFFFFFFFF, alpha), Ui.alpha(0xFF1C1C1E, Math.max(alpha, 0.6f)), focus);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        // MARK: - Keys

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) move(0, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) {
                            okDown = true;
                            okHeld = false;
                        } else if (okDown && !okHeld && (e.isLongPress() || e.getEventTime() - e.getDownTime() >= 380)
                                && !inputsPage && (row == LIGHTS || row == MEDIA)) {
                            okHeld = true;
                            // Held: the full lights panel, or the music app.
                            if (row == LIGHTS) open(new Intent().setComponent(new ComponentName(activity, LightsActivity.class)));
                            else openMusicApp();
                        }
                    } else {
                        if (okDown && !okHeld && !e.isCanceled()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (inputsPage) {
                        if (!down) {
                            inputsPage = false;
                            requestLayout();
                            invalidate();
                        }
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        private void move(int dx, int dy) {
            if (inputsPage) {
                inputIndex = Math.max(0, Math.min(inputs.size() - 1, inputIndex + dy));
                invalidate();
                return;
            }
            if (dy != 0) {
                row = Math.max(BUTTONS, Math.min(VOLUME, row + dy));
            } else if (row == BUTTONS) {
                col = Math.max(0, Math.min(4, col + dx));
            } else if (row == MEDIA) {
                mediaKey(dx > 0 ? 2 : 0);
            } else if (row == LIGHTS) {
                dim(dx);
            } else if (row == VOLUME) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dx > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            }
            invalidate();
        }

        private void activate() {
            Context c = getContext();
            if (inputsPage) {
                if (inputIndex < inputs.size()) {
                    HomeApps.Item it = inputs.get(inputIndex);
                    if (HomeApps.open(activity, it, this, 0, 0, getWidth(), getHeight())) activity.finish();
                }
                return;
            }
            switch (row) {
                case BUTTONS:
                    switch (col) {
                        case LIVE: {
                            Intent live = HomeApps.liveTv(c);
                            if (live != null) open(live);
                            break;
                        }
                        case INPUTS:
                            inputs = HomeApps.allInputs(c);
                            inputIndex = 0;
                            inputsPage = true;
                            requestLayout();
                            break;
                        case WIFI:
                            open(HomeApps.guestWifi()
                                    ? new Intent().setComponent(new ComponentName(activity, InfoActivity.class)).putExtra(InfoActivity.EXTRA, InfoActivity.WIFI)
                                    : new Intent(Settings.ACTION_WIFI_SETTINGS));
                            break;
                        case SETTINGS:
                            open(new Intent().setComponent(new ComponentName(activity, SettingsActivity.class)));
                            break;
                        default:
                            sleep();
                    }
                    break;
                case MEDIA:
                    mediaKey(1);
                    break;
                case LIGHTS:
                    toggleLights();
                    break;
                case MARKETS:
                    open(new Intent().setComponent(new ComponentName(activity, InfoActivity.class)).putExtra(InfoActivity.EXTRA, InfoActivity.MARKETS));
                    break;
                default:
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            }
            invalidate();
        }

        private void open(Intent intent) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.w("control center: " + e.getMessage());
            }
            activity.finish();
        }

        /** Sleep: the TV's own sleep key, through its own ADB (the panel closes first). */
        private void sleep() {
            final Context c = getContext().getApplicationContext();
            activity.finish();
            new Thread(() -> {
                try (Adb adb = Adb.connect(c, 10_000, null)) {
                    adb.shell("input keyevent KEYCODE_SLEEP");
                } catch (Exception e) {
                    AppLog.w("sleep: " + e.getMessage());
                }
            }).start();
        }
    }
}
