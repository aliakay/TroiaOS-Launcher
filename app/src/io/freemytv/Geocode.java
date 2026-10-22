package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Open-Meteo's geocoding (no key): the places with a name, for the weather's city (Customize and Set Up). */
final class Geocode {
    private Geocode() {}

    /** Up to 8 places called `query`: name, detail (region, country), latitude, longitude. Call off the UI thread. */
    static List<String[]> search(String query, String lang) throws Exception {
        String body = get("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(query.trim(), "UTF-8"));
        List<String[]> found = new ArrayList<>();
        JSONArray results = new JSONObject(body).optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String detail = o.optString("admin1", "");
            String country = o.optString("country", "");
            if (!country.isEmpty()) detail = detail.isEmpty() ? country : detail + ", " + country;
            found.add(new String[] {o.optString("name"), detail, String.valueOf(o.getDouble("latitude")),
                    String.valueOf(o.getDouble("longitude"))});
        }
        return found;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
