package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * The watchlist on the home screen: price, change since the previous close and today's curve, from Yahoo Finance's
 * public chart data (no key; it may run ~15 min behind and is not an official API). Fetched every 2 minutes, only while
 * the home screen is visible, and cached so the screen opens with numbers. The list comes from config.properties
 * ("stocks": Yahoo symbol, optionally ":label", comma-separated).
 */
final class Stocks {
    private static final long EVERY = 2 * 60_000L;

    interface Listener { void onStocks(); }

    static final class Quote {
        String symbol, label;
        double price = Double.NaN, previous = Double.NaN;
        float[] curve = new float[0];

        double change() {
            return previous > 0 && !Double.isNaN(price) ? (price - previous) / previous * 100 : Double.NaN;
        }
    }

    private static Stocks instance;

    static synchronized Stocks get(Context c) {
        if (instance == null) instance = new Stocks(c.getApplicationContext());
        return instance;
    }

    final List<Quote> quotes = new ArrayList<>();
    volatile long at;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;

    private Stocks(Context c) {
        cache = c.getSharedPreferences("stocks", Context.MODE_PRIVATE);
        for (String item : Config.STOCKS.split(",")) {
            item = item.trim();
            if (item.isEmpty()) continue;
            Quote q = new Quote();
            int colon = item.lastIndexOf(':');
            q.symbol = colon > 0 ? item.substring(0, colon).trim() : item;
            q.label = colon > 0 ? item.substring(colon + 1).trim() : item.toUpperCase(Locale.ROOT);
            String saved = cache.getString(q.symbol, null);
            if (saved != null) {
                try {
                    parse(q, saved);
                } catch (Exception ignored) {
                }
            }
            quotes.add(q);
        }
        at = cache.getLong("at", 0);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (fetching || quotes.isEmpty() || (now - at < EVERY && now >= at)) return;
        fetching = true;
        io.execute(() -> {
            boolean any = false;
            SharedPreferences.Editor edit = cache.edit();
            for (Quote q : quotes) {
                try {
                    String body = get("https://query1.finance.yahoo.com/v8/finance/chart/"
                            + URLEncoder.encode(q.symbol, "UTF-8") + "?range=1d&interval=15m");
                    synchronized (quotes) {
                        parse(q, body);
                    }
                    edit.putString(q.symbol, body);
                    any = true;
                } catch (Exception e) {
                    AppLog.w("stocks " + q.symbol + ": " + e.getMessage());
                }
            }
            if (any) {
                at = System.currentTimeMillis();
                edit.putLong("at", at).apply();
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onStocks(); });
            }
            fetching = false;
        });
    }

    /** {"chart":{"result":[{"meta":{"regularMarketPrice":…,"chartPreviousClose":…},"indicators":{"quote":[{"close":[…]}]}}]}} */
    private static void parse(Quote q, String body) throws Exception {
        JSONObject r = new JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONObject meta = r.getJSONObject("meta");
        q.price = meta.optDouble("regularMarketPrice", Double.NaN);
        q.previous = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN));
        JSONArray close = r.optJSONObject("indicators") == null ? null
                : r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).optJSONArray("close");
        List<Float> points = new ArrayList<>();
        if (close != null) {
            for (int i = 0; i < close.length(); i++) if (!close.isNull(i)) points.add((float) close.getDouble(i));
        }
        float[] curve = new float[points.size()];
        for (int i = 0; i < curve.length; i++) curve[i] = points.get(i);
        q.curve = curve;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** Full names for the common symbols (shown under the label). */
    static String name(String label) {
        switch (label.toUpperCase(Locale.ROOT)) {
            case "SPX": return "S&P 500";
            case "NDX": return "Nasdaq 100";
            case "BTC": return "Bitcoin";
            case "ETH": return "Ethereum";
            case "XAU": return Ui.tr("Altın", "Gold");
            case "XAG": return Ui.tr("Gümüş", "Silver");
            case "TSLA": return "Tesla";
            case "SOFI": return "SoFi";
            case "NVDA": return "NVIDIA";
            case "AAPL": return "Apple";
            case "MSFT": return "Microsoft";
            default: return "";
        }
    }

    /** 7.690,73 · 81.925 · 368,06 (Turkish); 7,690.73 · 81,925 · 368.06 (English): no cents from 10,000 on. */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        Locale l = Ui.en ? Locale.UK : Ui.TR;
        return v >= 10_000 ? String.format(l, "%,.0f", v) : String.format(l, "%,.2f", v);
    }

    /** +1,23% / −0,45% (a real minus sign; in Turkish the % comes first: +%1,23). */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        String sign = v >= 0 ? "+" : "−";
        String n = String.format(Ui.en ? Locale.UK : Ui.TR, "%.2f", Math.abs(v));
        return Ui.en ? sign + n + "%" : sign + "%" + n;
    }
}
