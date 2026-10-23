package com.cleanhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Continue on WakaTV": what was last watched in WakaTV. WakaTV shares nothing
 * with other apps (no media session, no deep links, and Android TV's Watch
 * Next list is only readable by system apps), so {@link HomeKeyService} reads
 * its screens instead: the title and IMDb id on a movie/series page, and the
 * title and elapsed/total time on the player's controls. Cards show the
 * title's landscape backdrop from the image service Stremio uses, by IMDb id;
 * when playback started without passing the title's page, the id is looked up
 * by name in Stremio's Cinemeta catalog. Logs use the tag "CleanHomeWaka".
 */
final class Waka {
    static final String PKG = "com.mm.droid.livetv.wakatv";
    private static final String TAG = "CleanHomeWaka";
    private static final int MAX = 12;
    // Watched past this fraction counts as finished and leaves the row.
    private static final float DONE = 0.95f;
    private static final String BACKDROP = "https://images.metahub.space/background/medium/%s/img";
    private static final String SEARCH = "https://v3-cinemeta.strem.io/catalog/%s/top/search=%s.json";
    private static final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Called on the main thread when the list changes (set by the home screen). */
    static Runnable listener;

    // The movie/series page seen last, so the player's title can be matched to its IMDb id.
    private static String pageName, pageId;

    /** A movie/series page is on screen. */
    static void onDetailPage(Context c, String name, String imdb) {
        pageName = name;
        pageId = imdb != null && imdb.matches("tt\\d+") ? imdb : null;
    }

