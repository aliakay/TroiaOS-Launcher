package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Customize, in the style of the settings: the dock's apps (add, move, remove), Control Center's controls (show, hide,
 * move) and the home screen (greeting, weather city, temperature unit, watchlist, language). Everything is saved in
 * Prefs at once; the home screen and Control Center pick it up when they come back.
 *
 * Opened from Settings, from "Edit" in Control Center (straight to its page) and by holding OK on a dock app (straight
 * to the dock, on that app). Back from a page it was opened on closes it.
 */
public class CustomizeActivity extends Activity {
    static final String EXTRA_PAGE = "page", EXTRA_PKG = "pkg";
    static final int PAGE_MAIN = 0, PAGE_DOCK = 1, PAGE_ADD = 2, PAGE_CONTROL = 3, PAGE_HOME = 4, PAGE_CITY = 5;

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

    static final class CustomizeView extends View implements Choreographer.FrameCallback {
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
            if (pkg != null) {
                for (int i = 0; i < rows.size(); i++) if (pkg.equals(rows.get(i).id)) focus = i;
            }
            snapScroll();
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
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
                    r = item("language", "Dil / Language", "home");
                    r.value = Ui.en ? "English" : "Türkçe";
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
                default: return Ui.tr("Uygulamalar, kontroller ve ana ekran", "Your apps, controls and home screen");
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
            float valueW = value.isEmpty() ? 0 : Math.min(rowValue.measureText(value), 380 * k);
            if (!value.isEmpty()) {
                c.drawText(TextUtils.ellipsize(value, new TextPaint(rowValue), 380 * k, TextUtils.TruncateAt.END).toString(), right,
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
            glassFill.setColor(Ui.alpha(0xFF2C2C2E, 0.94f * a));
            c.drawRoundRect(tmp, 32 * k, 32 * k, glassFill);
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
            }
            invalidate();
        }

        private void back() {
            if (menu != null) {
                menu = null;
            } else if (moving) {
                moving = false;
                rebuild();
            } else if (page == entryPage && page != PAGE_ADD && page != PAGE_CITY) {
                activity.finish();
                return;
            } else {
                int from = page;
                page = page == PAGE_ADD ? PAGE_DOCK : page == PAGE_CITY ? PAGE_HOME : PAGE_MAIN;
                rebuild();
                focus = 0;
                String id = from == PAGE_DOCK ? "dock" : from == PAGE_CONTROL ? "control" : from == PAGE_HOME ? "home"
                        : from == PAGE_ADD ? "add" : "city";
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
                    open(r.id.equals("dock") ? PAGE_DOCK : r.id.equals("control") ? PAGE_CONTROL : PAGE_HOME);
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

        interface Answer { void text(String value); }

        /** A text field in a dialog: the TV's keyboard comes up with it. */
        private void ask(String question, String current, boolean code, final Answer answer) {
            final EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setText(current);
            field.setSelection(field.getText().length());
            field.setInputType(code ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            int pad = Math.round(28 * k);
            field.setPadding(pad, pad / 2, pad, pad / 2);
            final AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(question)
                    .setView(field)
                    .setPositiveButton(Ui.tr("Kaydet", "Save"), (d, which) -> answer.text(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Vazgeç", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                if (action == EditorInfo.IME_ACTION_DONE) {
                    answer.text(field.getText().toString());
                    dialog.dismiss();
                    return true;
                }
                return false;
            });
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            dialog.setOnDismissListener(d -> invalidate());
            dialog.show();
        }

        /** Open-Meteo's geocoding (no key): up to 8 places with that name, then the list to pick from. */
        private void searchCity(final String query) {
            if (query.trim().isEmpty()) return;
            places.clear();
            placesState = "searching";
            open(PAGE_CITY);
            invalidate();
            final String lang = Ui.en ? "en" : "tr";
            io.execute(() -> {
                final List<String[]> found = new ArrayList<>();
                String state;
                try {
                    String body = get("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=" + lang
                            + "&name=" + URLEncoder.encode(query.trim(), "UTF-8"));
                    JSONArray results = new JSONObject(body).optJSONArray("results");
                    for (int i = 0; results != null && i < results.length(); i++) {
                        JSONObject o = results.getJSONObject(i);
                        String detail = o.optString("admin1", "");
                        String country = o.optString("country", "");
                        if (!country.isEmpty()) detail = detail.isEmpty() ? country : detail + ", " + country;
                        found.add(new String[] {o.optString("name"), detail, String.valueOf(o.getDouble("latitude")),
                                String.valueOf(o.getDouble("longitude"))});
                    }
                    state = found.isEmpty() ? "none" : "";
                } catch (Exception e) {
                    AppLog.w("geocoding: " + e.getMessage());
                    state = "error";
                }
                final String s = state;
                handler.post(() -> {
                    places.clear();
                    places.addAll(found);
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

        private static String get(String url) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                c.disconnect();
            }
        }
    }
}
