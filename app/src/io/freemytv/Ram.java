package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * "Free RAM" in Control Center: closes the apps resting in the background, like a task killer, and says how much memory
 * came back. `am kill-all` through the TV's own ADB (on the BRAVIA it freed ~390 MB, where the app's own
 * ActivityManager.killBackgroundProcesses freed nothing); that only closes cached apps, so what's on screen, what's
 * playing and the system's services are never touched, and every app simply starts again when opened. Without ADB it
 * falls back to killBackgroundProcesses.
 */
final class Ram {
    private Ram() {}

    /** Memory the system can hand out now, in bytes. */
    static long available(Context c) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        return info.availMem;
    }

    /** Closes the background apps; returns how many apps were asked (-1: all, through ADB). Never on the main thread. */
    static int free(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        int n = 0;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            adb.shell("am kill-all");
            return -1;
        } catch (Exception e) {
            AppLog.w("free ram: " + e.getMessage());
        }
        for (ApplicationInfo app : c.getPackageManager().getInstalledApplications(0)) {
            if (app.packageName.equals(c.getPackageName())) continue;
            if ((app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
            try {
                am.killBackgroundProcesses(app.packageName);
                n++;
            } catch (RuntimeException e) {
                AppLog.w("free ram " + app.packageName + ": " + e.getMessage());
            }
        }
        return n;
    }
}
