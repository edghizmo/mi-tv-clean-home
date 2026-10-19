package com.cleanhome.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * "Popular on Stremio": the first COUNT movies of Stremio's own Cinemeta
 * "Popular" catalog, with their posters cached on the TV. The catalog is
 * checked at most every REFRESH_MS, and only when the home screen asks (no
 * background work); only new posters are downloaded. Choosing a movie opens
 * its page in Stremio.
 */
final class Movies {
    static final String STREMIO = "com.stremio.one";
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final int COUNT = 20;
    private static final String FEED = "https://v3-cinemeta.strem.io/catalog/movie/top.json";

    static final class Movie {
        final String id, name, year;
        final File poster;

        Movie(String id, String name, String year, File poster) {
            this.id = id;
            this.name = name;
            this.year = year;
            this.poster = poster;
        }

        Intent intent() {
            return new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/movie/" + id))
                    .setPackage(STREMIO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    interface Callback {
        void onUpdated();
    }

    /** The cached list (posters may be missing if they failed to download). */
    static List<Movie> cached(Context c) {
        List<Movie> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("index", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.getString("id");
                File f = new File(dir(c), id + ".jpg");
                out.add(new Movie(id, o.optString("name"), o.optString("year"), f.isFile() ? f : null));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void refreshIfStale(Context c, Executor io, Callback cb) {
        if (System.currentTimeMillis() - prefs(c).getLong("checked", 0) < REFRESH_MS) return;
        Context app = c.getApplicationContext();
        io.execute(() -> {
            if (refresh(app)) cb.onUpdated();
        });
    }

    private static boolean refresh(Context c) {
        try {
            JSONArray metas = new JSONObject(new String(get(FEED), "UTF-8")).getJSONArray("metas");
            File dir = dir(c);
            JSONArray index = new JSONArray();
            Set<String> keep = new HashSet<>();
            for (int i = 0; i < metas.length() && index.length() < COUNT; i++) {
                JSONObject m = metas.getJSONObject(i);
                String id = m.optString("id");
                if (!id.matches("tt\\d+")) continue;
                String name = id + ".jpg";
                File f = new File(dir, name);
                String url = m.optString("poster");
                if (!f.isFile() && !url.isEmpty()) {
                    try {
                        byte[] jpg = get(url);
                        File tmp = new File(dir, name + ".part");
                        try (OutputStream o = new FileOutputStream(tmp)) {
                            o.write(jpg);
                        }
                        tmp.renameTo(f);
                    } catch (Exception ignored) {
                        // shown as a title card; retried at the next refresh
                    }
                }
                keep.add(name);
                index.put(new JSONObject().put("id", id).put("name", m.optString("name"))
                        .put("year", m.optString("releaseInfo")));
            }
            if (index.length() == 0) return false;
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
            prefs(c).edit().putString("index", index.toString())
                    .putLong("checked", System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;   // offline: keep showing the cached list
        }
    }

    private static byte[] get(String u) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            if (con.getResponseCode() != 200) throw new IllegalStateException("HTTP " + con.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    /** Decodes a poster scaled to the given width, or null. */
    static Bitmap decode(File f, int width) {
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (o.outWidth > width) {
            o.inScaled = true;
            o.inDensity = o.outWidth;
            o.inTargetDensity = width;
        }
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "posters");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("movies", Context.MODE_PRIVATE);
    }
}
