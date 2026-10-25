package io.troiaos;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TP-Link Tapo bulbs (L530, L535 and the like) on the home network, with no cloud: the bulb is spoken to directly over
 * its local KLAP protocol (an encrypted session set up with the Tapo account's e-mail and password, which the bulb
 * itself checks). Tapo bulbs don't announce themselves, so their addresses are typed once in Customize → Lights → Tapo;
 * the e-mail and password are kept encrypted on the TV (Prefs.putSecret). Plain http is only ever spoken to home-network
 * addresses (Net.home). One session per bulb, renewed when it expires or drops. Blocking: never on the UI thread.
 */
final class Tapo {
    private Tapo() {}

    private static final int CONNECT_MS = 3000, READ_MS = 5000, MAX_BODY = 64 * 1024;
    private static final long SESSION_MS = 20 * 60 * 60 * 1000L;   // a bulb grants 24 hours

    /** The keys of one session, and the connection it was made on (the bulb ties the cookie to it). */
    private static final class Session {
        byte[] key, iv, sig;
        int seq;
        String cookie;
        long expiresAt;
        Socket socket;
        InputStream in;
        OutputStream out;
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    // MARK: - Lamps

    /** The bulbs at the saved addresses that answered (those that don't are left out, like any other brand). */
    static List<Lights.Lamp> lamps(String ips, String user, String pass) {
        List<Lights.Lamp> out = new ArrayList<>();
        for (String ip : addresses(ips)) {
            try {
                out.add(lamp(ip, info(ip, user, pass)));
            } catch (Exception e) {
                AppLog.w("tapo " + ip + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Null if the first bulb accepts the account; otherwise the reason. */
    static String check(String ips, String user, String pass) {
        List<String> list = addresses(ips);
        if (list.isEmpty()) return Ui.tr("Adres yok", "No address");
        for (String ip : list) {
            try {
                info(ip, user, pass);
            } catch (SecurityException e) {
                return Ui.tr("Tapo hesabı kabul edilmedi (e-posta veya şifre)", "The Tapo account wasn't accepted (e-mail or password)");
            } catch (Exception e) {
                return ip + ": " + e.getMessage();
            }
        }
        return null;
    }

    static boolean set(String user, String pass, List<Lights.Lamp> lamps, Lights.Pilot p) {
        boolean ok = true;
        for (Lights.Lamp l : lamps) {
            try {
                JSONObject s = new JSONObject();
                if (p.on != null) s.put("device_on", p.on);
                if (!Boolean.FALSE.equals(p.on)) {
                    if (p.dimming >= 0) s.put("brightness", Math.max(1, Math.min(100, p.dimming)));
                    if (p.temp > 0 && l.white) {
                        s.put("color_temp", Math.max(2500, Math.min(6500, p.temp)));
                    } else if (p.rgb >= 0 && l.color) {
                        float[] hsv = Lights.hsv(p.rgb);
                        s.put("hue", Math.round(hsv[0]) % 360).put("saturation", Math.round(hsv[1] * 100)).put("color_temp", 0);
                    }
                }
                JSONObject reply = new JSONObject(call(l.ip, user, pass, "set_device_info", s));
                ok &= reply.optInt("error_code", -1) == 0;
            } catch (Exception e) {
                AppLog.w("tapo set: " + e.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    /** "192.168.1.5, 192.168.1.6" (commas, spaces or lines) → the addresses; anything outside the home is left out. */
    static List<String> addresses(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[,;\\s]+")) {
            String ip = part.trim();
            if (ip.isEmpty() || out.contains(ip) || !ip.matches("[0-9.]{7,15}")) continue;
            try {
                if (Net.home(InetAddress.getByName(ip))) out.add(ip);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static JSONObject info(String ip, String user, String pass) throws Exception {
        JSONObject reply = new JSONObject(call(ip, user, pass, "get_device_info", null));
        if (reply.optInt("error_code", -1) != 0) throw new IOException("error_code " + reply.optInt("error_code", -1));
        return reply.getJSONObject("result");
    }

    private static Lights.Lamp lamp(String ip, JSONObject i) throws JSONException {
        Lights.Lamp l = new Lights.Lamp();
        l.brand = Lights.TAPO;
        l.id = ip;
        l.ip = ip;
        String nick = i.optString("nickname", "");
        try {
            nick = nick.isEmpty() ? "" : new String(Base64.decode(nick, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        l.deviceName = nick.isEmpty() ? i.optString("model", "Tapo") : nick;
        l.on = i.optBoolean("device_on");
        l.dimming = Math.max(1, i.optInt("brightness", 100));
        l.color = i.has("hue") && i.has("saturation");
        l.white = i.has("color_temp");
        int ct = i.optInt("color_temp", 0);
        if (ct > 0) {
            l.temp = ct;
        } else if (l.color) {
            int rgb = android.graphics.Color.HSVToColor(new float[] {i.optInt("hue"), i.optInt("saturation") / 100f, 1});
            l.r = (rgb >> 16) & 255;
            l.g = (rgb >> 8) & 255;
            l.b = rgb & 255;
        }
        return l;
    }

    // MARK: - KLAP

    private static String call(String ip, String user, String pass, String method, JSONObject params) throws Exception {
        JSONObject req = new JSONObject().put("method", method);
        if (params != null) req.put("params", params);
        req.put("requestTimeMils", System.currentTimeMillis());
        String json = req.toString();
        synchronized (LOCKS.computeIfAbsent(ip, k -> new Object())) {
            Session s = SESSIONS.get(ip);
            if (s == null || System.currentTimeMillis() > s.expiresAt) s = fresh(ip, user, pass);
            try {
                return send(s, ip, json);
            } catch (IOException e) {
                // The bulb dropped the session (it rebooted or timed out): once more with a new one.
                s = fresh(ip, user, pass);
                return send(s, ip, json);
            }
        }
    }

    private static Session fresh(String ip, String user, String pass) throws Exception {
        Session old = SESSIONS.remove(ip);
        if (old != null) close(old);
        Session s = handshake(ip, user, pass);
        SESSIONS.put(ip, s);
        return s;
    }

    private static Session handshake(String ip, String user, String pass) throws Exception {
        InetAddress host = InetAddress.getByName(ip);
        if (!Net.home(host)) throw new IOException("plain http only on your home network (" + ip + ")");
        byte[] localSeed = new byte[16];
        RANDOM.nextBytes(localSeed);
        byte[] auth = sha256(concat(sha1(user.getBytes(StandardCharsets.UTF_8)), sha1(pass.getBytes(StandardCharsets.UTF_8))));
        Session s = new Session();
        try {
            s.socket = new Socket();
            s.socket.connect(new InetSocketAddress(host, 80), CONNECT_MS);
            s.socket.setSoTimeout(READ_MS);
            s.in = s.socket.getInputStream();
            s.out = s.socket.getOutputStream();

            Reply first = post(s, ip, "/app/handshake1", localSeed, null);
            if (first.code != 200 || first.body.length < 48) throw new IOException("handshake1 HTTP " + first.code);
            if (first.cookie == null) throw new IOException("handshake1 without a session");
            byte[] remoteSeed = Arrays.copyOfRange(first.body, 0, 16);
            byte[] serverHash = Arrays.copyOfRange(first.body, 16, 48);
            if (!MessageDigest.isEqual(serverHash, sha256(concat(localSeed, remoteSeed, auth)))) {
                throw new SecurityException("Tapo account rejected");
            }
            Reply second = post(s, ip, "/app/handshake2", sha256(concat(remoteSeed, localSeed, auth)), first.cookie);
            if (second.code != 200) throw new IOException("handshake2 HTTP " + second.code);

            s.cookie = first.cookie;
            s.key = Arrays.copyOf(sha256(concat(bytes("lsk"), localSeed, remoteSeed, auth)), 16);
            byte[] ivFull = sha256(concat(bytes("iv"), localSeed, remoteSeed, auth));
            s.iv = Arrays.copyOf(ivFull, 12);
            s.seq = ByteBuffer.wrap(ivFull, 28, 4).getInt();
            s.sig = Arrays.copyOf(sha256(concat(bytes("ldk"), localSeed, remoteSeed, auth)), 28);
            s.expiresAt = System.currentTimeMillis() + SESSION_MS;
            return s;
        } catch (Exception e) {
            close(s);
            throw e;
        }
    }

    private static String send(Session s, String ip, String json) throws Exception {
        s.seq++;
        byte[] seq = ByteBuffer.allocate(4).putInt(s.seq).array();
        byte[] cipherText = aes(Cipher.ENCRYPT_MODE, s, seq, json.getBytes(StandardCharsets.UTF_8));
        byte[] signature = sha256(concat(s.sig, seq, cipherText));
        Reply r = post(s, ip, "/app/request?seq=" + s.seq, concat(signature, cipherText), s.cookie);
        if (r.code != 200 || r.body.length <= 32) throw new IOException("request HTTP " + r.code);
        return new String(aes(Cipher.DECRYPT_MODE, s, seq, Arrays.copyOfRange(r.body, 32, r.body.length)), StandardCharsets.UTF_8);
    }

    private static byte[] aes(int mode, Session s, byte[] seq, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(s.key, "AES"), new IvParameterSpec(concat(s.iv, seq)));
        return cipher.doFinal(data);
    }

    // MARK: - HTTP over one connection (the app has no cleartext traffic of its own: see Net)

    private static final class Reply {
        int code;
        byte[] body;
        String cookie;
    }

    private static Reply post(Session s, String ip, String path, byte[] body, String cookie) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("POST ").append(path).append(" HTTP/1.1\r\nHost: ").append(ip).append("\r\n")
                .append("Content-Type: application/octet-stream\r\nContent-Length: ").append(body.length).append("\r\n")
                .append("Connection: keep-alive\r\n");
        if (cookie != null) head.append("Cookie: ").append(cookie).append("\r\n");
        head.append("\r\n");
        s.out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        s.out.write(body);
        s.out.flush();

        String status = line(s.in);
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not an HTTP answer");
        Reply r = new Reply();
        try {
            r.code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad HTTP status");
        }
        int length = -1;
        for (String h = line(s.in); !h.isEmpty(); h = line(s.in)) {
            String lower = h.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(h.substring(15).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length");
                }
            } else if (lower.startsWith("set-cookie:")) {
                for (String part : h.substring(11).split(";")) {
                    if (part.trim().startsWith("TP_SESSIONID=")) r.cookie = part.trim();
                }
            }
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("unexpected answer size");
        r.body = new byte[length];
        for (int off = 0; off < length; ) {
            int n = s.in.read(r.body, off, length - off);
            if (n < 0) throw new IOException("connection closed");
            off += n;
        }
        return r;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new IOException("connection closed");
            if (c != '\r') out.write(c);
            if (out.size() > 8192) throw new IOException("header too long");
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void close(Session s) {
        try {
            if (s.socket != null) s.socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] bytes(String v) {
        return v.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] sha1(byte[] d) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(d);
    }

    private static byte[] sha256(byte[] d) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(d);
    }
}