    /**
     * The player is on screen. elapsed/total are in ms, or -1 when the controls
     * (which show the times) are hidden.
     */
    static void onPlayer(Context c, String title, long elapsed, long total) {
        String name = title, id = null, episode = "";
        if (pageName != null && norm(title).startsWith(norm(pageName))) {
            name = pageName;
            id = pageId;
            episode = title.substring(Math.min(title.length(), pageName.length())).replaceAll("^[\\s:·\\-–]+", "");
        }
        String key = id != null ? id : "t:" + norm(name);
        try {
            JSONArray a = index(c), out = new JSONArray();
            JSONObject e = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.getString("key").equals(key)) e = o;
                else out.put(o);
            }
            if (e == null) e = new JSONObject().put("key", key);
            e.put("name", name).put("episode", episode).put("seen", System.currentTimeMillis());
            if (id != null) e.put("id", id);
            if (elapsed >= 0 && total > 0) e.put("elapsed", elapsed).put("total", total);
            boolean done = e.optLong("total") > 0 && e.optLong("elapsed") >= DONE * e.optLong("total");
            JSONArray sorted = new JSONArray();
            if (!done) sorted.put(e);
            for (int i = 0; i < out.length() && sorted.length() < MAX; i++) sorted.put(out.get(i));
            String after = sorted.toString();
            boolean changed = !stripSeen(prefs(c).getString("index", "[]")).equals(stripSeen(after));
            prefs(c).edit().putString("index", after).apply();
            if (!changed) return;
            Log.i(TAG, "watching \"" + name + "\" " + episode + " " + elapsed / 1000 + "/" + total / 1000 + " s");
            fetchArt(c.getApplicationContext(), name, id);
            if (listener != null) listener.run();
        } catch (Exception ex) {
            Log.w(TAG, "record: " + ex);
        }
    }

    /** The row, most recent first; Movie.id is the entry's key. */
    static List<Movies.Movie> cached(Context c) {
        List<Movies.Movie> out = new ArrayList<>();
        try {
            JSONArray a = index(c);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id", null);
                if (id == null) id = prefs(c).getString("id:" + norm(o.optString("name")), null);
                File f = id == null || id.isEmpty() ? null : new File(dir(c), id + ".jpg");
                if ((f == null && id == null) || (f != null && !f.isFile())) {
                    fetchArt(c.getApplicationContext(), o.optString("name"), id);
                }
                long total = o.optLong("total"), left = (total - o.optLong("elapsed")) / 60_000;
                float progress = total > 0 ? o.optLong("elapsed") / (float) total : -1f;
                String note = total <= 0 ? "" : left >= 60
                        ? String.format(Locale.US, "%dh %02dm left", left / 60, left % 60)
                        : Math.max(1, left) + " min left";
                out.add(new Movies.Movie(o.getString("key"), o.optString("name"), o.optString("episode"),
                        f != null && f.isFile() ? f : null, progress, note));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------------------------------------------------------------- reopening

    // Set when a card is chosen: HomeKeyService steers WakaTV to this title.
    private static String wanted;
    private static long wantedUntil;

    static void open(String name) {
        wanted = name;
        wantedUntil = android.os.SystemClock.elapsedRealtime() + 30_000;
    }

    /** The title being reopened, or null. */
    static String wanted() {
        if (wanted != null && android.os.SystemClock.elapsedRealtime() > wantedUntil) wanted = null;
        return wanted;
    }

    static void opened() {
        wanted = null;
    }

    static boolean same(String a, String b) {
        return a != null && b != null && norm(a).equals(norm(b));
    }

    // ---------------------------------------------------------------- storage

    /** Downloads the title's backdrop, first finding its IMDb id by name if needed. */
    private static void fetchArt(Context c, String name, String knownId) {
        io.execute(() -> {
            String id = knownId != null ? knownId : lookup(c, name);
            if (id == null) return;
            File f = new File(dir(c), id + ".jpg");
            if (f.isFile()) return;
            try {
                byte[] img = Movies.get(String.format(Locale.US, BACKDROP, id));
                File tmp = new File(f.getPath() + ".part");
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(img);
                }
                tmp.renameTo(f);
                // Drop art no longer in the row.
                List<String> keep = new ArrayList<>();
                JSONArray entries = index(c);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject o = entries.getJSONObject(i);
                    keep.add(o.optString("id", prefs(c).getString("id:" + norm(o.optString("name")), "")) + ".jpg");
                }
                File[] all = dir(c).listFiles();
                if (all != null) for (File x : all) if (!keep.contains(x.getName())) x.delete();
                Log.i(TAG, "art for \"" + name + "\" (" + id + ")");
                if (listener != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(listener);
            } catch (Exception e) {
                Log.w(TAG, "art " + id + ": " + e);
            }
        });
    }

    /**
     * The IMDb id for a title, from Cinemeta's movie then series search: an
     * exact name match, else the first movie result. Remembered per name ("" for
     * no match); null if the search couldn't be reached.
     */
    private static String lookup(Context c, String name) {
        String key = "id:" + norm(name);
        String known = prefs(c).getString(key, null);
        if (known != null) return known.isEmpty() ? null : known;
        String first = null, exact = null;
        boolean reached = false;
        for (String type : new String[]{"movie", "series"}) {
            try {
                String url = String.format(Locale.US, SEARCH, type, android.net.Uri.encode(name));
                JSONArray metas = new JSONObject(new String(Movies.get(url), "UTF-8")).optJSONArray("metas");
                reached = true;
                for (int i = 0; metas != null && i < metas.length(); i++) {
                    JSONObject m = metas.getJSONObject(i);
                    String id = m.optString("id");
                    if (!id.matches("tt\\d+")) continue;
                    if (first == null && type.equals("movie")) first = id;
                    if (same(m.optString("name"), name)) {
                        exact = id;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // no results comes back as an error page for some searches
            }
            if (exact != null) break;
        }
        String id = exact != null ? exact : first;
        if (reached) prefs(c).edit().putString(key, id == null ? "" : id).apply();
        Log.i(TAG, "lookup \"" + name + "\" -> " + id);
        return id;
    }

    private static String stripSeen(String json) {
        return json.replaceAll("\"seen\":\\d+", "");
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** "01:02:34" or "45:10" in ms, or -1. */
    static long time(CharSequence t) {
        if (t == null || !t.toString().trim().matches("\\d{1,2}(:\\d{2}){1,2}")) return -1;
        long s = 0;
        for (String p : t.toString().trim().split(":")) s = s * 60 + Long.parseLong(p);
        return s * 1000;
    }

    private static JSONArray index(Context c) throws Exception {
        return new JSONArray(prefs(c).getString("index", "[]"));
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "waka");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("waka", Context.MODE_PRIVATE);
    }
}
