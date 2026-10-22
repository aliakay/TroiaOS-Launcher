package io.freemytv;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LIFX lights through their LAN protocol (UDP 56700, https://lan.developer.lifx.com): no account, no cloud. A
 * broadcast LightGet makes every lamp answer with its state and label; changes are SetColor (HSBK) and SetPower, sent
 * with an acknowledgement asked for, again to lamps that didn't confirm. Blocking: never on the UI thread.
 */
final class Lifx {
    private Lifx() {}

    private static final int PORT = 56700, SOURCE = 0x546F7269;   // "Tori": answers come back to us
    private static final int GET = 101, STATE = 107, SET_COLOR = 102, SET_POWER = 117, ACK = 45;
    private static final int FADE_MS = 300;

    static List<Lights.Lamp> discover(int waitMs) {
        Map<String, Lights.Lamp> found = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] ask = packet(GET, null, false, 0, new byte[0]);
            List<InetAddress> targets = new ArrayList<>(Wiz.broadcasts());
            targets.add(InetAddress.getByName("255.255.255.255"));
            long until = System.currentTimeMillis() + waitMs;
            for (int round = 0; round < 2; round++) {
                for (InetAddress t : targets) {
                    try {
                        s.send(new DatagramPacket(ask, ask.length, t, PORT));
                    } catch (IOException ignored) {
                    }
                }
                long roundEnd = round == 0 ? System.currentTimeMillis() + waitMs / 2 : until;
                byte[] buf = new byte[256];
                while (true) {
                    long left = roundEnd - System.currentTimeMillis();
                    if (left <= 0) break;
                    s.setSoTimeout((int) left);
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    Lights.Lamp l = parse(p.getData(), p.getLength(), p.getAddress().getHostAddress());
                    if (l != null) found.put(l.id, l);
                }
            }
        } catch (IOException e) {
            AppLog.w("lifx: " + e.getMessage());
        }
        List<Lights.Lamp> lamps = new ArrayList<>(found.values());
        Collections.sort(lamps, (a, b) -> a.deviceName.compareToIgnoreCase(b.deviceName));
        return lamps;
    }

    static boolean set(List<Lights.Lamp> lamps, Lights.Pilot p) {
        Map<String, byte[][]> messages = new LinkedHashMap<>();   // ip → its packets
        int seq = 1;
        for (Lights.Lamp l : lamps) {
            List<byte[]> list = new ArrayList<>();
            if (!Boolean.FALSE.equals(p.on) && (p.dimming >= 0 || p.temp > 0 || p.rgb >= 0)) {
                int hue = l.hue, sat = l.sat, kelvin = l.temp > 0 ? l.temp : 3500;
                int bri = Math.round(Math.max(1, Math.min(100, p.dimming >= 0 ? p.dimming : l.dimming)) * 65535 / 100f);
                if (p.temp > 0) {
                    sat = 0;
                    kelvin = Math.max(1500, Math.min(9000, p.temp));
                } else if (p.rgb >= 0) {
                    float[] hsv = Lights.hsv(p.rgb);
                    hue = Math.round(hsv[0] / 360f * 65535);
                    sat = Math.round(hsv[1] * 65535);
                }
                ByteBuffer b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
                b.put((byte) 0).putShort((short) hue).putShort((short) sat).putShort((short) bri).putShort((short) kelvin).putInt(FADE_MS);
                list.add(packet(SET_COLOR, l.target, true, seq++ & 255, b.array()));
            }
            if (p.on != null) {
                ByteBuffer b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
                b.putShort((short) (p.on ? 65535 : 0)).putInt(FADE_MS);
                list.add(packet(SET_POWER, l.target, true, seq++ & 255, b.array()));
            }
            messages.put(l.ip, list.toArray(new byte[0][]));
        }
        Set<String> waiting = new HashSet<>(messages.keySet());
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] buf = new byte[128];
            for (int attempt = 0; attempt < 3 && !waiting.isEmpty(); attempt++) {
                Map<String, Integer> acks = new LinkedHashMap<>();
                for (String ip : waiting) {
                    InetAddress a = InetAddress.getByName(ip);
                    for (byte[] m : messages.get(ip)) s.send(new DatagramPacket(m, m.length, a, PORT));
                    acks.put(ip, 0);
                }
                long until = System.currentTimeMillis() + 450;
                while (!waiting.isEmpty()) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    s.setSoTimeout((int) left);
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(dp);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    String ip = dp.getAddress().getHostAddress();
                    if (dp.getLength() < 36 || type(dp.getData()) != ACK || !acks.containsKey(ip)) continue;
                    int n = acks.get(ip) + 1;
                    acks.put(ip, n);
                    if (n >= messages.get(ip).length) waiting.remove(ip);
                }
            }
        } catch (IOException e) {
            AppLog.w("lifx set: " + e.getMessage());
            return false;
        }
        if (!waiting.isEmpty()) AppLog.w("lifx: no answer from " + waiting);
        return waiting.isEmpty();
    }

    /** The 36-byte header (protocol 1024, addressable; tagged when it's for every lamp) and the payload. */
    static byte[] packet(int type, byte[] target, boolean ack, int seq, byte[] payload) {
        int size = 36 + payload.length;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) size);
        b.putShort((short) (1024 | (1 << 12) | (target == null ? 1 << 13 : 0)));
        b.putInt(SOURCE);
        b.put(target == null ? new byte[8] : target);
        b.put(new byte[6]);
        b.put((byte) (ack ? 2 : 0));
        b.put((byte) seq);
        b.putLong(0);
        b.putShort((short) type);
        b.putShort((short) 0);
        b.put(payload);
        return b.array();
    }

    private static int type(byte[] d) {
        return (d[32] & 255) | (d[33] & 255) << 8;
    }

    /** LightState: HSBK, power and the lamp's label. */
    static Lights.Lamp parse(byte[] d, int length, String ip) {
        if (length < 36 + 52 || type(d) != STATE) return null;
        ByteBuffer b = ByteBuffer.wrap(d, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        Lights.Lamp l = new Lights.Lamp();
        l.brand = Lights.LIFX;
        l.ip = ip;
        l.target = new byte[8];
        System.arraycopy(d, 8, l.target, 0, 8);
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < 6; i++) mac.append(String.format("%02x", d[8 + i] & 255));
        l.id = mac.toString();
        l.hue = b.getShort(36) & 0xFFFF;
        l.sat = b.getShort(38) & 0xFFFF;
        int bri = b.getShort(40) & 0xFFFF, kelvin = b.getShort(42) & 0xFFFF;
        l.on = (b.getShort(46) & 0xFFFF) != 0;
        l.dimming = Math.max(1, Math.round(bri * 100f / 65535));
        if (l.sat < 2000) {
            l.temp = kelvin;
        } else {
            int rgb = android.graphics.Color.HSVToColor(new float[] {l.hue * 360f / 65535, l.sat / 65535f, 1});
            l.r = (rgb >> 16) & 255;
            l.g = (rgb >> 8) & 255;
            l.b = rgb & 255;
        }
        int end = 48;
        while (end < 80 && d[end] != 0) end++;
        l.deviceName = new String(d, 48, end - 48, StandardCharsets.UTF_8).trim();
        if (l.deviceName.isEmpty()) l.deviceName = "LIFX";
        return l;
    }
}
