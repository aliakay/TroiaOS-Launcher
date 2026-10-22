package io.troiaos;

import android.util.Log;

/** The app's log (adb logcat -s TroiaOS). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("TroiaOS", message);
    }
}
