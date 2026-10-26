package io.troiaos;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Customize, in the style of the settings: the dock's apps (add, move, remove), Control Center's controls (show, hide,
 * move), the home screen (greeting, weather city, temperature unit, watchlist, language), the wallpaper (nature,
 * NASA's space photos, NASA's picture of the day or soft colors; one photo or a new one every hour; darkening), the
 * lights (any brand: Lights), the guest Wi-Fi QR code, the music app, and what TroiaOS does with data (Privacy). Everything is saved in
 * Prefs at once; the home screen and Control Center pick it up when they come back.
 *
 * Opened from Settings, from "Edit" in Control Center (straight to its page) and by holding OK on a dock app (straight
 * to the dock, on that app). Back from a page it was opened on closes it.
 */
public class CustomizeActivity extends Activity {
    static final String EXTRA_PAGE = "page", EXTRA_PKG = "pkg";
    static final int PAGE_MAIN = 0, PAGE_DOCK = 1, PAGE_ADD = 2, PAGE_CONTROL = 3, PAGE_HOME = 4, PAGE_CITY = 5,
            PAGE_WALL = 6, PAGE_PICK = 7, PAGE_LIGHTS = 8, PAGE_WIFI = 9, PAGE_MUSIC = 10, PAGE_PRIVACY = 11;

    private CustomizeView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new CustomizeView(this, getIntent().getIntExtra(EXTRA_PAGE, PAGE_MAIN), getIntent().getStringExtra(EXTRA_PKG));
        setContentView(view);
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

    static final class CustomizeView extends View implements Choreographer.FrameCallback, Photos.Listener {
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, LIST_BOTTOM = 1000, ROW_H = 74, ROW_GAP = 8,
                HEADER_H = 58, MENU_W = 330, MENU_ROW = 64;
        private static final int INK = 0xFF1C1C1E;

        /** One line of a page. */
        private static final class Row {
            final String id, label;
            String value = "", symbol;
            boolean header, chevron, dim;
            float top;      // in list units, from LIST_TOP

            Row(String id, String label) {
                this.id = id;
                this.label = label;
            }
        }

        private final CustomizeActivity activity;
        private final int entryPage;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final List<Row> rows = new ArrayList<>();
        private final List<Spring> pop = new ArrayList<>();
        private final Spring scroll = new Spring(0.35f, 1f, 0), menuPop = new Spring(0.25f, 0.85f, 0);
        private final RectF tmp = new RectF(), rowRect = new RectF();
        private int page, focus;
        private boolean running, okDown, backDown, moving;
        private long startedAt, lastNanos, lastSky;
        private int w, h;
        private float k = 1, contentH;

        // Page data.
        private final List<String> dock = new ArrayList<>();
        private List<HomeApps.Item> apps = new ArrayList<>();
        private final List<Prefs.Module> buttons = new ArrayList<>(), tiles = new ArrayList<>();
        private final List<String[]> places = new ArrayList<>();   // name, detail, latitude, longitude
        private String placesState = "";                           // "", "searching", "none", "error"
        private List<Lights.Lamp> lampsAll = new ArrayList<>();         // Customize → Lights, hidden ones too
        private String lightsState = "";                            // "", "searching", "connecting"
        private String[] menu;                                       // the open action menu's options (null: none)
        private int menuFocus;

        private final Paint veil = new Paint();
        private Paint title, subtitle, rowLabel, rowValue, header, hint, menuText, white, shadow, glassFill;

        CustomizeView(CustomizeActivity a, int page, String pkg) {
            super(a);
            activity = a;
            this.page = entryPage = page;
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            veil.setColor(0x52000000);
            load();
            rebuild();
            if (page == PAGE_LIGHTS) searchLamps();
            if (pkg != null) {
                for (int i = 0; i < rows.size(); i++) if (pkg.equals(rows.get(i).id)) focus = i;
            }
            snapScroll();
        }

