package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Set Up: the first time Toria opens, "hello" is written by hand (Hello), then the remote walks the person through
 * the language, their name, city, apps, wallpaper, lights, guest Wi-Fi, music app, markets, privacy and making Toria the
 * home screen. Every choice is saved at once with the keys Customize uses, so leaving half-way keeps it; the step
 * reached is kept too (`setup_step`), and opening Toria again resumes there.
 *
 * Opened by Settings (the app's icon) and the home screen while Set Up was never finished nor put off, and by "Set Up
 * Again" in Settings and Customize (from Hello, with the current values).
 */
public class SetupActivity extends Activity {
    static final String EXTRA_AGAIN = "again", EXTRA_FIRST = "first";

    private Hello hello;
    private SetupView steps;
    private boolean onHello, okDown, backDown, resumed;

    /** "Set Up Again": from the Hello screen. */
    static void again(Context c) {
        c.startActivity(new Intent(c, SetupActivity.class).putExtra(EXTRA_AGAIN, true));
    }

    /** Set Up was never finished: where it was left. `fromSettings`: "Later" opens the settings. */
    static void resume(Context c, boolean fromSettings) {
        Intent i = new Intent(c, SetupActivity.class).putExtra(EXTRA_FIRST, fromSettings);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
        hello = new Hello(this);
        steps = new SetupView(this);
        int step = getIntent().getBooleanExtra(EXTRA_AGAIN, false) ? 0 : Prefs.setupStep();
        if (step <= 0) showHello(); else showSteps(step);
    }

    /** Opened again while open (singleTask): "Set Up Again" starts over; otherwise it stays where it is. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_AGAIN, false) && !onHello) showHello();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (onHello) hello.start(); else steps.resume();
    }

    @Override protected void onPause() {
        resumed = false;
        hello.stop();
        steps.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!onHello) {
            if (steps.onKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) showSteps(SetupView.LANGUAGE);
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) later();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    void showHello() {
        onHello = true;
        okDown = backDown = false;
        steps.pause();
        setContentView(hello);
        Prefs.put(this, "setup_step", 0);
        if (resumed) hello.start();
    }

    void showSteps(int step) {
        onHello = false;
        hello.stop();
        setContentView(steps);
        steps.go(step);
        if (resumed) steps.resume();
    }

    /** Back on Hello: "Set up later?" (never asked again after Later; Settings → Set Up Again any time). */
    private void later() {
        if (Prefs.setupDone(this)) {     // Set Up Again: just close
            finish();
            return;
        }
        Dialogs.choice(this, Ui.tr("Kurulum sonra mı?", "Set up later?"),
                Ui.tr("Toria'yı istediğiniz zaman kurabilirsiniz: Ayarlar → Kurulumu Yeniden Başlat.",
                        "You can set up Toria any time: Settings → Set Up Again."),
                Ui.tr("Sonra", "Later"), () -> {
                    Prefs.put(this, "setup_skipped", true);
                    if (getIntent().getBooleanExtra(EXTRA_FIRST, false)) startActivity(new Intent(this, SettingsActivity.class));
                    finish();
                },
                Ui.tr("Devam Et", "Continue"), () -> showSteps(SetupView.LANGUAGE), null);
    }

    /** The last step's OK: done for good, and to the home screen. */
    void finishSetup() {
        Prefs.put(this, "setup_done", true);
        Prefs.put(this, "setup_step", null);
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    static final class SetupView extends View implements Choreographer.FrameCallback, Photos.Listener {
        static final int LANGUAGE = 1, NAME = 2, CITY = 3, APPS = 4, WALLPAPER = 5, LIGHTS = 6, WIFI = 7, MUSIC = 8,
                MARKETS = 9, PRIVACY = 10, HOME = 11, DONE = 12;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, LIST_BOTTOM = 1000, ROW_H = 74, ROW_GAP = 8,
                HEADER_H = 58, NOTE_H = 44;
        private static final int INK = 0xFF1C1C1E, AMBER = 0xFFFFC266;
        private static final String WATCHLIST = "^GSPC:S&P,BTC-USD:BTC,GC=F:XAU";

        /** One line of a step: a choice (focusable), a header (capitals) or a note (a sentence); neither of the last two takes focus. */
        private static final class Row {
            final String id, label;
            String value = "", symbol;
            boolean header, note, checked, chevron, dim, spinner;
            float top;

            Row(String id, String label) {
                this.id = id;
                this.label = label;
            }

            boolean focusable() {
                return !header && !note;
            }
        }

        private final SetupActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final List<Row> rows = new ArrayList<>();
        private final List<Spring> pop = new ArrayList<>();
        private final Spring scroll = new Spring(0.35f, 1f, 0);
        private final RectF tmp = new RectF(), rowRect = new RectF();
        private int step = LANGUAGE, focus;
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, lastSky;
        private int w, h;
        private float k = 1, contentH;

        // Step data.
        private final List<String[]> places = new ArrayList<>();   // name, detail, latitude, longitude
        private String placesState = "";                           // "", "searching", "none", "error"
        private final List<String> dock = new ArrayList<>(), appOrder = new ArrayList<>();
        private final Map<String, String> appLabels = new HashMap<>();
        private List<Lights.Lamp> lamps = new ArrayList<>();
        private String lightsState = "";                            // "", "searching"
        private boolean[][] qr;
        private JSONObject state = new JSONObject();                // HomeMode's, while on the home screen step
        private boolean switching;                                  // asked for Toria from here, not done yet
        private long pendingAt;

        private final Paint veil = new Paint();
        private Paint title, subtitle, rowLabel, rowValue, header, note, hint, white, shadow, dot, qrDark, spinner;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running || step != HOME) return;
                readState();
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SetupView(SetupActivity a) {
            super(a);
            activity = a;
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            running = true;
            Photos photos = Photos.get(getContext());
            photos.addListener(this);
            photos.refresh();       // the person's wallpaper behind the steps (in a fresh process, nothing has loaded it yet)
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            Prefs.load(getContext());
            if (step == HOME) {
                readState();
                handler.post(poll);
            }
            rebuild();
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            if (!running) return;
            running = false;
            Photos photos = Photos.get(getContext());
            photos.removeListener(this);
            handler.removeCallbacks(poll);
            handler.removeCallbacks(previewFocused);
            if (step == WALLPAPER) photos.refresh();     // a preview never outlives the step
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean busy = !scroll.settled() || (step == HOME && busy());
            for (Spring s : pop) busy |= !s.settled();
            if (busy || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override public void onPhoto() {
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            note = Ui.text(Ui.regular, 24 * k, 0xB3FFFFFF);
            hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            hint.setTextAlign(Paint.Align.CENTER);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            qrDark = new Paint();
            qrDark.setColor(0xFF000000);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            snapScroll();
        }

        // MARK: - Steps

        /** Opens a step (and saves it as the one to resume at). */
        void go(int to) {
            Context c = getContext();
            if (step == WALLPAPER && to != WALLPAPER) {
                handler.removeCallbacks(previewFocused);
                Photos.get(c).refresh();
            }
            handler.removeCallbacks(poll);
            step = Math.max(LANGUAGE, Math.min(DONE, to));
            if (step == HOME && freeActive()) step = DONE;   // Toria is the home screen already
            focus = 0;
            Prefs.put(c, "setup_step", step);
            switch (step) {
                case CITY:
                    places.clear();
                    placesState = "";
                    break;
                case APPS:
                    loadApps();
                    break;
                case LIGHTS:
                    searchLamps();
                    break;
                case HOME:
                    readState();
                    if (running) handler.post(poll);
                    break;
                default:
            }
            rebuild();
            focusSelected();
            snapScroll();
            invalidate();
        }

        private void forward() {
            int to = step + 1;
            if (to == HOME && freeActive()) to++;
            if (to > DONE) return;
            go(to);
        }

        private void backward() {
            int to = step - 1;
            if (to == HOME && freeActive()) to--;
            if (to < LANGUAGE) activity.showHello();
            else go(to);
        }

        /** On a step with a choice, focus starts on the chosen one. */
        private void focusSelected() {
            if (step == PRIVACY || step == DONE) {     // nothing to choose: the row that goes on
                focusOn(step == DONE ? "done" : "next");
                return;
            }
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).checked && step != APPS) {
                    focus = i;
                    return;
                }
            }
            focus = next(-1, 1);
        }

        private void focusOn(String id) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) focus = i;
        }

        private void rebuild() {
            rows.clear();
            Context c = getContext();
            switch (step) {
                case LANGUAGE: {
                    item("tr", "Türkçe", "home").checked = !Ui.en;
                    item("en", "English", "home").checked = Ui.en;
                    nextRow(false);
                    break;
                }
                case NAME: {
                    Row r = item("name", Ui.tr("Adınız", "Your Name"), "sparkles");
                    r.value = Prefs.name().isEmpty() ? Ui.tr("Yazmak için OK", "OK to type it") : Prefs.name();
                    r.chevron = true;
                    note(Prefs.name().isEmpty() ? Ui.tr("Boş bırakırsanız yalnızca “Merhaba” yazar.", "Left empty, the top just says “Hello”.")
                            : Ui.tr("Ekranın üstünde: “Merhaba " + Prefs.name() + "”", "At the top of the screen: “Hello " + Prefs.name() + "”"));
                    nextRow(!Prefs.name().isEmpty());
                    break;
                }
                case CITY: {
                    Row r = item("search", Ui.tr("Şehrinizi Arayın", "Search for Your City"), "pin");
                    r.value = Prefs.city().isEmpty() ? "" : Prefs.city();
                    r.chevron = true;
                    if (placesState.equals("searching")) headerRow(Ui.tr("ARANIYOR…", "SEARCHING…"));
                    else if (placesState.equals("none")) headerRow(Ui.tr("BULUNAMADI · BAŞKA BİR AD DENEYİN", "NOTHING FOUND · TRY ANOTHER NAME"));
                    else if (placesState.equals("error")) {
                        headerRow(Ui.tr("İNTERNET BAĞLANTISI YOK", "NO INTERNET CONNECTION"));
                        note(Ui.tr("Bu adımı atlayıp şehri sonra Özelleştir'den seçebilirsiniz.", "Skip this step and pick the city later in Customize."));
                    }
                    for (int i = 0; i < places.size(); i++) {
                        Row p = item("place" + i, places.get(i)[0], "pin");
                        p.value = places.get(i)[1];
                    }
                    headerRow(Ui.tr("SICAKLIK", "TEMPERATURE"));
                    String t = Prefs.temperature().toUpperCase(Locale.ROOT);
                    Row u = item("temperature", Ui.tr("Birim", "Unit"), "sunny");
                    u.value = t.startsWith("F") ? "°F" : t.startsWith("C") ? "°C" : Ui.tr("Otomatik", "Automatic");
                    headerRow("");
                    nextRow(!Prefs.city().isEmpty());
                    break;
                }
                case APPS: {
                    boolean full = dock.size() >= HomeApps.MAX_SHELF;
                    headerRow(full ? Ui.tr("ANA EKRAN DOLU · ÖNCE BİRİNİ ÇIKARIN", "HOME IS FULL · REMOVE ONE FIRST")
                            : Ui.tr("ANA EKRANDA OLANLAR İŞARETLİ", "THE ONES ON HOME ARE TICKED"));
                    for (String pkg : appOrder) {
                        Row r = item("app:" + pkg, appLabels.get(pkg), appSymbol(pkg));
                        r.checked = dock.contains(pkg);
                        r.dim = !r.checked && full;
                    }
                    headerRow(Ui.tr("GÖRÜNÜM", "LAYOUT"));
                    boolean list = Prefs.layout().equals("list");
                    Row d = item("layout:dock", "Dock", "apps");
                    d.value = Ui.tr("Altta cam bir şerit", "A glass bar at the bottom");
                    d.checked = !list;
                    Row l = item("layout:list", Ui.tr("Liste", "List"), "apps");
                    l.value = Ui.tr("Fotoğrafın üstünde büyük adlar", "Big names over the photo");
                    l.checked = list;
                    headerRow("");
                    nextRow(false);
                    break;
                }
                case WALLPAPER: {
                    String mode = Prefs.wallpaper();
                    for (String m : Prefs.WALLPAPERS) item(m, wallpaperName(m), wallpaperSymbol(m)).checked = m.equals(mode);
                    headerRow("");
                    nextRow(false);
                    break;
                }
                case LIGHTS: {
                    if (lightsState.equals("searching")) headerRow(Ui.tr("LAMBALAR ARANIYOR…", "LOOKING FOR LAMPS…"));
                    else headerRow(lamps.isEmpty() ? Ui.tr("LAMBA BULUNAMADI", "NO LAMPS FOUND")
                            : Ui.tr("BULUNANLAR · OK: YANIP SÖNSÜN", "FOUND · OK: BLINK IT"));
                    for (Lights.Lamp l : lamps) item("lamp:" + l.key(), l.name(), "lightbulb").value = Lights.brandName(l.brand);
                    item("search", Ui.tr("Yeniden Ara", "Search Again"), "sync").dim = lightsState.equals("searching");
                    note(Ui.tr("WiZ, LIFX ve Govee kendiliğinden bulunur.", "WiZ, LIFX and Govee are found by themselves."));
                    note(Ui.tr("Govee: Govee uygulamasında “LAN Control” açık olmalı.", "Govee: turn on “LAN Control” in the Govee app."));
                    note(Ui.tr("Hue ve Home Assistant: sonra Özelleştir → Işıklar.", "Hue and Home Assistant: later, in Customize → Lights."));
                    headerRow("");
                    nextRow(!lamps.isEmpty());
                    break;
                }
                case WIFI: {
                    String ssid = Prefs.wifiSsid(), pass = Prefs.wifiPassword(), sec = Prefs.wifiSecurity();
                    Row r = item("ssid", Ui.tr("Ağ Adı", "Network Name"), "wifi");
                    r.value = ssid.isEmpty() ? Ui.tr("Ayarlanmadı", "Not set") : ssid;
                    r = item("password", Ui.tr("Şifre", "Password"), "settings");
                    r.value = sec.equals("nopass") ? Ui.tr("Yok (açık ağ)", "None (open network)")
                            : pass.isEmpty() ? Ui.tr("Ayarlanmadı", "Not set") : "••••••••";
                    r.dim = sec.equals("nopass");
                    r = item("security", Ui.tr("Güvenlik", "Security"), "check");
                    r.value = sec.equals("nopass") ? Ui.tr("Yok", "None") : sec.equals("WEP") ? "WEP" : "WPA / WPA2 / WPA3";
                    note(Ui.tr("Yalnızca bu TV'de, şifreli saklanır; hiçbir yere gönderilmez.", "Kept on this TV only, encrypted; never sent anywhere."));
                    headerRow("");
                    nextRow(!ssid.isEmpty());
                    qr = ssid.isEmpty() ? null : QrCode.encode(QrCode.wifi(ssid, pass, sec));
                    break;
                }
                case MUSIC: {
                    String chosen = Prefs.musicApp();
                    for (HomeApps.Item it : CustomizeActivity.CustomizeView.musicApps(c)) {
                        item(it.pkg, it.label, appSymbol(it.pkg)).checked = it.pkg.equals(chosen);
                    }
                    headerRow("");
                    nextRow(false);
                    break;
                }
                case MARKETS: {
                    String s = Prefs.stocks();
                    item("none", Ui.tr("Yok", "None"), "chart").checked = s.isEmpty();
                    Row b = item("basic", "S&P 500, Bitcoin, " + Ui.tr("Altın", "Gold"), "chart");
                    b.checked = s.equals(WATCHLIST);
                    Row own = item("own", Ui.tr("Kendim Yazayım", "Type My Own"), "edit");
                    own.checked = !s.isEmpty() && !b.checked;
                    if (own.checked) own.value = s.replace(",", ", ");
                    note(Ui.tr("Yahoo Finance sembolleri, örn. ^GSPC:S&P, BTC-USD:BTC, AAPL", "Yahoo Finance symbols, e.g. ^GSPC:S&P, BTC-USD:BTC, AAPL"));
                    headerRow("");
                    nextRow(!s.isEmpty());
                    break;
                }
                case PRIVACY: {
                    headerRow(Ui.tr("TORIA HİÇBİR VERİ TOPLAMAZ", "TORIA COLLECTS NOTHING"));
                    info(Ui.tr("Hesap, reklam, takip yok", "No account, no ads, no tracking"), Ui.tr("Sunucumuz da yok", "And no servers of ours"), "check");
                    info(Ui.tr("Hava durumu, şehir arama", "Weather, city search"), Ui.tr("Open-Meteo, yalnızca HTTPS", "Open-Meteo, HTTPS only"), "sunny");
                    info(Ui.tr("Piyasalar", "Markets"), Ui.tr("Yahoo Finance: sembolleriniz", "Yahoo Finance: your symbols"), "chart");
                    info(Ui.tr("NASA fotoğrafı", "NASA picture"), Ui.tr("Yalnızca seçerseniz", "Only if you choose it"), "sparkles");
                    info(Ui.tr("Işıklar, şifreler", "Lights, passwords"), Ui.tr("Evde ve bu TV'de kalır", "Stay at home, on this TV"), "lightbulb");
                    info(Ui.tr("Çalan müzik", "Now Playing"), Ui.tr("TV'de okunur, gönderilmez", "Read on the TV, never sent"), "music_note");
                    headerRow("");
                    item("next", Ui.tr("Anladım", "Got It"), "check").chevron = true;
                    break;
                }
                case HOME: {
                    note(Ui.tr("Toria TV'nin ana ekranı ve ekran koruyucusu olur.", "Toria becomes the TV's home screen and screensaver."));
                    note(Ui.tr("Birkaç reklam uygulaması kapatılır, asla kaldırılmaz.", "A few promo apps are turned off, never uninstalled."));
                    note(Ui.tr("Ayarlar → Ana Ekran → Orijinal her şeyi geri getirir.", "Settings → Home Screen → Original brings it all back."));
                    headerRow("");
                    boolean working = busy();
                    String error = state.optString("error");
                    Row s = item("switch", working ? Ui.tr("Geçiliyor", "Switching")
                            : error.isEmpty() ? Ui.tr("Şimdi Geç", "Switch Now") : Ui.tr("Yeniden Dene", "Try Again"), "tv");
                    s.spinner = working;
                    s.dim = working;
                    if (working) {
                        String b = state.optString("busy");
                        s.value = b.isEmpty() ? Ui.tr("Başlıyor…", "Starting…") : b;
                    }
                    item("later", Ui.tr("Sonra", "Later"), "bedtime").value = Ui.tr("Ayarlar'dan da geçebilirsiniz", "You can switch in Settings too");
                    if (working && askingToAllow()) {
                        headerRow(Ui.tr("TV SORUYOR", "THE TV ASKS"));
                        note(Ui.tr("“USB hata ayıklamaya izin verilsin mi?” sorusunda", "“Allow USB debugging?”: tick “Always allow”,"));
                        note(Ui.tr("“Her zaman izin ver”i işaretleyip OK'ye basın.", "then press OK."));
                    } else if (!working && !error.isEmpty()) {
                        headerRow(Ui.tr("OLMADI", "IT DIDN'T WORK"));
                        note(error);
                        if (error.contains("debugging is off") || error.contains("port 5555")) {
                            note(Ui.tr("Önce TV'de hata ayıklamayı açın:", "First turn on debugging on the TV:"));
                            note(Ui.tr("Ayarlar → Cihaz Tercihleri → Hakkında → “Derleme”ye 7 kez basın,", "Settings → Device Preferences → About → press “Build” 7 times,"));
                            note(Ui.tr("sonra Geliştirici seçenekleri → USB hata ayıklama: açık.", "then Developer options → USB debugging: on."));
                        }
                    }
                    break;
                }
                default: {   // DONE
                    info(Ui.tr("Bir uygulamada OK'yi basılı tutun", "Hold OK on an app"), Ui.tr("Taşıyın ya da kaldırın", "To move or remove it"), "apps");
                    info(Ui.tr("Hava durumunun yanındaki düğme", "The button next to the weather"), Ui.tr("Kontrol Merkezi", "Control Center"), "control");
                    info(Ui.tr("Kontrol Merkezi → Düzenle", "Control Center → Edit"), Ui.tr("Bunların hepsini değiştirir", "Changes all of this"), "edit");
                    headerRow("");
                    item("done", Ui.tr("Ana Ekrana Git", "Go Home"), "home").chevron = true;
                }
            }
            if (rows.isEmpty()) headerRow("");
            float y = 0;
            for (Row r : rows) {
                r.top = y;
                y += (r.header ? HEADER_H : r.note ? NOTE_H : ROW_H) + ROW_GAP;
            }
            contentH = y;
            while (pop.size() < rows.size()) pop.add(new Spring(0.28f, 0.85f, 0));
            focus = Math.max(0, Math.min(focus, rows.size() - 1));
            if (!rows.get(focus).focusable()) focus = next(focus, 1);
        }

        private Row item(String id, String label, String symbol) {
            Row r = new Row(id, label);
            r.symbol = symbol;
            rows.add(r);
            return r;
        }

        private void headerRow(String text) {
            Row r = new Row("", text);
            r.header = true;
            rows.add(r);
        }

        private void note(String text) {
            Row r = new Row("", text);
            r.note = true;
            rows.add(r);
        }

        /** A line that only informs (Privacy, the tips): OK does nothing on it. */
        private void info(String label, String value, String symbol) {
            item("", label, symbol).value = value;
        }

        /** The row that moves on: "Continue" once something is set; "Skip" on an optional step still empty. */
        private void nextRow(boolean set) {
            boolean optional = step == NAME || step == CITY || step == LIGHTS || step == WIFI || step == MARKETS;
            Row r = item("next", optional && !set ? Ui.tr("Atla", "Skip") : Ui.tr("Devam Et", "Continue"),
                    optional && !set ? "skip_next" : "chevron_right");
            r.chevron = true;
        }

        private static String appSymbol(String pkg) {
            String key = HomeApps.knownKey(pkg);
            String glyph = key == null ? null : HomeView.GLYPHS.get(key);
            return glyph != null ? glyph : "apps";
        }

        private static String wallpaperName(String mode) {
            switch (mode) {
                case "space": return Ui.tr("Uzay · NASA", "Space · NASA");
                case "apod": return Ui.tr("NASA Günün Fotoğrafı", "NASA Picture of the Day");
                case "colors": return Ui.tr("Yumuşak Renkler", "Soft Colors");
                default: return Ui.tr("Doğa", "Nature");
            }
        }

        private static String wallpaperSymbol(String mode) {
            switch (mode) {
                case "space": return "planet";
                case "apod": return "sparkles";
                case "colors": return "drop";
                default: return "leaf";
            }
        }

        /** The dock's apps (ticked, in their order) first, then every other app by name. */
        private void loadApps() {
            Context c = getContext();
            PackageManager pm = c.getPackageManager();
            dock.clear();
            for (String pkg : HomeApps.dockPackages(c)) if (HomeApps.launchIntent(pm, pkg) != null && !dock.contains(pkg)) dock.add(pkg);
            appOrder.clear();
            appLabels.clear();
            for (String pkg : dock) {
                appOrder.add(pkg);
                appLabels.put(pkg, HomeApps.label(pm, pkg));
            }
            for (HomeApps.Item it : HomeApps.launchable(c)) {
                if (appOrder.contains(it.pkg)) continue;
                appOrder.add(it.pkg);
                appLabels.put(it.pkg, it.label);
            }
        }

        private void searchLamps() {
            lightsState = "searching";
            final Context c = getContext().getApplicationContext();
            io.execute(() -> {
                final List<Lights.Lamp> found = Lights.all(c, 1800);
                handler.post(() -> {
                    lamps = found;
                    lightsState = "";
                    if (step == LIGHTS) {
                        String id = rows.isEmpty() ? "" : rows.get(focus).id;
                        rebuild();
                        focusOn(id);
                    }
                    invalidate();
                });
            });
        }

        private Lights.Lamp lamp(String key) {
            for (Lights.Lamp l : lamps) if (l.key().equals(key)) return l;
            return null;
        }

        private void readState() {
            state = HomeMode.state(getContext());
            if (!state.optString("busy").isEmpty()) pendingAt = 0;
            boolean working = busy();
            if (switching && !working && freeActive(state)) {      // done: on to the end
                switching = false;
                if (step == HOME) {
                    go(DONE);
                    return;
                }
            }
            if (!working) switching = false;
            if (step == HOME) {
                String id = rows.isEmpty() ? "" : rows.get(focus).id;
                rebuild();
                focusOn(id);
            }
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean askingToAllow() {
            String b = state.optString("busy");
            return b.contains("izin") || b.contains("Allow");
        }

        private boolean freeActive() {
            return freeActive(HomeMode.state(getContext()));
        }

        private static boolean freeActive(JSONObject s) {
            return HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted");
        }

        /** The wallpaper step shows the focused choice behind the list, after a short pause (no decoding on every key). */
        private final Runnable previewFocused = () -> {
            if (step != WALLPAPER || rows.isEmpty()) return;
            String id = rows.get(focus).id;
            Photos photos = Photos.get(getContext());
            if (Arrays.asList(Prefs.WALLPAPERS).contains(id)) photos.previewMode(id);
            else photos.refresh();
        };

        /** The next focusable row from `i` in direction `d` (or `i` itself if there is none). */
        private int next(int i, int d) {
            for (int j = i + d; j >= 0 && j < rows.size(); j += d) if (rows.get(j).focusable()) return j;
            return i >= 0 && i < rows.size() && rows.get(i).focusable() ? i : Math.max(0, Math.min(rows.size() - 1, i));
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            long up = SystemClock.uptimeMillis();
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance((up - startedAt) / 1000f);
            }
            scene.drawBackground(c);
            veil.setColor(step == WALLPAPER ? 0x29000000 : 0x52000000);   // lighter on the wallpaper: it's the point
            c.drawRect(0, 0, w, h, veil);
            aimScroll();
            scroll.step(dt);
            for (int i = 0; i < pop.size(); i++) {
                pop.get(i).target = i == focus ? 1 : 0;
                pop.get(i).step(dt);
            }

            // On the left: the step's symbol (or the guest QR code), title and one line.
            float cx = 500 * k;
            if (step == WIFI && qr != null) drawQr(c, cx, 380 * k, 300 * k);
            else Symbols.draw(getContext(), c, stepSymbol(), cx, 400 * k, 130 * k, 0xEBFFFFFF);
            c.drawText(stepTitle(), cx, 600 * k, title);
            c.drawText(stepHint(), cx, 648 * k, subtitle);

            c.save();
            c.clipRect(LIST_X * k - 40 * k, (LIST_TOP - 30) * k, w, LIST_BOTTOM * k + 40 * k);
            float seconds = (up - startedAt) / 1000f;
            for (int i = 0; i < rows.size(); i++) drawRow(c, i, seconds);
            c.restore();

            if (step == DONE) {
                Symbols.draw(getContext(), c, "horse", w / 2f, 990 * k, 60 * k, 0xD9FFFFFF);
            } else {
                drawDots(c, cx, 930 * k);
                c.drawText(step == PRIVACY ? Ui.tr("OK: anladım · Geri: önceki", "OK: got it · Back: previous")
                        : Ui.tr("OK: seç · →: sonraki · Geri: önceki", "OK: choose · →: next · Back: previous"), cx, 990 * k, hint);
            }
        }

        private String stepSymbol() {
            switch (step) {
                case NAME: return "sparkles";
                case CITY: return "pin";
                case APPS: return "apps";
                case WALLPAPER: return "wallpaper";
                case LIGHTS: return "lightbulb";
                case WIFI: return "wifi";
                case MUSIC: return "music_note";
                case MARKETS: return "chart";
                case PRIVACY: return "check";
                case HOME: return "tv";
                case DONE: return "sparkles";
                default: return "home";
            }
        }

        private String stepTitle() {
            switch (step) {
                case NAME: return Ui.tr("Adınız", "Your Name");
                case CITY: return Ui.tr("Şehriniz", "Your City");
                case APPS: return Ui.tr("Uygulamalarınız", "Your Apps");
                case WALLPAPER: return Ui.tr("Duvar Kağıdı", "Wallpaper");
                case LIGHTS: return Ui.tr("Işıklar", "Lights");
                case WIFI: return Ui.tr("Misafir Wi-Fi", "Guest Wi-Fi");
                case MUSIC: return Ui.tr("Müzik", "Music");
                case MARKETS: return Ui.tr("Piyasalar", "Markets");
                case PRIVACY: return Ui.tr("Gizlilik", "Privacy");
                case HOME: return Ui.tr("Toria'yı Ana Ekran Yapın", "Make Toria Your Home");
                case DONE: return Ui.tr("Her Şey Hazır", "You're All Set");
                default: return "Dil · Language";
            }
        }

        private String stepHint() {
            switch (step) {
                case NAME: return Ui.tr("Toria size nasıl seslensin?", "What should Toria call you?");
                case CITY: return Ui.tr("Ekranın üstündeki hava durumu için", "For the weather at the top of the screen");
                case APPS: return Ui.tr("OK ile ana ekrana ekleyin ya da çıkarın", "OK adds an app to Home or takes it off");
                case WALLPAPER: return Ui.tr("Gezinirken arkada görünür, OK ile seçin", "Browse to see it behind, OK to choose");
                case LIGHTS: return Ui.tr("Ev ağınızdaki akıllı lambalar", "Smart lamps on your home network");
                case WIFI: return Ui.tr("Misafirler QR kodu okutup bağlanır", "Guests scan a QR code to join");
                case MUSIC: return Ui.tr("Çalan bir şey yokken Now Playing bunu açar", "Now Playing opens it when nothing plays");
                case MARKETS: return Ui.tr("Kontrol Merkezi'nde küçük bir liste", "A small watchlist in Control Center");
                case PRIVACY: return Ui.tr("Verileriniz sizde kalır", "Your data stays with you");
                case HOME: return Ui.tr("Önce okuyun, sonra geçin", "Read this first, then switch");
                case DONE: return Ui.tr("Başlamak için birkaç ipucu", "A few tips to get going");
                default: return Ui.tr("Toria'nın konuştuğu dil", "The language Toria speaks");
            }
        }

        /** One dot per step; the current one is a wide capsule. */
        private void drawDots(Canvas c, float cx, float cy) {
            float r = 5 * k, gap = 22 * k, wide = 34 * k;
            int n = DONE;
            float total = (n - 1) * gap + wide, x = cx - total / 2;
            for (int i = 1; i <= n; i++) {
                boolean on = i == step;
                dot.setColor(on ? 0xF2FFFFFF : i < step ? 0x8CFFFFFF : 0x40FFFFFF);
                float right = x + (on ? wide : 2 * r);
                tmp.set(x, cy - r, right, cy + r);
                c.drawRoundRect(tmp, r, r, dot);
                x = right - 2 * r + gap;
            }
        }

        private void drawQr(Canvas c, float cx, float cy, float box) {
            tmp.set(cx - box / 2, cy - box / 2, cx + box / 2, cy + box / 2);
            c.drawRoundRect(tmp, 22 * k, 22 * k, white);
            int n = qr.length;
            float cell = (float) Math.floor((box - 24 * k) / (n + 4)), size = cell * n;
            float ox = cx - size / 2, oy = cy - size / 2;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (qr[y][x]) c.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, qrDark);
                }
            }
        }

        private void drawRow(Canvas c, int i, float seconds) {
            Row r = rows.get(i);
            float y = (LIST_TOP + r.top) * k - scroll.value;
            float rh = (r.header ? HEADER_H : r.note ? NOTE_H : ROW_H) * k;
            if (y + rh < (LIST_TOP - 30) * k || y > (LIST_BOTTOM + 40) * k) return;
            if (r.header) {
                if (!r.label.isEmpty()) c.drawText(r.label, LIST_X * k + 30 * k, y + rh - 16 * k, header);
                return;
            }
            if (r.note) {
                String text = TextUtils.ellipsize(r.label, new TextPaint(note), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString();
                c.drawText(text, LIST_X * k + 30 * k, y + rh / 2 + Ui.halfCap(note), note);
                return;
            }
            rowRect.set(LIST_X * k, y, LIST_R * k, y + rh);
            float f = Ui.clamp(pop.get(i).value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float mx = rowRect.centerX(), my = rowRect.centerY();
                tmp.set(mx - rowRect.width() * s / 2, my - rowRect.height() * s / 2, mx + rowRect.width() * s / 2, my + rowRect.height() * s / 2);
                float rad = tmp.height() / 2;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, rad, rad, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, rad, rad, white);
                white.setAlpha(255);
                rowRect.set(tmp);
            }
            int ink = blend(r.dim ? 0x80FFFFFF : 0xF2FFFFFF, r.dim ? 0x991C1C1E : INK, f);
            float x = rowRect.left + 34 * k, cy = rowRect.centerY();
            if (r.symbol != null) {
                float size = 26 * k, sw = Symbols.width(getContext(), r.symbol, size);
                if (sw > 34 * k) size *= 34 * k / sw;
                Symbols.draw(getContext(), c, r.symbol, x + 17 * k, cy, size, ink);
                x += 58 * k;
            }
            rowLabel.setColor(ink);
            float right = rowRect.right - 34 * k;
            if (r.checked) {
                float cw = Symbols.width(getContext(), "check", 26 * k);
                Symbols.draw(getContext(), c, "check", right - cw / 2, cy, 26 * k, blend(0xFFFFFFFF, INK, f));
                right -= cw + 18 * k;
            } else if (r.chevron) {
                float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                right -= cw + 18 * k;
            }
            String value = r.value;
            rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
            float valueMax = Math.max(380 * k, right - x - rowLabel.measureText(r.label) - 40 * k);
            float valueW = value.isEmpty() ? 0 : Math.min(rowValue.measureText(value), valueMax);
            if (!value.isEmpty()) {
                c.drawText(TextUtils.ellipsize(value, new TextPaint(rowValue), valueMax, TextUtils.TruncateAt.END).toString(), right,
                        cy + Ui.halfCap(rowValue), rowValue);
            }
            if (r.spinner) {
                spinner.setColor(blend(0xFFFFFFFF, INK, f));
                Ui.spinner(c, right - valueW - 26 * k, cy, 12 * k, seconds * 1.3f, spinner);
                valueW += 44 * k;
            }
            float room = right - valueW - 24 * k - x;
            c.drawText(TextUtils.ellipsize(r.label, new TextPaint(rowLabel), room, TextUtils.TruncateAt.END).toString(), x,
                    cy + Ui.halfCap(rowLabel), rowLabel);
        }

        private void snapScroll() {
            aimScroll();
            scroll.snap(scroll.target);
        }

        /** Keeps the focused row in view, with a row of air above and below. */
        private void aimScroll() {
            if (rows.isEmpty()) return;
            float view = (LIST_BOTTOM - LIST_TOP) * k, max = Math.max(0, contentH * k - view);
            Row r = rows.get(focus);
            float top = r.top * k, bottom = (r.top + ROW_H) * k, margin = (ROW_H + ROW_GAP) * k;
            float t = scroll.target;
            if (top - margin < t) t = top - margin;
            if (bottom + margin > t + view) t = bottom + margin - view;
            if (focus <= 1) t = 0;
            scroll.target = Math.max(0, Math.min(max, t));
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
                    if (down) {
                        focus = next(focus, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                        if (step == WALLPAPER) {
                            handler.removeCallbacks(previewFocused);
                            handler.postDelayed(previewFocused, 350);
                        }
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // On: not past Privacy without "Got it", nor while the home screen switches.
                    if (down && e.getRepeatCount() == 0 && step != PRIVACY && step != DONE && !(step == HOME && busy())) forward();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) ok();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) backward();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void ok() {
            if (rows.isEmpty()) return;
            Row r = rows.get(focus);
            if (!r.focusable() || r.id.isEmpty()) return;
            if (r.id.equals("next")) {
                forward();
                return;
            }
            final Context c = getContext();
            switch (step) {
                case LANGUAGE: {
                    // In the shared state file, like Settings and Customize do it: everything redraws in it at once.
                    JSONObject s = HomeMode.state(c);
                    try {
                        s.put("language", r.id);
                    } catch (org.json.JSONException ignored) {
                    }
                    HomeMode.save(c, s);
                    Ui.loadLanguage(c);
                    rebuild();
                    focusOn("next");
                    break;
                }
                case NAME:
                    Dialogs.ask(activity, Ui.tr("Adınız", "Your name"), Ui.tr("Ekranın üstünde, saatin yanında görünür.", "Shown at the top, by the clock."),
                            Prefs.name(), Dialogs.FIELD_WORDS, text -> {
                                Prefs.put(c, "name", text.trim());
                                rebuild();
                                focusOn("next");
                            }, this::invalidate);
                    break;
                case CITY:
                    city(r.id);
                    break;
                case APPS:
                    apps(r.id);
                    break;
                case WALLPAPER: {
                    handler.removeCallbacks(previewFocused);
                    if (!r.id.equals(Prefs.wallpaper())) Prefs.put(c, "wallpaper_photo", null);   // a photo of the other set
                    Prefs.put(c, "wallpaper", r.id);
                    Photos.get(c).refresh();
                    rebuild();
                    focusOn("next");
                    break;
                }
                case LIGHTS:
                    if (r.id.equals("search")) {
                        if (!lightsState.equals("searching")) {
                            searchLamps();
                            rebuild();
                        }
                    } else {
                        final Lights.Lamp l = lamp(r.id.substring(5));
                        final Context app = c.getApplicationContext();
                        if (l != null) io.execute(() -> Lights.blink(app, l));
                    }
                    break;
                case WIFI:
                    wifi(r.id);
                    break;
                case MUSIC:
                    Prefs.put(c, "music_app", r.id.equals(MediaListener.YT_MUSIC) ? null : r.id);
                    rebuild();
                    focusOn("next");
                    break;
                case MARKETS:
                    markets(r.id);
                    break;
                case HOME:
                    if (r.id.equals("later")) {
                        go(DONE);
                    } else if (!busy()) {
                        HomeMode.request(c, HomeMode.FREE);
                        switching = true;
                        pendingAt = SystemClock.uptimeMillis();
                        state = HomeMode.state(c);
                        try {
                            state.put("error", "");
                        } catch (org.json.JSONException ignored) {
                        }
                        rebuild();
                    }
                    break;
                case DONE:
                    if (r.id.equals("done")) activity.finishSetup();
                    break;
                default:
            }
            invalidate();
        }

        private void city(String id) {
            Context c = getContext();
            if (id.equals("search")) {
                Dialogs.ask(activity, Ui.tr("Şehriniz", "Your city"), null, Prefs.city(), Dialogs.FIELD_WORDS, this::searchCity, this::invalidate);
            } else if (id.equals("temperature")) {
                String t = Prefs.temperature().toUpperCase(Locale.ROOT);
                Prefs.put(c, "temperature", t.startsWith("C") ? "F" : t.startsWith("F") ? "auto" : "C");
                rebuild();
            } else if (id.startsWith("place")) {
                String[] p = places.get(Integer.parseInt(id.substring(5)));
                Prefs.setPlace(c, p[0], Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                places.clear();
                placesState = "";
                rebuild();
                focusOn("next");
                snapScroll();
            }
        }

        /** Open-Meteo's geocoding (Geocode): the places with that name, listed right under the search row. */
        private void searchCity(final String query) {
            if (query.trim().isEmpty()) return;
            places.clear();
            placesState = "searching";
            rebuild();
            invalidate();
            final String lang = Ui.en ? "en" : "tr";
            io.execute(() -> {
                List<String[]> found = new ArrayList<>();
                String result;
                try {
                    found = Geocode.search(query, lang);
                    result = found.isEmpty() ? "none" : "";
                } catch (Exception e) {
                    AppLog.w("geocoding: " + e.getMessage());
                    result = "error";
                }
                final List<String[]> list = found;
                final String s = result;
                handler.post(() -> {
                    places.clear();
                    places.addAll(list);
                    placesState = s;
                    if (step == CITY) {
                        rebuild();
                        focusOn(list.isEmpty() ? (s.equals("error") ? "next" : "search") : "place0");
                        snapScroll();
                    }
                    invalidate();
                });
            });
        }

        private void apps(String id) {
            Context c = getContext();
            if (id.startsWith("layout:")) {
                Prefs.put(c, "layout", id.substring(7));
            } else {
                String pkg = id.substring(4);
                if (dock.contains(pkg)) dock.remove(pkg);
                else if (dock.size() < HomeApps.MAX_SHELF) dock.add(pkg);
                else return;
                Prefs.setDock(c, dock);
            }
            rebuild();
        }

        private void wifi(String id) {
            final Context c = getContext();
            switch (id) {
                case "ssid":
                    Dialogs.ask(activity, Ui.tr("Ağ adı (SSID)", "Network name (SSID)"), null, Prefs.wifiSsid(), Dialogs.FIELD_CODE, text -> {
                        Prefs.put(c, "wifi_ssid", text.trim());
                        rebuild();
                    }, this::invalidate);
                    break;
                case "password":
                    if (Prefs.wifiSecurity().equals("nopass")) return;
                    Dialogs.ask(activity, Ui.tr("Wi-Fi şifresi", "Wi-Fi password"),
                            Ui.tr("Yalnızca bu TV'de, şifreli saklanır; QR kodunda ve misafir ekranında görünür.",
                                    "Kept on this TV only, encrypted; shown in the QR code and on the guest screen."),
                            Prefs.wifiPassword(), Dialogs.FIELD_SECRET, text -> {
                                if (!Prefs.putSecret(c, "wifi_password", text)) {
                                    Dialogs.message(activity, Ui.tr("Misafir Wi-Fi", "Guest Wi-Fi"),
                                            Ui.tr("TV şifreyi güvenle saklayamadı.", "The TV couldn't store the password securely."), null, null, this::invalidate);
                                }
                                rebuild();
                            }, this::invalidate);
                    break;
                default: {   // security: WPA → none → WEP
                    String s = Prefs.wifiSecurity();
                    Prefs.put(c, "wifi_security", s.equals("WPA") ? "nopass" : s.equals("nopass") ? "WEP" : "WPA");
                    rebuild();
                }
            }
        }

        private void markets(String id) {
            final Context c = getContext();
            switch (id) {
                case "none":
                    Prefs.put(c, "stocks", "");
                    break;
                case "basic":
                    Prefs.put(c, "stocks", WATCHLIST);
                    break;
                default:
                    Dialogs.ask(activity, Ui.tr("Yahoo sembolleri, virgülle", "Yahoo symbols, comma-separated"),
                            Ui.tr("Örn. ^GSPC:S&P, BTC-USD:BTC, AAPL (iki noktadan sonrası ekranda görünen ad)",
                                    "E.g. ^GSPC:S&P, BTC-USD:BTC, AAPL (after the colon: the name on screen)"),
                            Prefs.stocks(), Dialogs.FIELD_CODE, text -> {
                                StringBuilder clean = new StringBuilder();
                                for (String s : Prefs.split(text)) clean.append(clean.length() == 0 ? "" : ",").append(s);
                                Prefs.put(c, "stocks", clean.toString());
                                rebuild();
                                focusOn("next");
                            }, this::invalidate);
                    return;
            }
            rebuild();
            focusOn("next");
        }
    }
}
