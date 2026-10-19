package com.cleanhome.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Current temperature for one place from Open-Meteo (free, no key). Fetched
 * only when the home screen asks and the cached reading is older than
 * REFRESH_MS; there is no background polling. The last reading is cached so
 * it shows instantly, and hidden once it is older than STALE_MS.
 */
final class Weather {
    static final long REFRESH_MS = 30 * 60 * 1000L;
    private static final long STALE_MS = 6 * 60 * 60 * 1000L;

    final int tempC;
    final int code;     // WMO weather code
    final boolean day;
    final long time;

    private Weather(int tempC, int code, boolean day, long time) {
        this.tempC = tempC;
        this.code = code;
        this.day = day;
        this.time = time;
    }

    interface Callback {
        void onWeather(Weather w);
    }

    /** Last cached reading, or null if there is none or it is too old to show. */
    static Weather cached(Context c) {
        SharedPreferences p = prefs(c);
        long t = p.getLong("time", 0);
        if (t == 0 || System.currentTimeMillis() - t > STALE_MS) return null;
        return new Weather(p.getInt("temp", 0), p.getInt("code", 0), p.getBoolean("day", true), t);
    }

    /** Fetches a new reading on {@code io} if the cache is older than REFRESH_MS. */
    static void refreshIfStale(Context c, Executor io, Callback cb) {
        long t = prefs(c).getLong("time", 0);
        if (System.currentTimeMillis() - t < REFRESH_MS) return;
        Context app = c.getApplicationContext();
        io.execute(() -> {
            Weather w = fetch(app);
            if (w != null) cb.onWeather(w);
        });
    }

    private static Weather fetch(Context c) {
        String lat = c.getString(R.string.weather_latitude);
        String lon = c.getString(R.string.weather_longitude);
        HttpURLConnection con = null;
        try {
            URL url = new URL(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon));
            con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            if (con.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
            Weather w = new Weather((int) Math.round(cur.getDouble("temperature_2m")),
                    cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, System.currentTimeMillis());
            prefs(c).edit().putInt("temp", w.tempC).putInt("code", w.code)
                    .putBoolean("day", w.day).putLong("time", w.time).apply();
            return w;
        } catch (Exception e) {
            return null;   // offline or service down: keep showing the cached reading
        } finally {
            if (con != null) con.disconnect();
        }
    }

    int icon() {
        if (code == 0) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code <= 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_moon;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if (code >= 95) return R.drawable.ic_wx_storm;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