        void resume() {
            if (running) return;
            running = true;
            Photos.get(getContext()).addListener(this);
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            Photos.get(getContext()).removeListener(this);
            handler.removeCallbacks(previewFocused);
            if (page == PAGE_PICK) Photos.get(getContext()).refresh();     // a preview never outlives the page
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean busy = !scroll.settled() || !menuPop.settled();
            for (Spring s : pop) busy |= !s.settled();
            if (busy || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
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
            hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            menuText = Ui.text(Ui.medium, 27 * k, 0xF2FFFFFF);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            snapScroll();
        }

        // MARK: - Data

        private void load() {
            Context c = getContext();
            Prefs.load(c);
            dock.clear();
            PackageManager pm = c.getPackageManager();
            for (String pkg : HomeApps.dockPackages(c)) if (HomeApps.launchIntent(pm, pkg) != null && !dock.contains(pkg)) dock.add(pkg);
            buttons.clear();
            buttons.addAll(Prefs.modules(Prefs.BUTTONS));
            tiles.clear();
            tiles.addAll(Prefs.modules(Prefs.TILES));
        }

        /** The rows of the current page, laid out (headers are shorter and never take focus). */
        private void rebuild() {
            rows.clear();
            Context c = getContext();
            switch (page) {
                case PAGE_MAIN: {
                    Row r = item("dock", Ui.tr("Ana Ekrandaki Uygulamalar", "Apps on Home"), "apps");
                    r.value = Ui.tr(dock.size() + " uygulama", dock.size() + " apps");
                    r.chevron = true;
                    r = item("control", Ui.tr("Kontrol Merkezi", "Control Center"), "control");
                    int shown = 0;
                    for (Prefs.Module m : buttons) if (m.on) shown++;
                    for (Prefs.Module m : tiles) if (m.on) shown++;
                    r.value = Ui.tr(shown + " kontrol", shown + " controls");
                    r.chevron = true;
                    r = item("home", Ui.tr("Ana Ekran", "Home Screen"), "home");
                    r.value = Prefs.city();
                    r.chevron = true;
                    r = item("wall", Ui.tr("Duvar Kağıdı", "Wallpaper"), "wallpaper");
                    r.value = wallpaperName(Prefs.wallpaper());
                    r.chevron = true;
                    r = item("lights", Ui.tr("Işıklar", "Lights"), "lightbulb");
                    r.value = Lights.known(c) ? Ui.tr("Kurulu", "Set up") : Ui.tr("Kurulmadı", "Not set up");
                    r.chevron = true;
                    r = item("wifi", Ui.tr("Misafir Wi-Fi QR", "Guest Wi-Fi QR"), "wifi");
                    r.value = Prefs.wifiSsid().isEmpty() ? Ui.tr("Kapalı", "Off") : Prefs.wifiSsid();
                    r.chevron = true;
                    r = item("music", Ui.tr("Müzik Uygulaması", "Music App"), "music_note");
                    r.value = HomeApps.label(c.getPackageManager(), Prefs.musicApp());
                    r.chevron = true;
                    rows.add(headerRow(""));
                    r = item("privacy", Ui.tr("Gizlilik", "Privacy"), "check");
                    r.value = Ui.tr("Hiçbir veri toplanmaz", "Nothing is collected");
                    r.chevron = true;
                    r = item("setup", Ui.tr("Kurulumu Yeniden Başlat", "Set Up Again"), "restart_alt");
                    r.chevron = true;
                    break;
                }
                case PAGE_DOCK: {
                    rows.add(headerRow(Ui.tr("ANA EKRANDA, SIRAYLA", "ON HOME, IN ORDER")));
                    PackageManager pm = c.getPackageManager();
                    for (String pkg : dock) {
                        item(pkg, HomeApps.label(pm, pkg), appSymbol(pkg));
                    }
                    rows.add(headerRow(""));
                    Row add = item("add", Ui.tr("Uygulama Ekle", "Add App"), "add");
                    add.chevron = dock.size() < HomeApps.MAX_SHELF;
                    add.dim = !add.chevron;
                    if (add.dim) add.value = Ui.tr("Liste dolu", "Home is full");
                    item("reset", Ui.tr("Varsayılana Dön", "Reset to Default"), "restart_alt");
                    break;
                }
                case PAGE_ADD: {
                    for (HomeApps.Item it : apps) {
                        if (dock.contains(it.pkg)) continue;
                        item(it.pkg, it.label, appSymbol(it.pkg));
                    }
                    if (rows.isEmpty()) rows.add(headerRow(Ui.tr("EKLENECEK BAŞKA UYGULAMA YOK", "NO OTHER APPS TO ADD")));
                    break;
                }
                case PAGE_CONTROL: {
                    rows.add(headerRow(Ui.tr("DÜĞMELER", "BUTTONS")));
                    for (Prefs.Module m : buttons) moduleRow(m);
                    rows.add(headerRow(Ui.tr("KUTULAR", "TILES")));
                    for (Prefs.Module m : tiles) moduleRow(m);
                    rows.add(headerRow(""));
                    item("reset", Ui.tr("Varsayılana Dön", "Reset to Default"), "restart_alt");
                    break;
                }
                case PAGE_HOME: {
                    Row r = item("layout", Ui.tr("Uygulama Görünümü", "Apps Layout"), "apps");
                    r.value = Prefs.layout().equals("list") ? Ui.tr("Liste", "List") : "Dock";
                    r = item("name", Ui.tr("Selamlama Adı", "Greeting Name"), "sparkles");
                    r.value = Prefs.name().isEmpty() ? Ui.tr("Yok", "None") : Prefs.name();
                    r = item("city", Ui.tr("Hava Durumu Şehri", "Weather City"), "pin");
                    r.value = Prefs.city().isEmpty() ? Prefs.latitude() + ", " + Prefs.longitude() : Prefs.city();
                    r = item("temperature", Ui.tr("Sıcaklık", "Temperature"), "sunny");
                    String t = Prefs.temperature().toUpperCase(Locale.ROOT);
                    r.value = t.startsWith("F") ? "°F" : t.startsWith("C") ? "°C" : Ui.tr("Otomatik", "Automatic");
                    r = item("stocks", Ui.tr("Piyasa Listesi", "Watchlist"), "chart");
                    r.value = watchlistSummary();
                    r = item("sound", Ui.tr("Tık Sesi", "Click Sound"), "music_note");
                    r.value = Prefs.clickSound() ? Ui.tr("Açık", "On") : Ui.tr("Kapalı", "Off");
                    if (android.os.Build.VERSION.SDK_INT >= 33) {      // Android 11/12 only have the classic glass
                        r = item("glass", Ui.tr("Cam", "Glass"), "contrast");
                        String g = Prefs.glass();
                        r.value = g.equals("auto") ? Ui.tr("Liquid Glass", "Liquid Glass")
                                : g.equals("slow") ? Ui.tr("Klasik (TV yavaş)", "Classic (TV too slow)") : Ui.tr("Klasik", "Classic");
                    }
                    r = item("language", "Dil / Language", "translate");
                    r.value = Ui.en ? "English" : "Türkçe";
                    break;
                }
                case PAGE_WALL: {
                    rows.add(headerRow(Ui.tr("ARKADA NE OLSUN", "BEHIND YOUR APPS")));
                    String mode = Prefs.wallpaper();
                    for (String m : Prefs.WALLPAPERS) {
                        Row r = item(m, wallpaperName(m), wallpaperSymbol(m));
                        r.value = m.equals(mode) ? Ui.tr("Seçili", "Selected") : "";
                    }
                    rows.add(headerRow(""));
                    if (mode.equals("nature") || mode.equals("cities") || mode.equals("space")) {
                        Row r = item("pick", Ui.tr("Fotoğraf", "Photo"), "photo_library");
                        r.value = chosenPhotoName(mode);
                        r.chevron = true;
                    } else if (mode.equals("apod")) {
                        Row r = item("today", Ui.tr("Bugün", "Today"), "sparkles");
                        Apod.Day d = Photos.get(c).apod();
                        r.value = d != null ? d.title : Ui.tr("NASA fotoğrafları gösteriliyor", "Showing NASA's photos");
                        r.dim = true;
                    }
                    if (!mode.equals("colors")) {
                        Row r = item("dim", Ui.tr("Karartma", "Darken"), "contrast");
                        int d = Prefs.wallpaperDim();
                        r.value = d == 0 ? Ui.tr("Kapalı", "Off") : "%" + d;
                        if (Ui.en && d > 0) r.value = d + "%";
                    }
                    break;
                }
                case PAGE_PICK: {
                    String mode = Prefs.wallpaper(), fixed = Prefs.wallpaperPhoto();
                    Row auto = item("auto", Ui.tr("Her saat yenisi", "A new one every hour"), "sync");
                    auto.value = fixed.isEmpty() ? Ui.tr("Seçili", "Selected") : "";
                    rows.add(headerRow(Ui.tr("YA DA HEP AYNISI", "OR ALWAYS THE SAME ONE")));
                    for (Photos.Photo p : Photos.get(c).choices(mode)) {
                        Row r = item(p.file, p.place(), wallpaperSymbol(mode));
                        r.value = p.file.equals(fixed) ? Ui.tr("Seçili", "Selected") : "";
                    }
                    break;
                }
                case PAGE_LIGHTS: {
                    if (lightsState.equals("searching")) rows.add(headerRow(Ui.tr("LAMBALAR ARANIYOR…", "LOOKING FOR LAMPS…")));
                    else if (lightsState.equals("connecting")) rows.add(headerRow(Ui.tr("BAĞLANIYOR…", "CONNECTING…")));
                    else rows.add(headerRow(lampsAll.isEmpty() ? Ui.tr("LAMBA BULUNAMADI", "NO LAMPS FOUND")
                            : Ui.tr("LAMBALAR · OK: ADI, YANIP SÖN, GİZLE", "LAMPS · OK: RENAME, BLINK, HIDE")));
                    for (Lights.Lamp l : lampsAll) {
                        boolean hidden = Prefs.lampHidden(l.key());
                        Row r = item(l.key(), l.name(), "lightbulb");
                        r.value = Lights.brandName(l.brand) + (hidden ? Ui.tr(" · gizli", " · hidden") : "");
                        r.dim = hidden;
                    }
                    item("search", Ui.tr("Yeniden Ara", "Search Again"), "sync");
                    rows.add(headerRow(Ui.tr("BAĞLA", "CONNECT")));
                    Row hue = item("hue", "Philips Hue", "lightbulb");
                    hue.value = Prefs.has("hue_user") ? Ui.tr("Bağlı · ", "Connected · ") + Prefs.hueBridge() : Ui.tr("Köprüyü bağla", "Pair the Bridge");
                    hue.chevron = !Prefs.has("hue_user");
                    Row ha = item("ha", "Home Assistant", "home");
                    ha.value = Prefs.has("ha_token") ? Ui.tr("Bağlı", "Connected") : Ui.tr("Diğer markalar için", "For any other brand");
                    ha.chevron = !Prefs.has("ha_token");
                    rows.add(headerRow(Ui.tr("WIZ, LIFX VE GOVEE KENDİLİĞİNDEN BULUNUR", "WIZ, LIFX AND GOVEE ARE FOUND BY THEMSELVES")));
                    rows.add(headerRow(Ui.tr("GOVEE: UYGULAMASINDA “LAN CONTROL” AÇIK OLMALI", "GOVEE: TURN ON “LAN CONTROL” IN THE GOVEE APP")));
                    rows.add(headerRow(Ui.tr("HER ŞEY EV AĞINIZDA KALIR · BULUT YOK", "EVERYTHING STAYS ON YOUR HOME NETWORK · NO CLOUD")));
                    break;
                }
                case PAGE_WIFI: {
                    rows.add(headerRow(Ui.tr("MİSAFİRLER QR KODU TARAYIP BAĞLANIR", "GUESTS SCAN A QR CODE TO JOIN")));
                    String ssid = Prefs.wifiSsid(), pass = Prefs.wifiPassword(), sec = Prefs.wifiSecurity();
                    Row r = item("ssid", Ui.tr("Ağ Adı", "Network Name"), "wifi");
                    r.value = ssid.isEmpty() ? Ui.tr("Ayarlanmadı", "Not set") : ssid;
                    r = item("password", Ui.tr("Şifre", "Password"), "settings");
                    r.value = sec.equals("nopass") ? Ui.tr("Yok (açık ağ)", "None (open network)")
                            : pass.isEmpty() ? Ui.tr("Ayarlanmadı", "Not set") : "••••••••";
                    r.dim = sec.equals("nopass");
                    r = item("security", Ui.tr("Güvenlik", "Security"), "check");
                    r.value = sec.equals("nopass") ? Ui.tr("Yok", "None") : sec.equals("WEP") ? "WEP" : "WPA / WPA2 / WPA3";
                    r = item("show", Ui.tr("QR Kodunu Göster", "Show the QR Code"), "focus");
                    r.chevron = !ssid.isEmpty();
                    r.dim = ssid.isEmpty();
                    item("clear", Ui.tr("Kaldır", "Remove"), "restart_alt");
                    rows.add(headerRow(Ui.tr("YALNIZCA BU TV'DE, ŞİFRELİ SAKLANIR · HİÇBİR YERE GÖNDERİLMEZ",
                            "KEPT ONLY ON THIS TV, ENCRYPTED · NEVER SENT ANYWHERE")));
                    break;
                }
                case PAGE_MUSIC: {
                    rows.add(headerRow(Ui.tr("ÇALAN BİR ŞEY YOKKEN NOW PLAYING BUNU AÇAR", "NOW PLAYING OPENS IT WHEN NOTHING PLAYS")));
                    String chosen = Prefs.musicApp();
                    for (HomeApps.Item it : musicApps(c)) {
                        Row r = item(it.pkg, it.label, appSymbol(it.pkg));
                        r.value = it.pkg.equals(chosen) ? Ui.tr("Seçili", "Selected") : "";
                    }
                    break;
                }
                case PAGE_PRIVACY: {
                    rows.add(headerRow(Ui.tr("TROIAOS HİÇBİR VERİ TOPLAMAZ", "TROIAOS COLLECTS NOTHING")));
                    info(Ui.tr("Hesap, reklam, takip yok", "No account, no ads, no tracking"), "", "check");
                    info(Ui.tr("TroiaOS'un sunucusu yok", "TroiaOS has no servers"), Ui.tr("Bize hiçbir şey gelmez", "Nothing is sent to us"), "check");
                    rows.add(headerRow(Ui.tr("İNTERNETE GİDENLER (YALNIZCA HTTPS)", "WHAT GOES TO THE INTERNET (HTTPS ONLY)")));
                    info(Ui.tr("Hava durumu", "Weather"), Ui.tr("Open-Meteo: şehrinizin konumu", "Open-Meteo: your city's location"), "sunny");
                    info(Ui.tr("Şehir arama", "City search"), Ui.tr("Open-Meteo: yazdığınız ad", "Open-Meteo: the name you type"), "pin");
                    info(Ui.tr("Piyasalar", "Markets"), Ui.tr("Yahoo Finance: sembolleriniz", "Yahoo Finance: your symbols"), "chart");
                    info(Ui.tr("NASA fotoğrafı", "NASA picture"), Ui.tr("NASA: yalnızca seçerseniz", "NASA: only if you choose it"), "sparkles");
                    rows.add(headerRow(Ui.tr("EVDE KALANLAR", "WHAT STAYS AT HOME")));
                    info(Ui.tr("Işıklar", "Lights"), Ui.tr("Yalnızca ev ağı, bulut yok", "Home network only, no cloud"), "lightbulb");
                    info(Ui.tr("Şifreler, anahtarlar", "Passwords, keys"), Ui.tr("Bu TV'de şifreli", "Encrypted on this TV"), "settings");
                    info(Ui.tr("Çalan müzik", "Now Playing"), Ui.tr("TV'de okunur, gönderilmez", "Read on the TV, never sent"), "music_note");
                    info(Ui.tr("TV kontrolü (ADB)", "TV control (ADB)"), Ui.tr("Yalnızca bu TV'nin kendisi", "Only this TV, its own commands"), "tv");
                    info(Ui.tr("Yedekleme", "Backup"), Ui.tr("Kapalı: ayarlar TV'den çıkmaz", "Off: settings never leave the TV"), "home");
                    break;
                }
                default: {   // PAGE_CITY
                    if (placesState.equals("searching")) rows.add(headerRow(Ui.tr("ARANIYOR…", "SEARCHING…")));
                    else if (placesState.equals("none")) rows.add(headerRow(Ui.tr("BULUNAMADI", "NOTHING FOUND")));
                    else if (placesState.equals("error")) rows.add(headerRow(Ui.tr("BAĞLANTI HATASI", "COULDN'T SEARCH")));
                    for (int i = 0; i < places.size(); i++) {
                        Row r = item("place" + i, places.get(i)[0], "pin");
                        r.value = places.get(i)[1];
                    }
                }
            }
            if (rows.isEmpty()) rows.add(headerRow(""));   // never an empty page (focus always has a row)
            float y = 0;
            for (Row r : rows) {
                r.top = y;
                y += (r.header ? HEADER_H : ROW_H) + ROW_GAP;
            }
            contentH = y;
            while (pop.size() < rows.size()) pop.add(new Spring(0.28f, 0.85f, 0));
            focus = Math.max(0, Math.min(focus, rows.size() - 1));
            if (rows.get(focus).header) focus = next(focus, 1);
        }

        private Row item(String id, String label, String symbol) {
            Row r = new Row(id, label);
            r.symbol = symbol;
            rows.add(r);
            return r;
        }

        private static Row headerRow(String text) {
            Row r = new Row("", text);
            r.header = true;
            return r;
        }

        private void moduleRow(Prefs.Module m) {
            Row r = item(m.id, ControlCenterActivity.CenterView.moduleLabel(m.id), ControlCenterActivity.CenterView.moduleSymbol(m.id));
            r.value = m.on ? Ui.tr("Görünür", "Shown") : Ui.tr("Gizli", "Hidden");
            r.dim = !m.on;
            if (m.on && m.id.equals("markets") && Prefs.stocks().isEmpty()) r.value = Ui.tr("Liste boş", "No watchlist");
        }

        private static String appSymbol(String pkg) {
            String key = HomeApps.knownKey(pkg);
            String glyph = key == null ? null : HomeView.GLYPHS.get(key);
            return glyph != null ? glyph : "apps";
        }

        private static String watchlistSummary() {
            StringBuilder b = new StringBuilder();
            for (String item : Prefs.split(Prefs.stocks())) {
                int colon = item.lastIndexOf(':');
                b.append(b.length() == 0 ? "" : ", ").append(colon > 0 ? item.substring(colon + 1) : item);
            }
            return b.length() == 0 ? Ui.tr("Boş", "Empty") : b.toString();
        }

        /** A line that only informs (Privacy): OK does nothing on it. */
        private void info(String label, String value, String symbol) {
            Row r = item("", label, symbol);
            r.value = value;
        }

        /** Installed apps for Music App: the known music apps first, then the rest. */
        static List<HomeApps.Item> musicApps(Context c) {
            List<HomeApps.Item> all = HomeApps.launchable(c), out = new ArrayList<>();
            for (HomeApps.Item it : all) if (it.pkg.equals(MediaListener.YT_MUSIC) || it.pkg.equals("com.spotify.tv.android")) out.add(it);
            for (HomeApps.Item it : all) if (!out.contains(it)) out.add(it);
            return out;
        }

        private static String wallpaperName(String mode) {
            switch (mode) {
                case "cities": return Ui.tr("Şehirler", "Cities");
                case "space": return Ui.tr("Uzay · NASA", "Space · NASA");
                case "apod": return Ui.tr("NASA Günün Fotoğrafı", "NASA Picture of the Day");
                case "colors": return Ui.tr("Yumuşak Renkler", "Soft Colors");
                default: return Ui.tr("Doğa", "Nature");
            }
        }

        private static String wallpaperSymbol(String mode) {
            switch (mode) {
                case "cities": return "city";
                case "space": return "planet";
                case "apod": return "sparkles";
                case "colors": return "drop";
                default: return "leaf";
            }
        }

        private String chosenPhotoName(String mode) {
            String fixed = Prefs.wallpaperPhoto();
            for (Photos.Photo p : Photos.get(getContext()).choices(mode)) if (p.file.equals(fixed)) return p.place();
            return Ui.tr("Her saat yenisi", "Every hour");
        }

        /** Photos: the picture of the day arrived (its title is on the page). */
        @Override public void onPhoto() {
            if (page == PAGE_WALL && !moving && menu == null) rebuild();
            invalidate();
        }

        /** The picker shows the focused photo behind the list, after a short pause (no decoding on every key). */
        private final Runnable previewFocused = () -> {
            if (page != PAGE_PICK || rows.isEmpty()) return;
            String id = rows.get(focus).id;
            Photos photos = Photos.get(getContext());
            if (id.equals("auto")) {
                photos.refresh();
                return;
            }
            for (Photos.Photo p : photos.choices(Prefs.wallpaper())) if (p.file.equals(id)) photos.preview(p);
        };

        /** The next focusable row from `i` in direction `d` (or `i` itself if there is none). */
        private int next(int i, int d) {
            for (int j = i + d; j >= 0 && j < rows.size(); j += d) if (!rows.get(j).header) return j;
            return rows.get(i).header ? Math.max(0, Math.min(rows.size() - 1, i)) : i;
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
            veil.setColor(page == PAGE_PICK ? 0x29000000 : 0x52000000);   // lighter while picking: the photo is the point
            c.drawRect(0, 0, w, h, veil);   // the white text stays readable on a bright photo
            aimScroll();
            scroll.step(dt);
            menuPop.target = menu != null ? 1 : 0;
            menuPop.step(dt);
            for (int i = 0; i < pop.size(); i++) {
                pop.get(i).target = i == focus ? 1 : 0;
                pop.get(i).step(dt);
            }

            // On the left: the page's symbol, title and what OK does here.
            float cx = 500 * k;
            Symbols.draw(getContext(), c, pageSymbol(), cx, 400 * k, 130 * k, 0xEBFFFFFF);
            c.drawText(pageTitle(), cx, 600 * k, title);
            c.drawText(pageHint(), cx, 648 * k, subtitle);

            c.save();
            c.clipRect(LIST_X * k - 40 * k, (LIST_TOP - 30) * k, w, LIST_BOTTOM * k + 40 * k);
            for (int i = 0; i < rows.size(); i++) drawRow(c, i);
            c.restore();
            if (moving) {
                c.drawText(Ui.tr("↑ ↓ ile taşıyın, bitince OK", "↑ ↓ to move, OK when done"), LIST_X * k + 30 * k, (LIST_BOTTOM + 52) * k, hint);
            }
            if (menuPop.value > 0.01f) drawMenu(c);
        }

        private String pageSymbol() {
            switch (page) {
                case PAGE_DOCK: return "apps";
                case PAGE_ADD: return "add";
                case PAGE_CONTROL: return "control";
                case PAGE_HOME: return "home";
                case PAGE_CITY: return "pin";
                case PAGE_WALL: return "wallpaper";
                case PAGE_PICK: return "photo_library";
                case PAGE_LIGHTS: return "lightbulb";
                case PAGE_WIFI: return "wifi";
                case PAGE_MUSIC: return "music_note";
                case PAGE_PRIVACY: return "check";
                default: return "tune";
            }
        }

        private String pageTitle() {
            switch (page) {
                case PAGE_DOCK: return Ui.tr("Ana Ekrandaki Uygulamalar", "Apps on Home");
                case PAGE_ADD: return Ui.tr("Uygulama Ekle", "Add App");
                case PAGE_CONTROL: return Ui.tr("Kontrol Merkezi", "Control Center");
                case PAGE_HOME: return Ui.tr("Ana Ekran", "Home Screen");
                case PAGE_CITY: return Ui.tr("Şehir Seçin", "Pick Your City");
                case PAGE_WALL: return Ui.tr("Duvar Kağıdı", "Wallpaper");
                case PAGE_PICK: return Ui.tr("Fotoğraf Seçin", "Pick a Photo");
                case PAGE_LIGHTS: return Ui.tr("Işıklar", "Lights");
                case PAGE_WIFI: return Ui.tr("Misafir Wi-Fi", "Guest Wi-Fi");
                case PAGE_MUSIC: return Ui.tr("Müzik Uygulaması", "Music App");
                case PAGE_PRIVACY: return Ui.tr("Gizlilik", "Privacy");
                default: return Ui.tr("Özelleştir", "Customize");
            }
        }

        private String pageHint() {
            switch (page) {
                case PAGE_DOCK: return Ui.tr("Taşımak ya da kaldırmak için OK", "OK on an app to move or remove it");
                case PAGE_ADD: return Ui.tr("Ana ekrana eklemek için OK", "OK adds it to Home");
                case PAGE_CONTROL: return Ui.tr("Gizlemek ya da taşımak için OK", "OK on a control to hide or move it");
                case PAGE_HOME: return Ui.tr("Görünüm, selamlama, hava ve piyasalar", "Layout, greeting, weather and markets");
                case PAGE_CITY: return Ui.tr("Hava durumu bu şehir için gösterilir", "The weather follows this city");
                case PAGE_WALL: return Ui.tr("Seçtiğiniz hemen arkada görünür", "What you choose shows right behind");
                case PAGE_PICK: return Ui.tr("Gezinirken arkada görünür, OK ile seçin", "Browse to preview, OK to choose");
                case PAGE_LIGHTS: return Ui.tr("WiZ, LIFX, Govee, Hue, Home Assistant", "WiZ, LIFX, Govee, Hue, Home Assistant");
                case PAGE_WIFI: return Ui.tr("Telefonlar şifre yazmadan bağlanır", "Phones join without typing the password");
                case PAGE_MUSIC: return Ui.tr("Spotify, YouTube Music ya da başkası", "Spotify, YouTube Music or any other");
                case PAGE_PRIVACY: return Ui.tr("Verileriniz sizde kalır", "Your data stays with you");
                default: return Ui.tr("Uygulamalar, ekran, ışıklar, Wi-Fi ve müzik", "Apps, screen, lights, Wi-Fi and music");
            }
        }

        private void drawRow(Canvas c, int i) {
            Row r = rows.get(i);
            float y = (LIST_TOP + r.top) * k - scroll.value;
            float rh = (r.header ? HEADER_H : ROW_H) * k;
            if (y + rh < (LIST_TOP - 30) * k || y > (LIST_BOTTOM + 40) * k) return;
            if (r.header) {
                if (!r.label.isEmpty()) c.drawText(r.label, LIST_X * k + 30 * k, y + rh - 16 * k, header);
                return;
            }
            rowRect.set(LIST_X * k, y, LIST_R * k, y + rh);
            float f = Ui.clamp(pop.get(i).value);
            boolean lifted = moving && i == focus;
            if (f > 0.01f) {
                float s = 1 + (lifted ? 0.04f : 0.02f) * f;
                float mx = rowRect.centerX(), my = rowRect.centerY();
                tmp.set(mx - rowRect.width() * s / 2, my - rowRect.height() * s / 2, mx + rowRect.width() * s / 2, my + rowRect.height() * s / 2);
                float rad = tmp.height() / 2;   // a capsule, like the dock's focus
                tmp.offset(0, (lifted ? 16 : 10) * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, (lifted ? 0.55f : 0.4f) * f));
                c.drawRoundRect(tmp, rad, rad, shadow);
                tmp.offset(0, -(lifted ? 16 : 10) * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, rad, rad, white);
                rowRect.set(tmp);
            }
            int ink = blend(r.dim ? 0x80FFFFFF : 0xF2FFFFFF, r.dim ? 0x991C1C1E : INK, f);
            float x = rowRect.left + 34 * k, cy = rowRect.centerY();
            if (r.symbol != null) {
                // Every mark in the same small box: wide ones (Prime's smile, "max") are scaled down to fit.
                float size = 26 * k, sw = Symbols.width(getContext(), r.symbol, size);
                if (sw > 34 * k) size *= 34 * k / sw;
                Symbols.draw(getContext(), c, r.symbol, x + 17 * k, cy, size, ink);
                x += 58 * k;
            }
            rowLabel.setColor(ink);
            float right = rowRect.right - 34 * k;
            if (lifted) {
                float sw = Symbols.width(getContext(), "swap_vert", 26 * k);
                Symbols.draw(getContext(), c, "swap_vert", right - sw / 2, cy, 26 * k, INK);
            } else if (r.chevron) {
                float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, cy, 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                right -= cw + 18 * k;
            }
            String value = lifted ? "" : r.value;
            rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
            float valueMax = Math.max(380 * k, right - x - rowLabel.measureText(r.label) - 40 * k);
            float valueW = value.isEmpty() ? 0 : Math.min(rowValue.measureText(value), valueMax);
            if (!value.isEmpty()) {
                c.drawText(TextUtils.ellipsize(value, new TextPaint(rowValue), valueMax, TextUtils.TruncateAt.END).toString(), right,
                        cy + Ui.halfCap(rowValue), rowValue);
            }
            float room = right - valueW - 24 * k - x;
            c.drawText(TextUtils.ellipsize(r.label, new TextPaint(rowLabel), room, TextUtils.TruncateAt.END).toString(), x,
                    cy + Ui.halfCap(rowLabel), rowLabel);
        }

