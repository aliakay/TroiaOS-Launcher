package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * "Continue watching": the last thing each app played and where it stopped, so the home screen can show it when the
 * app is focused in the dock (the video's picture, title, channel, time left; and the latest from another app).
 *
 * Recorded from the apps' media sessions by the Recorder, which runs in MediaListener (the notification listener the
 * system keeps bound once Control Center has granted access). One entry per app, the latest; it goes away when the
 * thing is finished. Kept in files/continue.json, with the pictures next to it, so the home screen (another process)
 * reads them on resume.
 */
final class Continue {
    private Continue() {}

    /** Older than this, an entry isn't shown. */
    static final long FRESH_MS = 14L * 24 * 3600_000;
    private static final Object LOCK = new Object();
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    static final class Entry {
        String pkg, title, subtitle = "", mediaId = "", mediaUri = "";
        long position, duration, at;
        boolean music;
        Bitmap art;     // decoded by the reader (null if none)

        /** How far into it (0 to 1), or -1 without a duration. */
        float progress() {
            return duration > 0 ? Math.max(0, Math.min(1, position / (float) duration)) : -1;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("pkg", pkg).put("title", title).put("subtitle", subtitle).put("mediaId", mediaId)
                    .put("mediaUri", mediaUri).put("position", position).put("duration", duration).put("at", at).put("music", music);
        }

        static Entry from(JSONObject o) {
            Entry e = new Entry();
            e.pkg = o.optString("pkg");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.mediaId = o.optString("mediaId");
            e.mediaUri = o.optString("mediaUri");
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.at = o.optLong("at");
            e.music = isMusic(e.pkg);   // decided by the app each time (older saves guessed wrong for YouTube)
            return e;
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "continue.json");
    }

    static File artFile(Context c, String pkg) {
        return new File(c.getFilesDir(), "continue-" + pkg + ".jpg");
    }

    /** Every entry, newest first, with their pictures (decode: call it off the UI thread). */
    static List<Entry> read(Context c, boolean pictures) {
        List<Entry> out = new ArrayList<>();
        JSONArray a = readArray(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Entry e = Entry.from(o);
            if (e.title.isEmpty() || now - e.at > FRESH_MS) continue;
            if (pictures) {
                File f = artFile(c, e.pkg);
                if (f.exists()) {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    e.art = BitmapFactory.decodeFile(f.getPath(), opt);
                }
            }
            out.add(e);
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(y.at, x.at));
        return out;
    }

