package io.troiaos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TroiaOS's photos behind the home screen (Customize → Wallpaper): nature or city photos from Wikimedia Commons (daytime
 * ones by day, night ones at night), NASA's public-domain space photos, or NASA's Astronomy Picture of the Day (Apod), each
 * credited on screen; a new one every hour, or one chosen photo. "colors" shows Wallpaper's soft clouds instead. The
 * bundled photos are in assets/wallpapers (photos.json: place, author, license). Loaded in the background into
 * Wallpaper (with its blur, for the glass), darkened as chosen.
 */
final class Photos {
    interface Listener { void onPhoto(); }

    static final class Photo {
        String file, set, tr, en, author, license;
        /** NASA's picture of the day: the downloaded file (null: the photo is in the app's assets). */
        File path;

        String place() { return path != null ? tr : Ui.tr(tr, en); }

        String credit() {
            return path != null ? "NASA APOD · " + author : Ui.tr("Foto: ", "Photo: ") + author + " · " + license;
        }
    }

    private static final long APOD_RECHECK_MS = 30 * 60_000L;
    private static Photos instance;

    static synchronized Photos get(Context c) {
        if (instance == null) instance = new Photos(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final List<Photo> day = new ArrayList<>(), night = new ArrayList<>(), space = new ArrayList<>(),
            cityDay = new ArrayList<>(), cityNight = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(), net = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    /** What's on screen and what's being decoded: the photo's file and its darkening ("file|dim"). */
    private String loaded, loading;
    private Apod.Day apod;
    private boolean apodBusy;
    private long apodNextCheck;
    /** The photo on screen now (null with the soft colors, or until the first one is loaded). */
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
                p.set = o.optString("set", "nature");
                p.tr = o.optString("tr");
                p.en = o.optString("en");
                p.author = o.optString("author");
                p.license = o.optString("license");
                boolean dark = "night".equals(o.optString("time"));
                (p.set.equals("space") ? space : p.set.equals("cities") ? dark ? cityNight : cityDay : dark ? night : day).add(p);
            }
        } catch (Exception e) {
            AppLog.w("photos: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The photos one can pick for a wallpaper choice ("nature", "cities" or "space"). */
    List<Photo> choices(String set) {
        List<Photo> out = new ArrayList<>();
        if (set.equals("space")) {
            out.addAll(space);
        } else if (set.equals("cities")) {
            out.addAll(cityDay);
            out.addAll(cityNight);
        } else if (set.equals("nature")) {
            out.addAll(day);
            out.addAll(night);
        }
        return out;
    }

    /** The picture of the day kept on the TV (null: none yet, or none free in the last 30 days). */
    Apod.Day apod() {
        return apod;
    }

    /** Shows what Customize → Wallpaper says, if it isn't on screen already. Call from the main thread. */
    void refresh() {
        Prefs.load(context);
        showMode(Prefs.wallpaper(), Prefs.wallpaperPhoto(), false);
    }

    /**
     * Set Up's preview: what this wallpaper choice would show now, whatever is saved (refresh goes back). The picture
     * of the day only if one is already kept: browsing past it asks NASA nothing.
     */
    void previewMode(String mode) {
        showMode(mode, "", true);
    }

    private void showMode(String mode, String fixed, boolean preview) {
        if (mode.equals("colors")) {
            clear();
            return;
        }
        Photo p = null;
        if (mode.equals("apod")) {
            if (!preview) checkApod();
            p = apodPhoto();
        }
        if (p == null) {
            String set = mode.equals("apod") ? "space" : mode;
            if (!fixed.isEmpty() && !mode.equals("apod")) {
                for (Photo q : choices(set)) if (q.file.equals(fixed)) p = q;
            }
            if (p == null) {
                long now = System.currentTimeMillis();
                boolean dark = HomeData.get(context).isNight(now);
                List<Photo> list = set.equals("space") ? space
                        : set.equals("cities") ? dark && !cityNight.isEmpty() ? cityNight : cityDay
                        : dark && !night.isEmpty() ? night : day;
                if (list.isEmpty()) return;
                p = list.get((int) ((now / 3_600_000L) % list.size()));
            }
        }
        show(p, Prefs.wallpaperDim());
    }

    /** Customize's preview: this photo now, whatever is saved (refresh goes back to the saved choice). */
    void preview(Photo p) {
        if (p == null) clear();
        else show(p, Prefs.wallpaperDim());
    }

    private void show(final Photo p, final int dim) {
        final String key = p.file + "|" + dim;
        if (key.equals(loaded) || key.equals(loading)) return;
        loading = key;
        io.execute(() -> {
            try {
                Bitmap image = decode(p, dim);
                Bitmap frost = Wallpaper.frostOf(image);
                Bitmap sharp = PhotoGlass.wanted() ? Wallpaper.sharpOf(image) : null;      // Android 13+ only
                main.post(() -> {
                    if (!key.equals(loading)) return;      // something else was asked for meanwhile
                    Wallpaper.get().setPhoto(image, frost, sharp);
                    current = p;
                    loaded = key;
                    loading = null;
                    notifyListeners();
                });
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("photo " + p.file + ": " + e.getMessage());
                main.post(() -> {
                    if (key.equals(loading)) loading = null;
                });
            }
        });
    }

    /** Customize → Glass changed: build (or drop) the Android 13+ glass's copy of the photo on screen. */
    void glassChanged() {
        io.execute(() -> {
            final Bitmap image = Wallpaper.get().photo;
            final Bitmap sharp = image != null && PhotoGlass.wanted() ? Wallpaper.sharpOf(image) : null;
            main.post(() -> {
                if (Wallpaper.get().photo == image) Wallpaper.get().photoSharp = sharp;
                notifyListeners();
            });
        });
    }

    /** The soft colors: no photo. */
    private void clear() {
        loading = null;
        if (loaded == null && !Wallpaper.get().hasPhoto()) return;
        Wallpaper.get().setPhoto(null, null);
        current = null;
        loaded = null;
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onPhoto();
    }

    private Photo apodPhoto() {
        if (apod == null || apod.file == null || !apod.file.exists()) return null;
        Photo p = new Photo();
        p.file = "apod:" + apod.date;
        p.set = "apod";
        p.tr = p.en = apod.title;
        p.author = apod.credit;
        p.license = "";
        p.path = apod.file;
        return p;
    }

    /** Reads what's kept, and the feed when it's due (Apod: at most every 6 hours, shared by both processes). */
    private void checkApod() {
        long now = System.currentTimeMillis();
        if (apodBusy || now < apodNextCheck) return;
        apodBusy = true;
        apodNextCheck = now + APOD_RECHECK_MS;
        net.execute(() -> {
            Apod.Day d;
            try {
                d = Apod.stale(context) ? Apod.update(context) : Apod.saved(context);
            } catch (Exception e) {
                AppLog.w("apod: " + e.getMessage());
                d = Apod.saved(context);
            }
            final Apod.Day found = d;
            main.post(() -> {
                apodBusy = false;
                apod = found;
                notifyListeners();      // Customize shows the day's title
                refresh();
            });
        });
    }

    /** The photo cropped to 16:9 around its center, at 1920 × 1080, darkened by `dim` percent. */
    private Bitmap decode(Photo p, int dim) throws Exception {
        Bitmap src;
        if (p.path != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(p.path.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920 && o.outHeight / (sample * 2) >= 1080) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            src = BitmapFactory.decodeFile(p.path.getPath(), o);
        } else {
            try (InputStream in = context.getAssets().open("wallpapers/" + p.file)) {
                src = BitmapFactory.decodeStream(in);
            }
        }
        if (src == null) throw new Exception("can't decode");
        int w = src.getWidth(), h = src.getHeight();
        int cw = w, ch = Math.round(w * 9 / 16f);
        if (ch > h) {
            ch = h;
            cw = Math.round(h * 16 / 9f);
        }
        Bitmap out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, new Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch),
                new Rect(0, 0, 1920, 1080), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        if (dim > 0) c.drawColor(Math.round(255 * dim / 100f) << 24);
        src.recycle();
        return out;
    }
}