        /** The action menu: a small glass card left of the focused row (Apple TV's long-press menu). */
        private void drawMenu(Canvas c) {
            if (menu == null) return;
            float a = Ui.clamp(menuPop.value);
            Row r = rows.get(focus);
            float rowY = (LIST_TOP + r.top) * k - scroll.value;
            float mh = (menu.length * MENU_ROW + 20) * k, mw = MENU_W * k;
            float right = LIST_X * k - 28 * k, top = Math.max(60 * k, Math.min(h - mh - 60 * k, rowY + ROW_H * k / 2 - mh / 2));
            float s = 0.92f + 0.08f * a;
            c.save();
            c.scale(s, s, right, top + mh / 2);
            tmp.set(right - mw, top, right, top + mh);
            shadow.setColor(Ui.alpha(0xFF000000, 0.5f * a));
            tmp.offset(0, 12 * k);
            c.drawRoundRect(tmp, 32 * k, 32 * k, shadow);
            tmp.offset(0, -12 * k);
            if (scene.photoGlassReady()) {
                scene.glass(c, tmp, 32 * k, 0xFF2C2C2E, 1.3f, 0, 0, 0.02f, 1, a);     // Android 13+: Liquid Glass
            } else {
                glassFill.setColor(Ui.alpha(0xFF2C2C2E, 0.94f * a));
                c.drawRoundRect(tmp, 32 * k, 32 * k, glassFill);
            }
            for (int i = 0; i < menu.length; i++) {
                float y = top + (10 + i * MENU_ROW) * k;
                rowRect.set(right - mw + 10 * k, y, right - 10 * k, y + MENU_ROW * k);
                boolean on = i == menuFocus;
                if (on) {
                    white.setAlpha(Math.round(255 * a));
                    c.drawRoundRect(rowRect, rowRect.height() / 2, rowRect.height() / 2, white);
                }
                boolean danger = menu[i].equals(removeLabel());
                menuText.setColor(Ui.alpha(on ? INK : danger ? 0xFFFF6961 : 0xFFFFFFFF, 0.95f * a));
                c.drawText(menu[i], rowRect.left + 26 * k, rowRect.centerY() + Ui.halfCap(menuText), menuText);
            }
            c.restore();
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
                    if (down) vertical(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
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
                        if (backDown && !e.isCanceled()) back();
                        backDown = false;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void vertical(int d) {
            if (menu != null) {
                menuFocus = Math.max(0, Math.min(menu.length - 1, menuFocus + d));
            } else if (moving) {
                moveFocused(d);
            } else {
                focus = next(focus, d);
                if (page == PAGE_PICK) {
                    handler.removeCallbacks(previewFocused);
                    handler.postDelayed(previewFocused, 350);
                }
            }
            invalidate();
        }

        private void back() {
            if (menu != null) {
                menu = null;
            } else if (moving) {
                moving = false;
                rebuild();
            } else if (page == entryPage && page != PAGE_ADD && page != PAGE_CITY && page != PAGE_PICK) {
                activity.finish();
                return;
            } else {
                int from = page;
                if (from == PAGE_PICK) {        // back without choosing: the saved wallpaper again
                    handler.removeCallbacks(previewFocused);
                    Photos.get(getContext()).refresh();
                }
                page = page == PAGE_ADD ? PAGE_DOCK : page == PAGE_CITY ? PAGE_HOME : page == PAGE_PICK ? PAGE_WALL : PAGE_MAIN;
                rebuild();
                focus = 0;
                String id = from == PAGE_DOCK ? "dock" : from == PAGE_CONTROL ? "control" : from == PAGE_HOME ? "home"
                        : from == PAGE_ADD ? "add" : from == PAGE_WALL ? "wall" : from == PAGE_PICK ? "pick"
                        : from == PAGE_LIGHTS ? "lights" : from == PAGE_WIFI ? "wifi" : from == PAGE_MUSIC ? "music"
                        : from == PAGE_PRIVACY ? "privacy" : "city";
                for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) focus = i;
                snapScroll();
            }
            invalidate();
        }

        private String removeLabel() {
            return Ui.tr("Kaldır", "Remove");
        }

        private void ok() {
            if (rows.isEmpty()) return;
            if (menu != null) {
                choose(menu[menuFocus]);
                return;
            }
            if (moving) {
                moving = false;
                rebuild();
                invalidate();
                return;
            }
            Row r = rows.get(focus);
            if (r.header) return;
            Context c = getContext();
            switch (page) {
                case PAGE_MAIN:
                    switch (r.id) {
                        case "dock": open(PAGE_DOCK); break;
                        case "control": open(PAGE_CONTROL); break;
                        case "wall": open(PAGE_WALL); break;
                        case "lights": openLights(); break;
                        case "wifi": open(PAGE_WIFI); break;
                        case "music": openMusic(); break;
                        case "privacy": open(PAGE_PRIVACY); break;
                        case "setup": SetupActivity.again(activity); break;
                        default: open(PAGE_HOME);
                    }
                    break;
                case PAGE_LIGHTS:
                    lights(r);
                    break;
                case PAGE_WIFI:
                    wifi(r.id);
                    break;
                case PAGE_MUSIC:
                    Prefs.put(c, "music_app", r.id.equals(MediaListener.YT_MUSIC) ? null : r.id);
                    page = PAGE_MAIN;
                    rebuild();
                    for (int j = 0; j < rows.size(); j++) if (rows.get(j).id.equals("music")) focus = j;
                    snapScroll();
                    break;
                case PAGE_PRIVACY:
                    break;
                case PAGE_DOCK:
                    if (r.id.equals("add")) {
                        if (dock.size() < HomeApps.MAX_SHELF) {
                            apps = HomeApps.launchable(c);
                            open(PAGE_ADD);
                        }
                    } else if (r.id.equals("reset")) {
                        Prefs.setDock(c, null);
                        load();
                        rebuild();
                    } else {
                        openMenu(Ui.tr("Taşı", "Move"), removeLabel());
                    }
                    break;
                case PAGE_ADD:
                    dock.add(r.id);
                    Prefs.setDock(c, dock);
                    page = PAGE_DOCK;
                    rebuild();
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(r.id)) focus = i;
                    break;
                case PAGE_CONTROL:
                    if (r.id.equals("reset")) {
                        Prefs.resetModules(c);
                        load();
                        rebuild();
                    } else {
                        Prefs.Module m = module(r.id);
                        openMenu(m != null && m.on ? Ui.tr("Gizle", "Hide") : Ui.tr("Göster", "Show"), Ui.tr("Taşı", "Move"));
                    }
                    break;
                case PAGE_HOME:
                    home(r.id);
                    break;
                case PAGE_WALL:
                    wallpaper(r.id);
                    break;
                case PAGE_PICK:
                    handler.removeCallbacks(previewFocused);
                    Prefs.put(c, "wallpaper_photo", r.id.equals("auto") ? null : r.id);
                    Photos.get(c).refresh();
                    page = PAGE_WALL;
                    rebuild();
                    for (int j = 0; j < rows.size(); j++) if (rows.get(j).id.equals("pick")) focus = j;
                    snapScroll();
                    break;
                default:
                    int i = Integer.parseInt(r.id.substring(5));
                    String[] p = places.get(i);
                    Prefs.setPlace(c, p[0], Double.parseDouble(p[2]), Double.parseDouble(p[3]));
                    page = PAGE_HOME;
                    rebuild();
                    for (int j = 0; j < rows.size(); j++) if (rows.get(j).id.equals("city")) focus = j;
            }
            invalidate();
        }

