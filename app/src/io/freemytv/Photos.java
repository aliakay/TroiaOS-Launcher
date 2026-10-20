package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * At Yazılım's photos behind the home screen: Berlin and Türkiye (assets/wallpapers, listed in photos.json with the
 * place, author and license; all from Wikimedia Commons under free licenses, credited on screen). Daytime photos by
 * day, night photos at night, a different one every hour. Loaded in the background into Wallpaper (with its blur, for
 * the glass).
 */
final class Photos {
    interface Listener { void onPhoto(); }

    static final class Photo {
        String file, tr, en, author, license;

        String place() { return Ui.tr(tr, en); }

        String credit() { return Ui.tr("Foto: ", "Photo: ") + author + " · " + license; }
    }

    private static Photos instance;

    static synchronized Photos get(Context c) {
        if (instance == null) instance = new Photos(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final List<Photo> day = new ArrayList<>(), night = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private String loaded, loading;
    /** The photo on screen now (null until the first one is loaded). */
    volatile Photo current;

    private Photos(Context c) {
        context = c;
        try (InputStream in = c.getAssets().open("wallpapers/photos.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray list = new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Photo p = new Photo();
                p.file = o.getString("file");
                p.tr = o.optString("tr");
                p.en = o.optString("en");
                p.author = o.optString("author");
                p.license = o.optString("license");
                ("night".equals(o.optString("time")) ? night : day).add(p);
            }
        } catch (Exception e) {
            AppLog.w("photos: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Loads this hour's photo (day or night) if it isn't the one on screen already. Call from the main thread. */
    void refresh() {
        long now = System.currentTimeMillis();
        List<Photo> list = HomeData.get(context).isNight(now) && !night.isEmpty() ? night : day;
        if (list.isEmpty()) return;
        final Photo p = list.get((int) ((now / 3_600_000L) % list.size()));
        if (p.file.equals(loaded) || p.file.equals(loading)) return;
        loading = p.file;
        io.execute(() -> {
            try {
                Bitmap image = decode(p.file);
                Bitmap frost = Wallpaper.frostOf(image);
                main.post(() -> {
                    Wallpaper.get().setPhoto(image, frost);
                    current = p;
                    loaded = p.file;
                    loading = null;
                    for (Listener l : new ArrayList<>(listeners)) l.onPhoto();
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("photo " + p.file + ": " + e.getMessage());
                main.post(() -> loading = null);
            }
        });
    }

    /** The photo cropped to 16:9 around its center, at 1920 × 1080. */
    private Bitmap decode(String file) throws Exception {
        Bitmap src;
        try (InputStream in = context.getAssets().open("wallpapers/" + file)) {
            src = BitmapFactory.decodeStream(in);
        }
        if (src == null) throw new Exception("can't decode");
        int w = src.getWidth(), h = src.getHeight();
        int cw = w, ch = Math.round(w * 9 / 16f);
        if (ch > h) {
            ch = h;
            cw = Math.round(h * 16 / 9f);
        }
        Bitmap crop = Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch);
        Bitmap out = cw == 1920 && ch == 1080 ? crop : Bitmap.createScaledBitmap(crop, 1920, 1080, true);
        if (crop != src && crop != out) crop.recycle();
        if (src != out) src.recycle();
        return out;
    }
}
