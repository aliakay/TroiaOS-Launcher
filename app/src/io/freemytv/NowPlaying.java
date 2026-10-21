package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * What's playing, kept up to date: the session to show (MediaListener.pick), its app, song, artist, cover, state and
 * position. Used by Control Center and the screensaver. Needs notification access (Control Center asks for it once);
 * without it there is simply no session.
 */
final class NowPlaying {
    private final Context context;
    private final Runnable onChange;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean watching;

    MediaController controller;
    Bitmap art;
    String app = "", title = "", artist = "";

    NowPlaying(Context c, Runnable onChange) {
        context = c.getApplicationContext();
        this.onChange = onChange;
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            onChange.run();
        }

        @Override public void onMetadataChanged(MediaMetadata metadata) {
            read();
            onChange.run();
        }

        @Override public void onSessionDestroyed() {
            pick();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pick();

    void start() {
        if (watching) return;
        watching = true;
        try {
            MediaSessionManager m = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            m.addOnActiveSessionsChangedListener(sessions, MediaListener.component(context), handler);
        } catch (RuntimeException e) {   // no notification access (yet)
            AppLog.w("now playing: " + e.getMessage());
        }
        pick();
    }

    void stop() {
        if (!watching) return;
        watching = false;
        try {
            MediaSessionManager m = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            m.removeOnActiveSessionsChangedListener(sessions);
        } catch (RuntimeException ignored) {
        }
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;      // so the next start registers again
        art = null;
    }

    private void pick() {
        MediaController next = MediaListener.pick(MediaListener.sessions(context));
        boolean same = next != null && controller != null && controller.getSessionToken().equals(next.getSessionToken());
        if (!same) {
            if (controller != null) controller.unregisterCallback(callback);
            controller = next;
            if (controller != null) controller.registerCallback(callback, handler);
        }
        read();
        onChange.run();
    }

    private void read() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (artist.isEmpty()) artist = text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap b = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        art = b;
        app = controller == null ? "" : appName(controller.getPackageName());
    }

    boolean playing() {
        return MediaListener.playing(controller);
    }

    boolean isYouTubeMusic() {
        return controller == null || MediaListener.YT_MUSIC.equals(controller.getPackageName());
    }

    /** How far into the song (0 to 1), counting the time since the app last reported it; -1 if unknown. */
    float progress() {
        if (controller == null) return -1;
        MediaMetadata m = controller.getMetadata();
        PlaybackState s = controller.getPlaybackState();
        long duration = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (s == null || duration <= 0 || s.getPosition() < 0) return -1;
        long position = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            position += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, Math.min(1, position / (float) duration));
    }

    private static String text(MediaMetadata m, String key, String fallback) {
        if (m == null) return "";
        CharSequence s = m.getText(key);
        if (s == null || s.length() == 0) s = m.getText(fallback);
        return s == null ? "" : s.toString().trim();
    }

    private String appName(String pkg) {
        if (MediaListener.YT_MUSIC.equals(pkg)) return "YouTube Music";
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }
}
