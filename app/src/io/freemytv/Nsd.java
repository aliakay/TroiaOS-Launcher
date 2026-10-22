package io.freemytv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Finds devices that announce themselves on the home network (mDNS / Bonjour): the Hue Bridge, Home Assistant. Only
 * listens on the local network; nothing goes to the internet. Blocking: never on the UI thread.
 */
final class Nsd {
    private Nsd() {}

    /** "ip:port" of each device of `type` heard within `waitMs` (at most 4, resolved one after the other). */
    static List<String> find(Context c, String type, int waitMs) {
        NsdManager nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        final List<NsdServiceInfo> heard = new ArrayList<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int code) { AppLog.w("nsd: can't search (" + code + ")"); }
            @Override public void onStopDiscoveryFailed(String t, int code) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo s) { synchronized (heard) { heard.add(s); } }
            @Override public void onServiceLost(NsdServiceInfo s) {}
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            Thread.sleep(waitMs);
        } catch (InterruptedException | RuntimeException e) {
            AppLog.w("nsd: " + e.getMessage());
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
        List<NsdServiceInfo> list;
        synchronized (heard) {
            list = new ArrayList<>(heard);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.size() && i < 4; i++) {
            final String[] result = new String[1];
            final CountDownLatch done = new CountDownLatch(1);
            try {
                nsd.resolveService(list.get(i), new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int code) { done.countDown(); }
                    @Override public void onServiceResolved(NsdServiceInfo s) {
                        if (s.getHost() != null) result[0] = s.getHost().getHostAddress() + ":" + s.getPort();
                        done.countDown();
                    }
                });
                done.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException | RuntimeException e) {
                AppLog.w("nsd resolve: " + e.getMessage());
            }
            if (result[0] != null && !out.contains(result[0])) out.add(result[0]);
        }
        return out;
    }
}
