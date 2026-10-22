package io.freemytv;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * A small glass panel under the home screen's corner buttons, like the lights panel: the weather in detail (now, the
 * next hours, sunrise and sunset), the watchlist (price, change and today's curve), or the guest Wi-Fi as a QR code
 * that phones scan to join. Only to look at: OK or Back closes it.
 */
public class InfoActivity extends Activity {
    static final String EXTRA = "panel", WEATHER = "weather", MARKETS = "markets", WIFI = "wifi";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        String panel = getIntent().getStringExtra(EXTRA);
        setContentView(new InfoView(this, MARKETS.equals(panel), WIFI.equals(panel)));
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        Window win = getWindow();
        WindowManager.LayoutParams lp = win.getAttributes();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(70 * k);
        lp.y = Math.round(196 * k);
        lp.width = Math.round(InfoView.W * k);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.3f;
        win.setAttributes(lp);
        win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // Only a press that started here closes it (the OK that opened the panel ends here as a release).
            if (e.getAction() == KeyEvent.ACTION_UP && e.getDownTime() >= openedAt && !e.isCanceled()) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final long openedAt = android.os.SystemClock.uptimeMillis();

    static final class InfoView extends View implements HomeData.Listener, Stocks.Listener {
        static final float W = 760;
        private static final float PAD = 36, ROW = 76, WEATHER_H = 420, QR_BOX = 330, WIFI_H = 402;
        /** Colors for dark glass (night) and light glass (day), like Apple's dark and light appearance. */
        private int textColor, dimColor, upColor, downColor, symbolColor;

        private final boolean markets, wifi;
        /** The guest Wi-Fi's QR code (modules [y][x]), made once. */
        private final boolean[][] qr;
        private final HomeData data;
        private final Stocks stocks;
        private final LiquidGlass glass = new LiquidGlass();
        private final RectF panel = new RectF();
        private final Path curve = new Path();
        private final int[] onScreen = new int[2];
        private float k = 1;
        private Paint title, dim, dimRight, big, text, number, numberRight, change, hour, hourTemp, line, dotted, divider;

        InfoView(Activity a, boolean markets, boolean wifi) {
            super(a);
            this.markets = markets;
            this.wifi = wifi;
            Prefs.load(a);
            qr = wifi ? QrCode.encode(QrCode.wifi(Prefs.wifiSsid(), Prefs.wifiPassword(), Prefs.wifiSecurity())) : null;
            data = HomeData.get(a);
            stocks = Stocks.get(a);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            data.addListener(this);
            stocks.addListener(this);
            data.refreshIfStale();
            if (markets) stocks.refreshIfStale();
        }

        @Override protected void onDetachedFromWindow() {
            data.removeListener(this);
            stocks.removeListener(this);
            super.onDetachedFromWindow();
        }

        @Override public void onData() { invalidate(); }

        @Override public void onStocks() { invalidate(); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            float kk = width / W;
            float height = wifi ? WIFI_H : markets ? 96 + Math.max(1, stocks.quotes.size()) * ROW + 20 : WEATHER_H;
            setMeasuredDimension(width, Math.round(height * kk));
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            k = w / W;
            title = Ui.text(Ui.semibold, 30 * k, 0xF2FFFFFF);
            dim = Ui.numbers(Ui.regular, 22 * k, 0x99FFFFFF);
            dimRight = Ui.numbers(Ui.regular, 22 * k, 0x99FFFFFF);
            dimRight.setTextAlign(Paint.Align.RIGHT);
            big = Ui.numbers(Ui.light, 110 * k, 0xFFFFFFFF);
            text = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            number = Ui.text(Ui.semibold, 28 * k, 0xF2FFFFFF);
            numberRight = Ui.numbers(Ui.medium, 28 * k, 0xF2FFFFFF);
            numberRight.setTextAlign(Paint.Align.RIGHT);
            change = Ui.numbers(Ui.semibold, 22 * k, 0xFF30D158);
            change.setTextAlign(Paint.Align.RIGHT);
            hour = Ui.numbers(Ui.medium, 22 * k, 0x99FFFFFF);
            hour.setTextAlign(Paint.Align.CENTER);
            hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
            hourTemp.setTextAlign(Paint.Align.CENTER);
            line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2.5f * k);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            dotted = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotted.setStyle(Paint.Style.STROKE);
            dotted.setStrokeWidth(1.5f * k);
            dotted.setColor(0x4DFFFFFF);
            dotted.setPathEffect(new DashPathEffect(new float[] {3 * k, 5 * k}, 0));
            divider = new Paint(Paint.ANTI_ALIAS_FLAG);
            divider.setColor(0x1AFFFFFF);
        }

        /** Light glass with dark text by day; smoked glass with white text at night. */
        private void applyColors(boolean light) {
            textColor = light ? 0xFF1C1C1E : 0xF2FFFFFF;
            dimColor = light ? 0xFF6E6E73 : 0x99FFFFFF;
            upColor = light ? 0xFF248A3D : 0xFF30D158;
            downColor = light ? 0xFFD70015 : 0xFFFF453A;
            symbolColor = light ? 0xE61C1C1E : 0xE6FFFFFF;
            for (Paint p : new Paint[] {title, text, number, numberRight, hourTemp}) p.setColor(textColor);
            for (Paint p : new Paint[] {dim, dimRight, hour}) p.setColor(dimColor);
            big.setColor(light ? 0xFF1C1C1E : 0xFFFFFFFF);
            dotted.setColor(light ? 0x40000000 : 0x4DFFFFFF);
            divider.setColor(light ? 0x1A000000 : 0x1AFFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            getLocationOnScreen(onScreen);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float night = data.isNight(System.currentTimeMillis()) || Wallpaper.get().hasPhoto() ? 1 : 0;   // dark glass on photos
            applyColors(night < 0.5f);
            panel.set(0, 0, getWidth(), getHeight());
            glass.draw(c, panel, 36 * k, night < 0.5f ? 0xFFFFFFFF : 0xFF121212, night < 0.5f ? 0.62f : 0.5f,
                    dm.widthPixels, dm.heightPixels, onScreen[0], onScreen[1], night, 1, 1);
            if (wifi) drawWifi(c); else if (markets) drawMarkets(c); else drawWeather(c);
        }

        /**
         * The guest Wi-Fi: the QR code on a white card (with its quiet zone, so any phone camera reads it), and the
         * network's name and password written out for those who'd rather type.
         */
        private void drawWifi(Canvas c) {
            float x = PAD * k, top = PAD * k, box = QR_BOX * k;
            Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFFFFFFF);
            panel.set(x, top, x + box, top + box);
            c.drawRoundRect(panel, 22 * k, 22 * k, white);
            if (qr != null) {
                int n = qr.length;
                float cell = (float) Math.floor((box - 24 * k) / (n + 4) * 1f), size = cell * n;
                float ox = x + (box - size) / 2, oy = top + (box - size) / 2;
                Paint dark = new Paint();
                dark.setColor(0xFF000000);
                for (int yy = 0; yy < n; yy++) {
                    for (int xx = 0; xx < n; xx++) {
                        if (qr[yy][xx]) c.drawRect(ox + xx * cell, oy + yy * cell, ox + (xx + 1) * cell, oy + (yy + 1) * cell, dark);
                    }
                }
            }
            float tx = x + box + 40 * k;
            c.drawText(Ui.tr("Misafir Wi-Fi", "Guest Wi-Fi"), tx, top + 40 * k, title);
            c.drawText(Ui.tr("AĞ", "NETWORK"), tx, top + 104 * k, dim);
            c.drawText(Prefs.wifiSsid(), tx, top + 142 * k, text);
            boolean open = Prefs.wifiPassword().isEmpty() || Prefs.wifiSecurity().equals("nopass");
            c.drawText(Ui.tr("ŞİFRE", "PASSWORD"), tx, top + 198 * k, dim);
            c.drawText(open ? Ui.tr("Şifre yok", "No password") : Prefs.wifiPassword(), tx, top + 236 * k, text);
            c.drawText(Ui.tr("Telefonun kamerasıyla okutun,", "Scan it with your phone's camera"), tx, top + 300 * k, dim);
            c.drawText(Ui.tr("otomatik bağlanır.", "to join."), tx, top + 328 * k, dim);
        }

        private void drawWeather(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k;
            String city = Prefs.city().isEmpty() ? Ui.tr("Hava Durumu", "Weather") : Prefs.city();
            c.drawText(city, x, 66 * k, title);
            HomeData.Weather wx = data.weather;
            if (Double.isNaN(wx.temp)) {
                c.drawText(Ui.tr("Şu an hava durumu verisi yok.", "No weather data right now."), x, 130 * k, dim);
                return;
            }
            if (wx.sunrise != 0 && wx.sunset != 0) {
                c.drawText(Ui.tr("Gün doğumu ", "Sunrise ") + Ui.time(wx.sunrise) + "  ·  " + Ui.tr("Gün batımı ", "Sunset ")
                        + Ui.time(wx.sunset), right, 66 * k, dimRight);
            }
            String symbol = Symbols.weather(wx.code, wx.isDay);
            Symbols.draw(getContext(), c, symbol, x + 34 * k, 150 * k, 60 * k, symbolColor);
            String now = HomeData.degrees(wx.temp);
            c.drawText(now, x + 86 * k - Ui.leftBearing(big, now), 192 * k, big);
            float tx = x + 300 * k;
            c.drawText(HomeData.condition(wx.code, wx.isDay), tx, 150 * k, text);
            c.drawText(Ui.en ? "H: " + HomeData.degrees(wx.max) + "   L: " + HomeData.degrees(wx.min)
                    : "Y: " + HomeData.degrees(wx.max) + "   D: " + HomeData.degrees(wx.min), tx, 192 * k, dim);
            c.drawRect(x, 236 * k, right, 236 * k + Math.max(1, k), divider);
            int n = Math.min(HomeData.HOURS, wx.hours.size());
            float cw = (right - x) / Math.max(1, HomeData.HOURS);
            for (int i = 0; i < n; i++) {
                float cx = x + cw * (i + 0.5f);
                c.drawText(HomeData.hour(wx.hours.get(i)), cx, 282 * k, hour);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), cx, 326 * k, 30 * k, symbolColor);
                c.drawText(HomeData.degrees(wx.hourTemp.get(i)), cx, 382 * k, hourTemp);
            }
        }

        private void drawMarkets(Canvas c) {
            float x = PAD * k, right = getWidth() - PAD * k;
            c.drawText(Ui.tr("Piyasalar", "Markets"), x, 66 * k, title);
            String stale = HomeData.staleness(stocks.at, 2 * 60_000L);
            c.drawText(stale.isEmpty() ? "Yahoo Finance" : stale, right, 66 * k, dimRight);
            for (int i = 0; i < stocks.quotes.size(); i++) {
                Stocks.Quote q = stocks.quotes.get(i);
                float top = (96 + i * ROW) * k;
                if (i > 0) c.drawRect(x, top - Math.max(1, k), right, top, divider);
                double ch = q.change();
                int color = Double.isNaN(ch) ? dimColor : ch >= 0 ? upColor : downColor;
                c.drawText(q.label, x, top + 34 * k, number);
                String name = Stocks.name(q.label);
                if (!name.isEmpty()) c.drawText(name, x, top + 62 * k, dim);
                c.drawText(Stocks.price(q.price), right, top + 34 * k, numberRight);
                change.setColor(color);
                c.drawText(Stocks.change(ch), right, top + 62 * k, change);
                drawCurve(c, q, x + 170 * k, top + 14 * k, right - 200 * k, top + 60 * k, color);
            }
        }

        private void drawCurve(Canvas c, Stocks.Quote q, float left, float top, float right, float bottom, int color) {
            float[] p = q.curve;
            if (p.length < 2) return;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : p) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (q.previous > 0) {
                min = Math.min(min, (float) q.previous);
                max = Math.max(max, (float) q.previous);
            }
            float span = Math.max(max - min, 1e-6f);
            if (q.previous > 0) {
                float py = bottom - (bottom - top) * ((float) q.previous - min) / span;
                c.drawLine(left, py, right, py, dotted);
            }
            curve.reset();
            for (int i = 0; i < p.length; i++) {
                float px = left + (right - left) * i / (p.length - 1), py = bottom - (bottom - top) * (p[i] - min) / span;
                if (i == 0) curve.moveTo(px, py);
                else curve.lineTo(px, py);
            }
            line.setColor(color);
            c.drawPath(curve, line);
        }
    }
}
