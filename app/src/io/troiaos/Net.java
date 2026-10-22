package io.troiaos;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Requests to devices at home (the Hue Bridge, Home Assistant). The app forbids plain HTTP (usesCleartextTraffic is
 * false), and keeps it that way for the internet: an http:// address is only ever spoken to when it is on the home
 * network (private, link-local or Tailscale addresses), over a small HTTP/1.1 client of its own. https:// goes through
 * the system as usual.
 */
final class Net {
    private Net() {}

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static final int MAX_BODY = 4 << 20;

    /** `token`: sent as "Authorization: Bearer …" when not empty. Blocking: never on the main thread. */
    static Response request(String method, String url, String token, String json, int timeoutMs) throws IOException {
        URI u;
        try {
            u = new URI(url.trim());
        } catch (Exception e) {
            throw new IOException("not an address: " + url);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return viaSystem(method, url.trim(), token, json, timeoutMs);
        if (!scheme.equals("http") || u.getHost() == null) throw new IOException("not an http(s) address: " + url);
        InetAddress host = InetAddress.getByName(u.getHost());
        if (!home(host)) throw new IOException("plain http only on your home network (" + u.getHost() + ")");
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n")
                    .append("User-Agent: TroiaOS\r\nAccept: application/json\r\nConnection: close\r\n");
            if (token != null && !token.isEmpty()) head.append("Authorization: Bearer ").append(token).append("\r\n");
            if (json != null) head.append("Content-Type: application/json\r\nContent-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            return read(new BufferedInputStream(s.getInputStream()));
        }
    }

    /** True for addresses inside the home: 10/8, 172.16/12, 192.168/16, 169.254/16, Tailscale's 100.64/10, fc00::/7. */
    static boolean home(InetAddress a) {
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return (b[0] & 255) == 100 && (b[1] & 0xC0) == 64;
        return a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC;
    }

    private static Response viaSystem(String method, String url, String token, String json, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", "TroiaOS");
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? "" : new String(readAll(in, -1), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Status line, headers, then the body (Content-Length, chunked, or until the connection closes). */
    private static Response read(InputStream in) throws IOException {
        String status = line(in);
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not an HTTP answer");
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad HTTP status");
        }
        int length = -1;
        boolean chunked = false;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            String lower = h.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
            else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
        }
        byte[] body;
        if (chunked) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String size = line(in);
                int semi = size.indexOf(';');
                int n = Integer.parseInt((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                if (n == 0) break;
                if (out.size() + n > MAX_BODY) throw new IOException("answer too large");
                out.write(readAll(in, n));
                line(in);
            }
            body = out.toByteArray();
        } else {
            if (length > MAX_BODY) throw new IOException("answer too large");
            body = readAll(in, length);
        }
        return new Response(code, new String(body, StandardCharsets.UTF_8));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch = in.read(); ch != -1 && ch != '\n'; ch = in.read()) {
            if (ch != '\r') b.write(ch);
            if (b.size() > 16 << 10) throw new IOException("header too long");
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** `n` bytes, or (n < 0) everything until the end. */
    private static byte[] readAll(InputStream in, int n) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (n < 0 || out.size() < n) {
            int r = in.read(buf, 0, n < 0 ? buf.length : Math.min(buf.length, n - out.size()));
            if (r < 0) {
                if (n >= 0) throw new IOException("answer cut short");
                break;
            }
            out.write(buf, 0, r);
            if (out.size() > MAX_BODY) throw new IOException("answer too large");
        }
        return out.toByteArray();
    }
}
