package io.freemytv;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.Collections;
import java.util.List;

/**
 * Android only shows the "now playing" sessions (title, artist, cover, play/pause) to an app that has notification
 * access, and this listener is how the app holds that access. Control Center grants it once through the TV's own ADB
 * (`cmd notification allow_listener`). The system keeps it running from then on, so it also records where each app
 * stopped (Continue.Recorder) for the home screen's "continue watching" card. It reads no notifications.
 */
public class MediaListener extends NotificationListenerService {
    static final String YT_MUSIC = "com.google.android.youtube.tvmusic";

    private Continue.Recorder recorder;

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        if (recorder == null) recorder = new Continue.Recorder(this);
        recorder.start();
    }

    @Override public void onListenerDisconnected() {
        if (recorder != null) recorder.stop();
        super.onListenerDisconnected();
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    static boolean allowed(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationListenerAccessGranted(component(c));
    }

    /** The sessions, newest first; empty without access. */
    static List<MediaController> sessions(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return m.getActiveSessions(component(c));
        } catch (RuntimeException e) {   // SecurityException: no notification access yet
            return Collections.emptyList();
        }
    }

    /** The one to show: what is playing; otherwise the chosen music app's; otherwise the newest one. Null if there is none. */
    static MediaController pick(List<MediaController> sessions) {
        MediaController music = null;
        for (MediaController m : sessions) {
            if (playing(m)) return m;
            if (music == null && Prefs.musicApp().equals(m.getPackageName())) music = m;
        }
        return music != null ? music : sessions.isEmpty() ? null : sessions.get(0);
    }

    static boolean playing(MediaController m) {
        PlaybackState s = m == null ? null : m.getPlaybackState();
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING;
    }
}