        private void open(int to) {
            page = to;
            focus = 0;
            rebuild();
            snapScroll();
        }

        private void openMenu(String... options) {
            menu = options;
            menuFocus = 0;
        }

        private void choose(String option) {
            menu = null;
            Context c = getContext();
            String id = rows.get(focus).id;
            if (page == PAGE_LIGHTS) {
                lightsMenu(option, id);
                invalidate();
                return;
            }
            if (option.equals(Ui.tr("Taşı", "Move"))) {
                moving = true;
            } else if (option.equals(removeLabel())) {
                dock.remove(id);
                Prefs.setDock(c, dock);
                rebuild();
            } else {   // Show / Hide
                Prefs.Module m = module(id);
                if (m != null) {
                    m.on = !m.on;
                    Prefs.setModules(c, buttons, tiles);
                }
                rebuild();
            }
            invalidate();
        }

        private Prefs.Module module(String id) {
            for (Prefs.Module m : buttons) if (m.id.equals(id)) return m;
            for (Prefs.Module m : tiles) if (m.id.equals(id)) return m;
            return null;
        }

        /** Moves the lifted row one place up or down, within its own list (dock apps; buttons; tiles). */
        private void moveFocused(int d) {
            String id = rows.get(focus).id;
            Context c = getContext();
            if (page == PAGE_DOCK) {
                int i = dock.indexOf(id), j = i + d;
                if (i < 0 || j < 0 || j >= dock.size()) return;
                dock.remove(i);
                dock.add(j, id);
                Prefs.setDock(c, dock);
            } else if (page == PAGE_CONTROL) {
                List<Prefs.Module> group = module(id) != null && buttons.contains(module(id)) ? buttons : tiles;
                int i = group.indexOf(module(id)), j = i + d;
                if (i < 0 || j < 0 || j >= group.size()) return;
                group.add(j, group.remove(i));
                Prefs.setModules(c, buttons, tiles);
            } else {
                return;
            }
            rebuild();
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) focus = i;
        }

