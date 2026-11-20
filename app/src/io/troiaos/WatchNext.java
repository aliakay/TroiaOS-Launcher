package io.troiaos;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TV's Watch Next list: what each app says can be continued (Netflix: the show, the episode, where it stopped,
 * its poster and a link straight to it), which Netflix doesn't give its media session. Reading other apps' rows
 * needs READ_TV_LISTINGS, which a TV never asks for on screen: it's granted over ADB (TroiaOS does it once itself,
 * through the TV's own ADB; revoked, it stays revoked). Without it, nothing is read and the tiles stay as they were.
 * The posters come from the address the app gives (Netflix: its image server), over HTTPS only, kept small in files/.
 */
final class WatchNext {
    private WatchNext() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final String[] COLUMNS = {"package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "watch_next_type", "last_playback_position_millis", "duration_millis",
            "poster_art_uri", "thumbnail_uri", "intent_uri", "last_engagement_time_utc_millis"};
    private static final int TYPE_CONTINUE = 0, TYPE_NEXT = 1;
    private static final int POSTER_MAX_BYTES = 3 << 20, POSTER_WIDTH = 480;

    static boolean allowed(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Once ever: TroiaOS grants itself the permission through the TV's own ADB (as for notification access). */
    static void askOnce(Context c) {
        if (allowed(c)) return;
        SharedPreferences p = c.getSharedPreferences("watch-next", Context.MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            adb.shell("pm grant " + c.getPackageName() + " " + PERMISSION);
            AppLog.w("watch next: access " + (allowed(c) ? "granted" : "not granted"));
        } catch (Exception e) {
            AppLog.w("watch next: " + e.getMessage());
        }
    }

    /** Each app's newest row that can be continued (not finished, not dismissed), newest first. Off the UI thread. */
    static List<Continue.Entry> read(Context c) {
        List<Continue.Entry> out = new ArrayList<>();
        if (!allowed(c)) return out;
        Map<String, Continue.Entry> newest = new HashMap<>();
        JSONObject gone = Continue.gone(c);
        long now = System.currentTimeMillis();
        // Without ACCESS_ALL_EPG_DATA a selection is refused: the whole table is read and filtered here.
        try (Cursor q = c.getContentResolver().query(URI, COLUMNS, null, null, null)) {
            while (q != null && q.moveToNext()) {
                int type = q.isNull(5) ? -1 : q.getInt(5);
                String pkg = q.getString(0), title = q.getString(1);
                if (type != TYPE_CONTINUE && type != TYPE_NEXT) continue;
                if (pkg == null || pkg.equals(c.getPackageName()) || title == null || title.trim().isEmpty()) continue;
                Continue.Entry e = new Continue.Entry();
                e.pkg = pkg;
                e.title = title.trim();
                e.subtitle = episode(q.getString(3), q.getString(4), q.getString(2));
                e.position = Math.max(0, q.getLong(6));
                e.duration = Math.max(0, q.getLong(7));
                e.at = q.getLong(11);
                e.listed = true;
                e.music = Continue.isMusic(pkg);
                String poster = q.getString(8);
                if (poster == null || !poster.startsWith("https://")) poster = q.getString(9);
                e.poster = poster != null && poster.startsWith("https://") ? poster : "";
                String link = q.getString(10);
                e.link = link == null ? "" : link;
                if (now - e.at > Continue.FRESH_MS || Continue.dismissed(gone, e)) continue;
                if (e.duration > 0 && e.position >= e.duration * 0.95) continue;
                Continue.Entry had = newest.get(pkg);
                if (had == null || had.at < e.at) newest.put(pkg, e);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: " + e.getMessage());
        }
        out.addAll(newest.values());
        java.util.Collections.sort(out, (x, y) -> Long.compare(y.at, x.at));
        return out;
    }

    /**
     * Live TV (Fibe TV): its player only gives the program's name, but its row on the TV's home screen (preview
     * programs) lists what's on its channels now, each with a poster and a link that tunes to the channel. A tile
     * without a picture of its own takes them from the row with the same title; the channel is remembered, so later
     * the tile shows what's on that channel now (and OK tunes to it). Off the UI thread.
     */
    static void addLive(Context c, List<Continue.Entry> list) {
        if (!allowed(c)) return;
        List<String[]> rows = new ArrayList<>();      // package, title, episode, poster, link
        String[] cols = {"package_name", "title", "episode_title", "poster_art_uri", "intent_uri"};
        try (Cursor q = c.getContentResolver().query(PREVIEW, cols, null, null, null)) {
            while (q != null && q.moveToNext()) {
                String[] r = {q.getString(0), q.getString(1), q.getString(2), q.getString(3), q.getString(4)};
                if (r[0] != null && r[1] != null && r[4] != null) rows.add(r);
            }
        } catch (RuntimeException e) {
            AppLog.w("watch next: live: " + e.getMessage());
            return;
        }
        SharedPreferences p = c.getSharedPreferences("watch-next", Context.MODE_PRIVATE);
        for (Continue.Entry e : list) {
            if (e.listed || e.untitled || Continue.artFile(c, e.pkg).exists()) continue;
            String[] row = null;
            for (String[] r : rows) if (r[0].equals(e.pkg) && r[1].trim().equalsIgnoreCase(e.title)) row = r;
            if (row != null) {
                p.edit().putString("channel_" + e.pkg, row[4]).putString("channel_title_" + e.pkg, e.title).apply();
            } else if (e.title.equals(p.getString("channel_title_" + e.pkg, null))) {
                String link = p.getString("channel_" + e.pkg, "");
                for (String[] r : rows) if (r[0].equals(e.pkg) && r[4].equals(link)) row = r;    // that channel, now
            }
            if (row == null) {
                // Fibe's list isn't there yet (right after a restart, until Fibe opens): the channel and poster it
                // had for this program last time, if they were saved.
                String link = p.getString("channel_" + e.pkg, "");
                String poster = p.getString("poster_" + e.pkg, "");
                if (link.isEmpty() || poster.isEmpty() || !e.title.equals(p.getString("channel_title_" + e.pkg, null))
                        || !posterFile(c, e.pkg).exists()) continue;
                e.listed = true;
                e.subtitle = channelName(link);
                e.poster = poster;
                e.link = link;
                continue;
            }
            e.listed = true;
            e.title = row[1].trim();
            e.subtitle = channelName(row[4]);
            if (row[2] != null && !row[2].trim().isEmpty()) e.subtitle = e.subtitle.isEmpty() ? row[2].trim() : e.subtitle + " · " + row[2].trim();
            e.poster = row[3] != null && row[3].startsWith("https://") ? row[3] : "";
            e.link = row[4];
        }
    }

    /** Fibe's channel code from its link ("…/channel/CP24H?…" → "CP24"); a few by their names. */
    static String channelName(String link) {
        int i = link.indexOf("/channel/");
        if (i < 0) return "";
        String code = link.substring(i + 9);
        int q = code.indexOf('?');
        if (q >= 0) code = code.substring(0, q);
        if (code.endsWith("HD")) code = code.substring(0, code.length() - 2);
        else if (code.endsWith("HT")) code = code.substring(0, code.length() - 2);
        else if (code.length() > 3 && code.endsWith("H")) code = code.substring(0, code.length() - 1);
        if (code.endsWith("-")) code = code.substring(0, code.length() - 1);
        switch (code) {
            case "CTY": return "Citytv";
            case "GLB": return "Global";
            case "GLF": return "Golf Channel";
            case "CBCN": return "CBC News";
            case "CTVN": return "CTV News";
            case "HOME": return "Home Network";
            case "W": return "W Network";
            default: return code;
        }
    }

    /** "S7 E14", "S7 E14 · The Name", or just the name. */
    private static String episode(String season, String number, String name) {
        String s = season != null && number != null && !season.isEmpty() && !number.isEmpty() ? "S" + season + " E" + number : "";
        if (name != null && !name.trim().isEmpty()) s = s.isEmpty() ? name.trim() : s + " · " + name.trim();
        return s;
    }

    /**
     * One tile per app: an app's Watch Next row and its media-session entry are the same thing seen twice. The row
     * wins when the session has no title (Netflix), or when it is newer; the newer time and position are kept.
     */
    static List<Continue.Entry> merge(List<Continue.Entry> sessions, List<Continue.Entry> listed) {
        Map<String, Continue.Entry> byPkg = new HashMap<>();
        for (Continue.Entry s : sessions) byPkg.put(s.pkg, s);
        for (Continue.Entry l : listed) {
            Continue.Entry s = byPkg.get(l.pkg);
            if (s == null) {
                byPkg.put(l.pkg, l);
            } else if (s.untitled || l.at >= s.at) {
                if (s.at > l.at) {          // the session saw it play later: its position is the fresher one
                    l.at = s.at;
                    l.position = s.position;
                }
                byPkg.put(l.pkg, l);
            }
        }
        List<Continue.Entry> out = new ArrayList<>(byPkg.values());
        java.util.Collections.sort(out, (x, y) -> Long.compare(y.at, x.at));
        return out;
    }

    static File posterFile(Context c, String pkg) {
        return new File(c.getFilesDir(), "continue-poster-" + pkg + ".jpg");
    }

    /** The poster already saved for this row (same address), or null. */
    static File saved(Context c, Continue.Entry e) {
        if (e.poster.isEmpty()) return null;
        File f = posterFile(c, e.pkg);
        String url = c.getSharedPreferences("watch-next", Context.MODE_PRIVATE).getString("poster_" + e.pkg, "");
        return f.exists() && url.equals(e.poster) ? f : null;
    }

    /**
     * Downloads the row's poster (HTTPS only, at most 3 MB), saves it 480 px wide and replaces the app's previous
     * one. Network: call it off the UI thread and off the home screen's loader. True if saved.
     */
    static boolean fetch(Context c, Continue.Entry e) {
        if (!e.poster.startsWith("https://")) return false;
        File tmp = new File(c.getFilesDir(), "continue-poster.download");
        try {
            // Redirects are followed by hand (Fibe's artwork server sends one), HTTPS only, at most 3.
            String url = e.poster;
            HttpURLConnection http = null;
            for (int hop = 0; ; hop++) {
                http = (HttpURLConnection) new URL(url).openConnection();
                http.setConnectTimeout(10_000);
                http.setReadTimeout(20_000);
                http.setInstanceFollowRedirects(false);
                int code = http.getResponseCode();
                if (code / 100 != 3) break;
                String next = http.getHeaderField("Location");
                http.disconnect();
                if (next == null || hop >= 2) throw new IOException("HTTP " + code);
                url = new URL(new URL(url), next).toString();
                if (!url.startsWith("https://")) throw new IOException("redirect off HTTPS");
            }
            try {
                if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
                try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 << 10];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > POSTER_MAX_BYTES) throw new IOException("poster too large");
                        out.write(buf, 0, n);
                    }
                }
            } finally {
                http.disconnect();
            }
            Bitmap b = Continue.decode(tmp, POSTER_WIDTH);
            if (b == null) throw new IOException("not a picture");
            File f = posterFile(c, e.pkg), part = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(part)) {
                b.compress(Bitmap.CompressFormat.JPEG, 86, out);
            } finally {
                b.recycle();
            }
            if (!part.renameTo(f)) throw new IOException("could not save");
            c.getSharedPreferences("watch-next", Context.MODE_PRIVATE).edit().putString("poster_" + e.pkg, e.poster).apply();
            AppLog.w("watch next: poster for " + e.pkg + " (" + new URL(url).getHost() + ")");
            return true;
        } catch (IOException | RuntimeException ex) {
            AppLog.w("watch next: poster for " + e.pkg + ": " + ex.getMessage());
            return false;
        } finally {
            tmp.delete();
        }
    }
}
