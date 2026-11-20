package io.troiaos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Tonight" card: a live or upcoming game of the teams followed (Prefs.sportsTeams), from ESPN's public scoreboard
 * JSON (site.api.espn.com; unofficial, no key). Each league's whole scoreboard is read, so ESPN never learns which
 * teams are followed. Every 2 minutes while the home screen shows (every 15 when there's no game today), cached so the
 * screen opens with the card.
 */
final class Sports {
    private static final long EVERY = 2 * 60_000L, QUIET = 15 * 60_000L;
    /** A game that started this long ago and never said it ended (no refresh since: offline) is dropped. */
    private static final long STALE = 5 * 3600_000L;

    interface Listener { void onSports(); }

    /** ESPN's path and the name shown, by league key. */
    static final String[][] LEAGUES = {{"nba", "basketball/nba", "NBA"}, {"mlb", "baseball/mlb", "MLB"}};

    static final String[][] NBA = {{"ATL", "Hawks"}, {"BOS", "Celtics"}, {"BKN", "Nets"}, {"CHA", "Hornets"}, {"CHI", "Bulls"},
            {"CLE", "Cavaliers"}, {"DAL", "Mavericks"}, {"DEN", "Nuggets"}, {"DET", "Pistons"}, {"GS", "Warriors"},
            {"HOU", "Rockets"}, {"IND", "Pacers"}, {"LAC", "Clippers"}, {"LAL", "Lakers"}, {"MEM", "Grizzlies"},
            {"MIA", "Heat"}, {"MIL", "Bucks"}, {"MIN", "Timberwolves"}, {"NO", "Pelicans"}, {"NY", "Knicks"},
            {"OKC", "Thunder"}, {"ORL", "Magic"}, {"PHI", "76ers"}, {"PHX", "Suns"}, {"POR", "Trail Blazers"},
            {"SAC", "Kings"}, {"SA", "Spurs"}, {"TOR", "Raptors"}, {"UTAH", "Jazz"}, {"WSH", "Wizards"}};
    static final String[][] MLB = {{"ARI", "Diamondbacks"}, {"ATH", "Athletics"}, {"ATL", "Braves"}, {"BAL", "Orioles"},
            {"BOS", "Red Sox"}, {"CHC", "Cubs"}, {"CHW", "White Sox"}, {"CIN", "Reds"}, {"CLE", "Guardians"},
            {"COL", "Rockies"}, {"DET", "Tigers"}, {"HOU", "Astros"}, {"KC", "Royals"}, {"LAA", "Angels"},
            {"LAD", "Dodgers"}, {"MIA", "Marlins"}, {"MIL", "Brewers"}, {"MIN", "Twins"}, {"NYM", "Mets"},
            {"NYY", "Yankees"}, {"PHI", "Phillies"}, {"PIT", "Pirates"}, {"SD", "Padres"}, {"SF", "Giants"},
            {"SEA", "Mariners"}, {"STL", "Cardinals"}, {"TB", "Rays"}, {"TEX", "Rangers"}, {"TOR", "Blue Jays"},
            {"WSH", "Nationals"}};

    static String[][] teams(String league) {
        return league.equals("mlb") ? MLB : NBA;
    }

    /** "Raptors" for "nba:TOR" (the abbreviation when unknown). */
    static String teamName(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return key;
        String abbr = key.substring(colon + 1);
        for (String[] t : teams(key.substring(0, colon))) if (t[0].equals(abbr)) return t[1];
        return abbr;
    }

    static final class Game {
        String league = "", state = "", detail = "";
        String away = "", home = "", awayName = "", homeName = "";
        int awayColor, homeColor;
        String awayScore = "", homeScore = "";
        long start;

        boolean live() { return state.equals("in"); }

        JSONObject json() throws Exception {
            return new JSONObject().put("league", league).put("state", state).put("detail", detail)
                    .put("away", away).put("home", home).put("awayName", awayName).put("homeName", homeName)
                    .put("awayColor", awayColor).put("homeColor", homeColor)
                    .put("awayScore", awayScore).put("homeScore", homeScore).put("start", start);
        }

        static Game from(JSONObject o) {
            Game g = new Game();
            g.league = o.optString("league");
            g.state = o.optString("state");
            g.detail = o.optString("detail");
            g.away = o.optString("away");
            g.home = o.optString("home");
            g.awayName = o.optString("awayName");
            g.homeName = o.optString("homeName");
            g.awayColor = o.optInt("awayColor");
            g.homeColor = o.optInt("homeColor");
            g.awayScore = o.optString("awayScore");
            g.homeScore = o.optString("homeScore");
            g.start = o.optLong("start");
            return g;
        }
    }

    private static Sports instance;

    static synchronized Sports get(Context c) {
        if (instance == null) instance = new Sports(c.getApplicationContext());
        return instance;
    }

    private volatile Game game;
    private volatile long at;
    private volatile String teams;      // the teams the game was found for
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetching;
    private volatile long failedAt;

    private Sports(Context c) {
        cache = c.getSharedPreferences("sports", Context.MODE_PRIVATE);
        at = cache.getLong("at", 0);
        teams = cache.getString("teams", "");
        String saved = cache.getString("game", "");
        if (!saved.isEmpty()) {
            try {
                game = Game.from(new JSONObject(saved));
            } catch (Exception ignored) {
            }
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The game to show now, or null: live, or starting later today or tomorrow. Main thread. */
    Game current() {
        Game g = game;
        if (g == null || !Prefs.sportsOn() || !Prefs.sportsTeams().equals(teams)) return null;
        long now = System.currentTimeMillis();
        if (g.state.equals("post") || now - g.start > STALE || g.start >= dayStart(now, 2)) return null;
        return g;
    }

    void refreshIfStale() {
        final String want = Prefs.sportsOn() ? Prefs.sportsTeams() : "";
        long now = System.currentTimeMillis();
        boolean changed = !want.equals(teams);
        if (fetching || (want.isEmpty() && !changed) || (now >= failedAt && now - failedAt < EVERY)) return;
        Game g = game;
        boolean soon = g != null && g.start < dayStart(now, 1) && !g.state.equals("post");
        if (!changed && now >= at && now - at < (soon ? EVERY : QUIET)) return;
        fetching = true;
        io.execute(() -> {
            try {
                Game found = want.isEmpty() ? null : find(Prefs.split(want));
                game = found;
                teams = want;
                at = System.currentTimeMillis();
                String json = "";
                if (found != null) {
                    try {
                        json = found.json().toString();
                    } catch (Exception ignored) {
                    }
                }
                cache.edit().putLong("at", at).putString("teams", want).putString("game", json).apply();
                AppLog.w("sports: " + (found == null ? "no game" : found.away + "@" + found.home + " " + found.state + " " + found.detail));
                main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onSports(); });
            } catch (Exception e) {
                AppLog.w("sports: " + e.getMessage());     // keeps what it had; tried again in 2 minutes
                failedAt = System.currentTimeMillis();
            } finally {
                fetching = false;
            }
        });
    }

    /** The best game for these teams: a live one first, else the earliest still to come today or tomorrow. */
    private static Game find(List<String> wanted) throws IOException {
        Game best = null;
        long now = System.currentTimeMillis(), end = dayStart(now, 2);
        for (String[] league : LEAGUES) {
            List<String> abbrs = new ArrayList<>();
            for (String w : wanted) if (w.startsWith(league[0] + ":")) abbrs.add(w.substring(league[0].length() + 1));
            if (abbrs.isEmpty()) continue;
            String base = "https://site.api.espn.com/apis/site/v2/sports/" + league[1] + "/scoreboard";
            List<Game> games = new ArrayList<>();
            // ESPN's own "today" (it stays on last night's games past midnight, so a late game stays live); then
            // the local today if that's another day, and tomorrow when nothing is left today.
            String espnDay = scan(get(base), league[2], abbrs, games);
            String today = ymd(now, 0), tomorrow = ymd(now, 1);
            if (!today.equals(espnDay)) scan(get(base + "?dates=" + today), league[2], abbrs, games);
            boolean left = false;
            for (Game g : games) left |= g.live() || (g.state.equals("pre") && g.start < dayStart(now, 1));
            if (!left && !tomorrow.equals(espnDay)) scan(get(base + "?dates=" + tomorrow), league[2], abbrs, games);
            for (Game g : games) {
                if (g.state.equals("post") || now - g.start > STALE || g.start >= end) continue;
                if (best == null || (g.live() && !best.live()) || (g.live() == best.live() && g.start < best.start)) best = g;
            }
        }
        return best;
    }

    /**
     * Adds the games of `abbrs` in one scoreboard; returns its day ("20261120"). The shape:
     * {"day":{"date":"2026-11-20"},"events":[{"date":"2026-11-21T00:30Z","status":{"type":{"state":"pre|in|post",
     * "shortDetail":"8:42 - 3rd"}},"competitions":[{"competitors":[{"homeAway":"home","score":"12",
     * "team":{"abbreviation":"DET","shortDisplayName":"Pistons","color":"1d428a"}}]}]}]}
     */
    private static String scan(String body, String league, List<String> abbrs, List<Game> out) {
        String day = "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject d = root.optJSONObject("day");
            if (d != null) day = d.optString("date").replace("-", "");
            JSONArray events = root.optJSONArray("events");
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                JSONArray comps = e.optJSONArray("competitions");
                if (comps == null || comps.length() == 0) continue;
                JSONArray teams = comps.getJSONObject(0).optJSONArray("competitors");
                if (teams == null) continue;
                Game g = new Game();
                boolean ours = false;
                for (int j = 0; j < teams.length(); j++) {
                    JSONObject c = teams.getJSONObject(j), t = c.optJSONObject("team");
                    if (t == null) continue;
                    String abbr = t.optString("abbreviation");
                    ours |= abbrs.contains(abbr);
                    int color = color(t.optString("color"));
                    if (c.optString("homeAway").equals("home")) {
                        g.home = abbr;
                        g.homeName = t.optString("shortDisplayName", abbr);
                        g.homeScore = c.optString("score");
                        g.homeColor = color;
                    } else {
                        g.away = abbr;
                        g.awayName = t.optString("shortDisplayName", abbr);
                        g.awayScore = c.optString("score");
                        g.awayColor = color;
                    }
                }
                if (!ours) continue;
                JSONObject type = e.optJSONObject("status") == null ? null : e.getJSONObject("status").optJSONObject("type");
                g.state = type == null ? "" : type.optString("state");
                g.detail = type == null ? "" : type.optString("shortDetail");
                g.league = league;
                g.start = parseDate(e.optString("date"));
                if (g.start > 0) out.add(g);
            }
        } catch (Exception ex) {
            AppLog.w("sports scoreboard: " + ex.getMessage());
        }
        return day;
    }

    /** "1d428a" → opaque color (0 when missing). */
    private static int color(String hex) {
        try {
            return hex.length() == 6 ? 0xFF000000 | Integer.parseInt(hex, 16) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** ESPN's "2026-11-21T00:30Z" (UTC, no seconds). */
    private static long parseDate(String s) {
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** Local midnight, `days` from today. */
    static long dayStart(long now, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /** "20261121" for the local day `days` from today. */
    static String ymd(long now, int days) {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(dayStart(now, days) + 3600_000L));
    }

    private static String get(String url) throws IOException {
        Net.Response r = Net.request("GET", url, null, null, 8000);
        if (r.code != 200) throw new IOException("HTTP " + r.code + " " + url);
        return r.body;
    }

    /**
     * The game's channel in a live TV app's row on the TV's home screen (preview programs: Fibe TV lists what's on
     * its channels now, each with a link that tunes to it): a program naming both teams, else one naming either.
     * Needs READ_TV_LISTINGS (WatchNext). Null when it isn't there. Off the UI thread.
     */
    static String channelLink(Context c, String pkg, Game g) {
        if (!WatchNext.allowed(c)) return null;
        String a = g.awayName.toLowerCase(Locale.ROOT), h = g.homeName.toLowerCase(Locale.ROOT);
        String both = null, one = null;
        int count = 0;
        String[] cols = {"package_name", "title", "episode_title", "intent_uri"};
        try (android.database.Cursor q = c.getContentResolver().query(
                android.net.Uri.parse("content://android.media.tv/preview_program"), cols, null, null, null)) {
            while (q != null && q.moveToNext()) {
                if (!pkg.equals(q.getString(0)) || q.getString(3) == null) continue;
                count++;
                String text = ((q.getString(1) == null ? "" : q.getString(1)) + " "
                        + (q.getString(2) == null ? "" : q.getString(2))).toLowerCase(Locale.ROOT);
                boolean ha = !a.isEmpty() && text.contains(a), hh = !h.isEmpty() && text.contains(h);
                if (ha && hh && both == null) both = q.getString(3);
                else if ((ha || hh) && one == null) one = q.getString(3);
                if (ha || hh) AppLog.w("sports: on " + WatchNext.channelName(q.getString(3)) + ": " + q.getString(1));
            }
        } catch (RuntimeException e) {
            AppLog.w("sports: channels: " + e.getMessage());
            return null;
        }
        AppLog.w("sports: " + count + " programs in " + pkg + "'s row, " + (both != null || one != null ? "found the game" : "no game"));
        return both != null ? both : one;
    }

    /** Above the matchup: "LIVE · NBA", "TONIGHT · NBA", "TOMORROW · MLB". */
    static String heading(Game g, long now) {
        String when = g.live() ? Ui.tr("CANLI", "LIVE")
                : g.start < dayStart(now, 1) ? (startHour(g) >= 17 ? Ui.tr("BU AKŞAM", "TONIGHT") : Ui.tr("BUGÜN", "TODAY"))
                : Ui.tr("YARIN", "TOMORROW");
        return when + " · " + g.league;
    }

    /** Below the matchup: the clock and period when live ("8:42 - 3rd", "Top 5th"), else the start time. */
    static String when(Game g) {
        if (g.live()) return g.detail;
        if (g.state.equals("pre") && g.start < System.currentTimeMillis()) return Ui.tr("Başlamak üzere", "Starting soon");
        return Ui.en ? Ui.clock12(g.start) : Ui.clock(g.start);
    }

    private static int startHour(Game g) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(g.start);
        return c.get(Calendar.HOUR_OF_DAY);
    }
}