        // MARK: - Wallpaper

        private void wallpaper(String id) {
            Context c = getContext();
            Photos photos = Photos.get(c);
            switch (id) {
                case "pick": {
                    open(PAGE_PICK);
                    String fixed = Prefs.wallpaperPhoto();
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(fixed)) focus = i;
                    snapScroll();
                    break;
                }
                case "today":
                    break;
                case "dim": {
                    int d = Prefs.wallpaperDim();
                    Prefs.put(c, "wallpaper_dim", String.valueOf(d >= 45 ? 0 : d + 15));
                    photos.refresh();
                    rebuild();
                    break;
                }
                default: {   // one of Prefs.WALLPAPERS
                    if (!id.equals(Prefs.wallpaper())) Prefs.put(c, "wallpaper_photo", null);   // a photo of the other set
                    Prefs.put(c, "wallpaper", id);
                    photos.refresh();
                    rebuild();
                }
            }
        }

        // MARK: - Home screen settings

        private void home(String id) {
            Context c = getContext();
            switch (id) {
                case "layout":
                    Prefs.put(c, "layout", Prefs.layout().equals("list") ? "dock" : "list");
                    rebuild();
                    break;
                case "name":
                    ask(Ui.tr("Selamlama adı", "Greeting name"), Prefs.name(), false, text -> {
                        Prefs.put(c, "name", text.trim());
                        rebuild();
                    });
                    break;
                case "city":
                    ask(Ui.tr("Şehir", "City"), Prefs.city(), false, this::searchCity);
                    break;
                case "temperature": {
                    String t = Prefs.temperature().toUpperCase(Locale.ROOT);
                    Prefs.put(c, "temperature", t.startsWith("C") ? "F" : t.startsWith("F") ? "auto" : "C");
                    rebuild();
                    break;
                }
                case "glass":
                    Prefs.put(c, "glass", Prefs.glass().equals("auto") ? "classic" : "auto");
                    Photos.get(c).glassChanged();
                    rebuild();
                    break;
                case "sound":
                    Prefs.put(c, "click_sound", !Prefs.clickSound());
                    rebuild();
                    break;
                case "stocks":
                    ask(Ui.tr("Yahoo sembolleri, virgülle (örn. ^GSPC:S&P, BTC-USD:BTC, AAPL)",
                            "Yahoo symbols, comma-separated (e.g. ^GSPC:S&P, BTC-USD:BTC, AAPL)"), Prefs.stocks(), true, text -> {
                        StringBuilder clean = new StringBuilder();
                        for (String s : Prefs.split(text)) clean.append(clean.length() == 0 ? "" : ",").append(s);
                        Prefs.put(c, "stocks", clean.toString());
                        rebuild();
                    });
                    break;
                default: {   // language: in the shared state file, like Settings does it
                    JSONObject s = HomeMode.state(c);
                    try {
                        s.put("language", Ui.en ? "tr" : "en");
                    } catch (org.json.JSONException ignored) {
                    }
                    HomeMode.save(c, s);
                    Ui.loadLanguage(c);
                    rebuild();
                }
            }
        }

        // MARK: - Lights

        private void openLights() {
            open(PAGE_LIGHTS);
            searchLamps();
        }

        private void searchLamps() {
            lightsState = "searching";
            rebuild();
            invalidate();
            final Context c = getContext().getApplicationContext();
            io.execute(() -> {
                final List<Lights.Lamp> found = Lights.all(c, 1800);
                handler.post(() -> {
                    lampsAll = found;
                    lightsState = "";
                    if (page == PAGE_LIGHTS && menu == null) rebuild();
                    invalidate();
                });
            });
        }

        private Lights.Lamp lamp(String key) {
            for (Lights.Lamp l : lampsAll) if (l.key().equals(key)) return l;
            return null;
        }

        private void lights(Row r) {
            final Context c = getContext().getApplicationContext();
            switch (r.id) {
                case "search":
                    if (!lightsState.equals("searching")) searchLamps();
                    break;
                case "hue":
                    if (Prefs.has("hue_user")) openMenu(Ui.tr("Bağlantıyı kes", "Disconnect"));
                    else findService(Hue.SERVICE, ip -> confirmHue(stripPort(ip), null),
                            () -> ask(Ui.tr("Hue Köprüsü'nün adresi (IP)", "The Hue Bridge's address (IP)"),
                                    Ui.tr("Hue uygulaması → Ayarlar → Köprüler → (i)", "Hue app → Settings → Bridges → (i)"),
                                    "", FIELD_CODE, ip -> confirmHue(ip.trim(), null)));
                    break;
                case "ha":
                    if (Prefs.has("ha_token")) openMenu(Ui.tr("Bağlantıyı kes", "Disconnect"));
                    else findService(HomeAssistant.SERVICE, this::askHaUrl, () -> askHaUrl(""));
                    break;
                default: {
                    if (lamp(r.id) == null) return;
                    openMenu(Ui.tr("Yeniden adlandır", "Rename"), Ui.tr("Yanıp sön", "Blink"),
                            Prefs.lampHidden(r.id) ? Ui.tr("Göster", "Show") : Ui.tr("Gizle", "Hide"));
                }
            }
        }

        private void lightsMenu(String option, final String id) {
            final Context c = getContext().getApplicationContext();
            if (option.equals(Ui.tr("Bağlantıyı kes", "Disconnect"))) {
                if (id.equals("hue")) {
                    Prefs.put(c, "hue_ip", null);
                    Prefs.put(c, "hue_user", null);
                } else {
                    Prefs.put(c, "ha_url", null);
                    Prefs.put(c, "ha_token", null);
                }
                searchLamps();
                return;
            }
            final Lights.Lamp l = lamp(id);
            if (l == null) return;
            if (option.equals(Ui.tr("Yeniden adlandır", "Rename"))) {
                ask(Ui.tr("Lambanın adı", "The lamp's name"), l.name(), false, text -> {
                    Prefs.setLampName(c, id, text);
                    rebuild();
                });
            } else if (option.equals(Ui.tr("Yanıp sön", "Blink"))) {
                io.execute(() -> Lights.blink(c, l));
            } else {
                Prefs.setLampHidden(c, id, !Prefs.lampHidden(id));
                rebuild();
            }
        }

        interface Found { void at(String hostPort); }

        /** Looks for a device on the home network (mDNS); `none` when nothing answered in 3 seconds. */
        private void findService(final String type, final Found found, final Runnable none) {
            lightsState = "connecting";
            rebuild();
            invalidate();
            final Context c = getContext().getApplicationContext();
            io.execute(() -> {
                final List<String> list = Nsd.find(c, type, 3000);
                handler.post(() -> {
                    lightsState = "";
                    rebuild();
                    if (list.isEmpty()) none.run();
                    else found.at(list.get(0));
                    invalidate();
                });
            });
        }

        private static String stripPort(String hostPort) {
            int colon = hostPort.lastIndexOf(':');
            return colon > 0 && hostPort.indexOf(':') == colon ? hostPort.substring(0, colon) : hostPort;
        }

        /** Hue: press the bridge's button, then Connect (within 30 s). Asked again while the button wasn't pressed. */
        private void confirmHue(final String ip, String problem) {
            if (ip.isEmpty()) return;
            final Context c = getContext().getApplicationContext();
            String msg = (problem != null ? problem + "\n\n" : "")
                    + Ui.tr("Hue Köprüsü'nün (" + ip + ") üstündeki yuvarlak düğmeye basın, sonra 30 saniye içinde Bağlan'ı seçin.",
                            "Press the round button on top of the Hue Bridge (" + ip + "), then choose Connect within 30 seconds.")
                    + "\n\n" + Ui.tr("Köprünün verdiği anahtar yalnızca bu TV'de, şifreli saklanır.",
                            "The key the bridge gives stays on this TV only, encrypted.");
            dialog("Philips Hue", msg, Ui.tr("Bağlan", "Connect"), () -> {
                lightsState = "connecting";
                rebuild();
                io.execute(() -> {
                    String error = null;
                    boolean retry = false;
                    try {
                        String user = Hue.pair(ip);
                        Prefs.put(c, "hue_ip", ip);
                        if (!Prefs.putSecret(c, "hue_user", user)) error = Ui.tr("TV anahtarı güvenle saklayamadı.", "The TV couldn't store the key securely.");
                    } catch (Hue.ButtonNotPressed e) {
                        retry = true;
                    } catch (Exception e) {
                        error = e.getMessage();
                    }
                    final String err = error;
                    final boolean again = retry;
                    handler.post(() -> {
                        lightsState = "";
                        if (again) confirmHue(ip, Ui.tr("Düğmeye henüz basılmadı.", "The button wasn't pressed yet."));
                        else if (err != null) dialog("Philips Hue", err, null, null);
                        else searchLamps();
                        rebuild();
                        invalidate();
                    });
                });
            });
        }

        private void askHaUrl(String found) {
            String suggestion = !Prefs.haUrl().isEmpty() ? Prefs.haUrl() : found.isEmpty() ? "http://" : "http://" + found;
            ask(Ui.tr("Home Assistant adresi", "Home Assistant's address"),
                    Ui.tr("Örn. http://192.168.1.10:8123 — düz http yalnızca ev ağında kullanılır.",
                            "E.g. http://192.168.1.10:8123. Plain http is only used inside your home network."),
                    suggestion, FIELD_CODE, url -> {
                        if (HomeAssistant.clean(url).length() > 8) askHaToken(HomeAssistant.clean(url));
                    });
        }

        private void askHaToken(final String url) {
            final Context c = getContext().getApplicationContext();
            ask(Ui.tr("Uzun ömürlü erişim anahtarı", "Long-lived access token"),
                    Ui.tr("Home Assistant → profiliniz → Güvenlik → Uzun ömürlü erişim anahtarları. Telefondan Google TV uygulamasının klavyesiyle yapıştırabilirsiniz. Yalnızca bu TV'de, şifreli saklanır.",
                            "Home Assistant → your profile → Security → Long-lived access tokens. You can paste it from your phone with the Google TV app's keyboard. Kept on this TV only, encrypted."),
                    "", FIELD_SECRET, token -> {
                        final String t = token.trim();
                        if (t.isEmpty()) return;
                        lightsState = "connecting";
                        rebuild();
                        io.execute(() -> {
                            String error = HomeAssistant.check(url, t);
                            if (error == null) {
                                Prefs.put(c, "ha_url", url);
                                if (!Prefs.putSecret(c, "ha_token", t)) error = Ui.tr("TV anahtarı güvenle saklayamadı.", "The TV couldn't store the token securely.");
                            }
                            final String err = error;
                            handler.post(() -> {
                                lightsState = "";
                                if (err != null) dialog("Home Assistant", Ui.tr("Bağlanamadı: ", "Couldn't connect: ") + err, null, null);
                                else searchLamps();
                                rebuild();
                                invalidate();
                            });
                        });
                    });
        }

        /** A message with one action (or just OK when `action` is null). */
        private void dialog(String title, String message, String action, final Runnable then) {
            Dialogs.message(activity, title, message, action, then, this::invalidate);
        }

        // MARK: - Guest Wi-Fi and the music app

        private void wifi(String id) {
            final Context c = getContext();
            switch (id) {
                case "ssid":
                    ask(Ui.tr("Ağ adı (SSID)", "Network name (SSID)"), Prefs.wifiSsid(), true, text -> {
                        Prefs.put(c, "wifi_ssid", text.trim());
                        rebuild();
                    });
                    break;
                case "password":
                    if (Prefs.wifiSecurity().equals("nopass")) return;
                    ask(Ui.tr("Wi-Fi şifresi", "Wi-Fi password"),
                            Ui.tr("Yalnızca bu TV'de, şifreli saklanır; QR kodunda ve misafir ekranında görünür.",
                                    "Kept on this TV only, encrypted; shown in the QR code and on the guest screen."),
                            Prefs.wifiPassword(), FIELD_SECRET, text -> {
                                if (!Prefs.putSecret(c, "wifi_password", text)) {
                                    dialog(Ui.tr("Misafir Wi-Fi", "Guest Wi-Fi"), Ui.tr("TV şifreyi güvenle saklayamadı.", "The TV couldn't store the password securely."), null, null);
                                }
                                rebuild();
                            });
                    break;
                case "security": {
                    String s = Prefs.wifiSecurity();
                    Prefs.put(c, "wifi_security", s.equals("WPA") ? "nopass" : s.equals("nopass") ? "WEP" : "WPA");
                    rebuild();
                    break;
                }
                case "show":
                    if (Prefs.wifiSsid().isEmpty()) return;
                    activity.startActivity(new android.content.Intent().setComponent(new android.content.ComponentName(activity, InfoActivity.class))
                            .putExtra(InfoActivity.EXTRA, InfoActivity.WIFI));
                    break;
                default:    // Remove: empty (not the config.properties defaults)
                    Prefs.put(c, "wifi_ssid", "");
                    Prefs.putSecret(c, "wifi_password", "");
                    Prefs.put(c, "wifi_security", null);
                    rebuild();
            }
        }

        private void openMusic() {
            open(PAGE_MUSIC);
            String chosen = Prefs.musicApp();
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(chosen)) focus = i;
            snapScroll();
        }

        private void ask(String question, String current, boolean code, final Dialogs.Answer answer) {
            ask(question, null, current, code ? FIELD_CODE : FIELD_WORDS, answer);
        }

        private static final int FIELD_WORDS = Dialogs.FIELD_WORDS, FIELD_CODE = Dialogs.FIELD_CODE, FIELD_SECRET = Dialogs.FIELD_SECRET;

        private void ask(String question, String note, String current, int kind, final Dialogs.Answer answer) {
            Dialogs.ask(activity, question, note, current, kind, answer, this::invalidate);
        }

        /** Open-Meteo's geocoding (Geocode): up to 8 places with that name, then the list to pick from. */
        private void searchCity(final String query) {
            if (query.trim().isEmpty()) return;
            places.clear();
            placesState = "searching";
            open(PAGE_CITY);
            invalidate();
            final String lang = Ui.en ? "en" : "tr";
            io.execute(() -> {
                List<String[]> found = new ArrayList<>();
                String state;
                try {
                    found = Geocode.search(query, lang);
                    state = found.isEmpty() ? "none" : "";
                } catch (Exception e) {
                    AppLog.w("geocoding: " + e.getMessage());
                    state = "error";
                }
                final List<String[]> result = found;
                final String s = state;
                handler.post(() -> {
                    places.clear();
                    places.addAll(result);
                    placesState = s;
                    if (page == PAGE_CITY) {
                        focus = 0;
                        rebuild();
                        snapScroll();
                    }
                    invalidate();
                });
            });
        }
    }
}
