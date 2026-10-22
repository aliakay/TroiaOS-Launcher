package io.freemytv;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Html;
import android.util.Xml;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NASA's Astronomy Picture of the Day as the wallpaper, from APOD's official feed (science.nasa.gov/feed/apod-basic/,
 * the last 30 days; api.nasa.gov's APOD endpoint has returned only the NASA logo since APOD moved there in 2026).
 *
 * Many APOD pictures belong to their photographers, and the feed's copyright field just repeats the credit, so a day
 * counts as free only when every name in its credit is a public space agency, observatory or mission (`free`). The
 * newest free landscape picture is kept in files/apod.img, with its title and credit in apod.json. The feed is read at
 * most every 6 hours; call `update` off the main thread.
 */
final class Apod {
    private Apod() {}

    private static final String FEED = "https://science.nasa.gov/feed/apod-basic/";
    private static final long CHECK_MS = 6 * 3_600_000L;
    private static final int MAX_BYTES = 30 << 20;

    static final class Day {
        String date, title, credit;
        File file;
    }

    /** Agencies, observatories and missions whose pictures are public domain or free with credit. */
    private static final Set<String> FREE = new HashSet<>(Arrays.asList(
            "nasa", "esa", "csa", "jpl", "caltech", "gsfc", "goddard", "goddard space flight center", "msfc", "jsc", "ksc",
            "nasa ames", "ames", "stsci", "aura", "hubble", "hubble heritage", "hubble heritage team", "hla",
            "hubble legacy archive", "webb", "jwst", "ero production team", "webb ero production team", "cxc", "sao",
            "chandra", "sdo", "soho", "aia", "hmi", "msss", "mro", "hirise", "lpl", "u. arizona", "univ. arizona",
            "univ. of arizona", "university of arizona", "arizona state univ.", "arizona state university", "asu", "lro",
            "lroc", "lunar reconnaissance orbiter", "iss", "international space station", "cassini", "ssi",
            "space science institute", "swri", "juno", "junocam", "jhuapl", "apl", "new horizons", "dscovr", "epic",
            "noaa", "usgs", "landsat", "earth observatory", "nasa earth observatory", "goes", "spitzer", "wise", "neowise",
            "ucla", "galex", "kepler", "tess", "voyager", "viking", "perseverance", "curiosity", "mars 2020", "cornell",
            "ipac", "artemis", "artemis i", "artemis ii", "orion", "apollo", "parker solar probe", "solar orbiter",
            "europa clipper", "lucy", "osiris-rex", "dart", "image", "image credit", "credit", "and", "the", "et al."));
    private static final Pattern NUMBERED = Pattern.compile("(apollo|expedition|artemis) [0-9ivx]+");
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:[,;/&+()\\[\\]:|]|\\s[-–—]\\s|\\band\\b)\\s*");

    /** True if every name in the credit is a public agency or mission (and nothing says "copyright"). */
    static boolean free(String credit) {
        String s = credit.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').trim();
        if (s.isEmpty() || s.contains("copyright") || s.contains("©")) return false;
        boolean any = false;
        for (String part : SPLIT.split(s)) {
            String t = part.trim().replaceAll("\\s+", " ");
            if (t.startsWith("the ")) t = t.substring(4);
            if (t.endsWith("'s")) t = t.substring(0, t.length() - 2);
            if (t.isEmpty()) continue;
            if (!FREE.contains(t) && !NUMBERED.matcher(t).matches()) {
                // "JPL-Caltech", "OSIRIS-REx": every piece has to be on the list.
                for (String piece : t.split("-")) if (!FREE.contains(piece.trim())) return false;
            }
            any = true;
        }
        return any;
    }

    private static File meta(Context c) {
        return new File(c.getFilesDir(), "apod.json");
    }

    private static File image(Context c) {
        return new File(c.getFilesDir(), "apod.img");
    }

    private static JSONObject read(Context c) {
        File f = meta(c);
        if (!f.exists()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(in, 1 << 16), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The picture kept from the last check, or null. */
    static Day saved(Context c) {
        JSONObject o = read(c);
        File img = image(c);
        if (o.optString("date").isEmpty() || !img.exists()) return null;
        Day d = new Day();
        d.date = o.optString("date");
        d.title = o.optString("title");
        d.credit = o.optString("credit");
        d.file = img;
        return d;
    }

    static boolean stale(Context c) {
        long checked = read(c).optLong("checked");
        long now = System.currentTimeMillis();
        return checked > now || now - checked > CHECK_MS;
    }

    /** Reads the feed and keeps the newest free landscape picture. Network: never on the main thread. */
    static Day update(Context c) throws Exception {
        List<Day> days = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        HttpURLConnection http = open(FEED);
        try (InputStream in = http.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            Day d = null;
            String hd = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    n = n.substring(n.indexOf(':') + 1);
                    if (n.equals("item")) {
                        d = new Day();
                        hd = null;
                    } else if (d != null) {
                        switch (n) {
                            case "title": d.title = plain(p.nextText()); break;
                            case "pubDate": d.date = p.nextText().trim(); break;
                            case "credit": d.credit = plain(p.nextText()); break;
                            case "hdurl": hd = p.nextText().trim(); break;
                            default: break;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && d != null && p.getName().endsWith("item")) {
                    if (d.title != null && d.date != null && d.credit != null && hd != null && !hd.isEmpty() && landscape(hd, 0, 0)) {
                        days.add(d);
                        urls.add(hd);
                    }
                    d = null;
                }
            }
        } finally {
            http.disconnect();
        }
        JSONObject o = read(c);
        Day kept = saved(c);
        int tries = 0;
        for (int i = 0; i < days.size() && tries < 3; i++) {        // newest first
            Day d = days.get(i);
            if (!free(d.credit)) continue;
            if (kept != null && d.date.equals(kept.date)) break;     // the newest free one is the one we have
            tries++;
            File tmp = new File(c.getFilesDir(), "apod.tmp");
            try {
                download(smaller(urls.get(i)), tmp);
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getPath(), b);
                if (!landscape(null, b.outWidth, b.outHeight)) continue;
                if (!tmp.renameTo(image(c))) throw new IOException("can't keep the picture");
                o.put("date", d.date).put("title", d.title).put("credit", d.credit);
                kept = d;
                kept.file = image(c);
                AppLog.w("apod: " + d.title + " (" + d.credit + ")");
                break;
            } catch (Exception e) {
                AppLog.w("apod picture: " + e.getMessage());
            } finally {
                if (tmp.exists() && !tmp.delete()) AppLog.w("apod: tmp left");
            }
        }
        o.put("checked", System.currentTimeMillis());
        write(c, o);
        return kept;
    }

    /** Wide enough for a TV (from the URL's w and h, or the real size); unknown sizes are tried. */
    private static boolean landscape(String url, int w, int h) {
        if (url != null) {
            try {
                Uri u = Uri.parse(url);
                w = Integer.parseInt(u.getQueryParameter("w"));
                h = Integer.parseInt(u.getQueryParameter("h"));
            } catch (Exception e) {
                return true;
            }
            if (w <= 0 || h <= 0) return true;
        }
        return w >= 1200 && w >= h * 1.3f;
    }

    /** NASA's image server scales on request: no need to fetch a 9000-pixel original. */
    private static String smaller(String url) {
        Uri u = Uri.parse(url);
        if (u.getHost() == null || !u.getHost().equals("assets.science.nasa.gov")) return url;
        return u.buildUpon().clearQuery().appendQueryParameter("w", "2400").appendQueryParameter("h", "2400")
                .appendQueryParameter("fit", "clip").build().toString();
    }

    private static String plain(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "TroiaOS/1.0 (Android TV home screen)");
        if (c.getResponseCode() != 200) {
            c.disconnect();
            throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
        }
        return c;
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16 << 10];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("picture too large");
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void write(Context c, JSONObject o) {
        File tmp = new File(c.getFilesDir(), "apod.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("apod: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(meta(c))) AppLog.w("apod: could not save apod.json");
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