    private static JSONArray readArray(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return new JSONArray();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return new JSONArray();
            }
        }
    }

    private static void write(Context c, Map<String, Entry> entries) {
        synchronized (LOCK) {
            JSONArray a = new JSONArray();
            try {
                for (Entry e : entries.values()) a.put(e.json());
            } catch (JSONException ignored) {
            }
            File tmp = new File(c.getFilesDir(), "continue.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                AppLog.w("continue: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(file(c))) AppLog.w("continue: could not replace the file");
        }
    }

    /**
     * Picks it up where it stopped: a YouTube video opens at that second (when the app gave its video ID); otherwise
     * the app opens (most keep their own "continue" at the top).
     */
    static boolean open(Activity a, Entry e) {
        Intent intent = null;
        if (YOUTUBE_TV.equals(e.pkg) && YOUTUBE_ID.matcher(e.mediaId).matches()) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + e.mediaId
                    + "&t=" + Math.max(0, e.position / 1000) + "s")).setPackage(e.pkg);
        } else if (e.mediaUri.startsWith("http") || e.mediaUri.contains("://")) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(e.mediaUri)).setPackage(e.pkg);
        }
        try {
            if (intent != null) {
                a.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
        } catch (RuntimeException ex) {
            AppLog.w("continue: " + ex.getMessage());
        }
        Intent launch = HomeApps.launchIntent(a.getPackageManager(), e.pkg);
        if (launch == null) return false;
        a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    /** "36 dk önce" / "36 min ago". */
    static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("şimdi", "just now");
        if (minutes < 60) return Ui.tr(minutes + " dk önce", minutes + " min ago");
        if (minutes < 48 * 60) return Ui.tr((minutes / 60) + " sa önce", (minutes / 60) + " h ago");
        return Ui.tr((minutes / 1440) + " gün önce", (minutes / 1440) + " days ago");
    }

    /** "97 dk kaldı", "1 sa 37 dk kaldı" / "97 min left"; empty without a duration. */
    static String left(Entry e) {
        if (e.duration <= 0) return "";
        long minutes = Math.max(1, (e.duration - e.position + 59_999) / 60_000);
        if (minutes < 100) return Ui.tr(minutes + " dk kaldı", minutes + " min left");
        return Ui.tr((minutes / 60) + " sa " + (minutes % 60) + " dk kaldı", (minutes / 60) + " h " + (minutes % 60) + " min left");
    }

    // MARK: - Recording (in MediaListener, main process)

    static final class Recorder {
        private final Context context;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Map<MediaSession.Token, MediaController> watched = new HashMap<>();
        private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
        private final Map<String, Entry> entries = new HashMap<>();
        private final Map<String, Long> savedAt = new HashMap<>();
        private boolean running;

        Recorder(Context c) {
            context = c.getApplicationContext();
        }

        private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::update;

        /** While something plays, its position is saved every 20 s (the app may be closed without a pause). */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                for (MediaController m : watched.values()) if (MediaListener.playing(m)) record(m, false);
                handler.postDelayed(this, 20_000);
            }
        };

        void start() {
            if (running) return;
            running = true;
            entries.clear();
            for (Entry e : read(context, false)) entries.put(e.pkg, e);
            try {
                MediaSessionManager m = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
                m.addOnActiveSessionsChangedListener(sessions, MediaListener.component(context), handler);
                update(m.getActiveSessions(MediaListener.component(context)));
            } catch (RuntimeException e) {
                AppLog.w("continue recorder: " + e.getMessage());
            }
            handler.postDelayed(tick, 20_000);
        }

        void stop() {
            if (!running) return;
            running = false;
            handler.removeCallbacks(tick);
            try {
                MediaSessionManager m = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
                m.removeOnActiveSessionsChangedListener(sessions);
            } catch (RuntimeException ignored) {
            }
            for (Map.Entry<MediaSession.Token, MediaController> w : watched.entrySet()) {
                w.getValue().unregisterCallback(callbacks.get(w.getKey()));
            }
            watched.clear();
            callbacks.clear();
        }

        private void update(List<MediaController> list) {
            if (list == null) return;
            List<MediaSession.Token> live = new ArrayList<>();
            for (final MediaController m : list) {
                MediaSession.Token t = m.getSessionToken();
                live.add(t);
                if (watched.containsKey(t) || m.getPackageName().equals(context.getPackageName())) continue;
                MediaController.Callback cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState state) {
                        record(m, true);
                    }

                    @Override public void onMetadataChanged(MediaMetadata metadata) {
                        record(m, true);
                    }
                };
                m.registerCallback(cb, handler);
                watched.put(t, m);
                callbacks.put(t, cb);
                record(m, true);
            }
            for (MediaSession.Token t : new ArrayList<>(watched.keySet())) {
                if (live.contains(t)) continue;
                watched.get(t).unregisterCallback(callbacks.remove(t));
                watched.remove(t);
            }
        }

        /**
         * Updates the app's entry from its session. Stopped or idle sessions are skipped (they often report position 0
         * and would erase where it really stopped); a finished one removes the entry.
         */
        private void record(MediaController m, boolean now) {
            MediaMetadata md = m.getMetadata();
            PlaybackState s = m.getPlaybackState();
            if (md == null || s == null) return;
            int st = s.getState();
            if (st != PlaybackState.STATE_PLAYING && st != PlaybackState.STATE_PAUSED && st != PlaybackState.STATE_BUFFERING) return;
            String pkg = m.getPackageName();
            String title = text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (title.isEmpty()) return;
            long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = Math.max(0, s.getPosition());
            if (st == PlaybackState.STATE_PLAYING) {
                position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            if (duration > 0 && position >= duration * 0.97) {       // finished: nothing to continue
                if (entries.remove(pkg) != null) save(true);
                return;
            }
            Entry e = entries.get(pkg);
            boolean fresh = e == null || !e.title.equals(title);
            if (e == null) {
                e = new Entry();
                e.pkg = pkg;
                entries.put(pkg, e);
            }
            e.title = title;
            String sub = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (sub.isEmpty()) sub = text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
            e.subtitle = sub;
            String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            e.mediaId = id == null ? "" : id;
            String uri = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            e.mediaUri = uri == null ? "" : uri;
            e.duration = Math.max(0, duration);
            e.position = position;
            e.at = System.currentTimeMillis();
            e.music = isMusic(pkg, md);
            if (fresh) AppLog.w("continue: " + pkg + " id=" + (e.mediaId.isEmpty() ? "-" : e.mediaId) + " uri=" + (e.mediaUri.isEmpty() ? "-" : "yes")
                    + " duration=" + duration / 1000 + "s");
            if (fresh || !artFile(context, pkg).exists()) saveArt(pkg, picture(md));
            save(now || st != PlaybackState.STATE_PLAYING || !savedAt.containsKey(pkg));
            savedAt.put(pkg, SystemClock.elapsedRealtime());
        }

        private void save(boolean now) {
            final Map<String, Entry> copy = new HashMap<>();
            for (Map.Entry<String, Entry> x : entries.entrySet()) {
                Entry e = x.getValue(), c = new Entry();
                c.pkg = e.pkg;
                c.title = e.title;
                c.subtitle = e.subtitle;
                c.mediaId = e.mediaId;
                c.mediaUri = e.mediaUri;
                c.position = e.position;
                c.duration = e.duration;
                c.at = e.at;
                c.music = e.music;
                copy.put(x.getKey(), c);
            }
            io.execute(() -> write(context, copy));
        }

        private void saveArt(final String pkg, final Bitmap b) {
            if (b == null) {
                artFile(context, pkg).delete();
                return;
            }
            // Small enough for the card (480 px wide), saved off the main thread.
            float scale = Math.min(1, 480f / Math.max(1, b.getWidth()));
            final Bitmap small = scale < 1 ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale),
                    Math.round(b.getHeight() * scale), true) : b;
            io.execute(() -> {
                File f = artFile(context, pkg), tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    small.compress(Bitmap.CompressFormat.JPEG, 86, out);
                } catch (IOException e) {
                    return;
                }
                tmp.renameTo(f);
            });
        }

        private static Bitmap picture(MediaMetadata md) {
            Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            return b;
        }

        private static boolean isMusic(String pkg, MediaMetadata md) {
            return Continue.isMusic(pkg);
        }

        private static String text(MediaMetadata m, String key, String fallback) {
            CharSequence s = m.getText(key);
            if (s == null || s.length() == 0) s = m.getText(fallback);
            return s == null ? "" : s.toString().trim();
        }
    }

    /**
     * Music or podcasts ("Last played") rather than video ("Last watched"): by the app only, since YouTube fills in an
     * "album" for videos too.
     */
    static boolean isMusic(String pkg) {
        return pkg.contains("music") || pkg.contains("spotify") || pkg.contains("deezer") || pkg.contains("tidal")
                || pkg.contains("soundcloud") || pkg.contains("podcast");
    }
}
