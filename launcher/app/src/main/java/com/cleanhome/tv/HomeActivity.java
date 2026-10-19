package com.cleanhome.tv;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen. Focus is handled entirely here (no Android focus
 * search) so D-pad movement is deterministic:
 *   top bar  : Left/Right between pills, ends stop; Down -> the Now Playing
 *              card below them while something plays, else the dock;
 *              from the card Up -> back to that pill, Down -> dock
 *   dock     : Left/Right between the four apps; Up -> top bar; Down -> movies
 *   movies   : "Popular on Stremio" posters; Left/Right scroll the row;
 *              Up -> dock; Down -> the grid tile below
 *   grid     : 4 columns; Up/Down move exactly one row; Up from row 0 -> movies
 *   (with Stremio missing or no movies cached, dock and grid link directly)
 *   menu     : the pill dropdowns; Up/Down, Enter chooses, Back/Left/Right close
 *   gallery  : the Wallpaper pill's picker; Left/Right preview wallpapers
 *              full screen, OK keeps one, Back restores the previous one
 *   player   : the Now Playing pill's dropdown; Left/Right between
 *              prev/play/next, Up/Down/Back close it back to the pill
 */
public class HomeActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, PLAYER = 4, MOVIES = 5, GALLERY = 6;
    // Now Playing is the card below the pills: Down from a pill, not part of Left/Right.
    private static final int PILL_NOW = 0, PILL_INPUTS = 1, PILL_WIFI = 2, PILL_WALLPAPER = 3, PILL_SETTINGS = 4;
    // Your own photos are dimmed about 45%; the bundled art is made dark already.
    private static final int WALL_DIM = Color.argb(115, 0, 0, 0), ART_DIM = Color.argb(40, 0, 0, 0);
    private static final String PHOTO = "photo", BLACK = "black";
    private static final int COLS = 4;
    private static final String TVPLAYER = "com.xiaomi.mitv.tvplayer";
    private static final float TILE_SCALE = 1.08f, PILL_SCALE = 1.06f;
    // Movies row: fixed height, and how far its posters sit below the row's top.
    private static final int MOVIES_ROW_DP = 290, MOVIES_POSTER_TOP_DP = 44, POSTER_WIDTH_DP = 120;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Wallpaper decoding has its own thread, so gallery previews never wait
    // behind a network fetch on io.
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Interpolator scrollInterp = new DecelerateInterpolator(2f);

    private RecyclerView list;
    private GlassHighlight highlight, menuHighlight;
    private LinearLayout inputsMenu;
    private View root;
    private ImageView wallpaper, wallpaperNext;
    private String shownWallpaper;
    private volatile int wallpaperToken;
    private Map<String, String> art;   // bundled wallpapers: key -> name, in gallery order

    private View gallery;
    private ViewGroup galleryStrip;
    private TextView galleryName;
    private final List<String> galleryKeys = new ArrayList<>();
    private int galleryIdx, galleryGen;
    private String galleryBefore;
    // Outfit at each weight used, built once: rebuilding a variable font on every bind cost frames.
    private final android.util.SparseArray<Typeface> outfit = new android.util.SparseArray<>();
    private SharedPreferences prefs;
    private final Adapter adapter = new Adapter();

    private List<AppEntry> dock = Collections.emptyList();
    private List<AppEntry> grid = Collections.emptyList();
    private List<Movies.Movie> movies = Collections.emptyList();
    private Map<String, Bitmap> posters = Collections.emptyMap();

    // Header views (rebound whenever the header item is bound).
    private TextView greeting, clock, date, hero, wifiName, weatherTemp;
    private View weatherRow;
    private ImageView weatherIcon;
    private View glow;
    private GradientDrawable glowDrawable;
    private int glowColor = Color.TRANSPARENT;
    private ValueAnimator glowAnim;
    private View[] pills = new View[0];
    private TextView nowPillText, nowPillSub, npTitle, npSubtitle;
    private ImageView nowPillIcon, nowPillArt;
    private FrostedPanel dockPanel, nowCard;
    private ImageView npArt, npPlay;
    private View[] playerButtons = new View[0];
    private int playerIdx = 1, menuAnchor = PILL_INPUTS, pillAboveNow = PILL_SETTINGS;
    private NowPlaying nowPlaying;
    private NowPlaying.State npState;
    private View[] dockCards = new View[0];
    private View[] dockLabels = new View[0];

    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<View> menuRows = new ArrayList<>();

    private int section = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, menuIdx = 0, movieIdx = 0;
    private View focusedCard, focusedLabel;
    private String wifiLabel = "Wi-Fi";
    private boolean visible;
    private int lastKey;
    private long lastKeyTime;

    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback netCallback;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_home);
        // The wallpaper is opaque, so the window's own black fill is only
        // overdraw; the root view stays black until a wallpaper has faded in.
        getWindow().setBackgroundDrawable(null);
        root = findViewById(R.id.root);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        list = findViewById(R.id.list);
        highlight = findViewById(R.id.highlight);
        menuHighlight = findViewById(R.id.menu_highlight);
        inputsMenu = findViewById(R.id.inputs_menu);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperNext = findViewById(R.id.wallpaper_next);
        gallery = findViewById(R.id.gallery);
        galleryStrip = findViewById(R.id.gallery_strip);
        galleryName = findViewById(R.id.gallery_name);
        font(findViewById(R.id.gallery_title), 400);
        font(galleryName, 300);
        font(findViewById(R.id.gallery_hint), 300);
        ((HorizontalScrollView) findViewById(R.id.gallery_scroll))
                .setOnScrollChangeListener((v, x, y, ox, oy) -> highlight.poke());

        GridLayoutManager glm = new GridLayoutManager(this, COLS);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < gridStart() ? COLS : 1;
            }
        });
        list.setLayoutManager(glm);
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        // Keep every row bound while it is off screen. The default (2 views)
        // made scrolling back up rebuild the dock band or the poster row
        // mid-animation, which dropped frames on this TV.
        list.setItemViewCacheSize(64);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                highlight.poke();
            }
        });
        // Let the last grid row scroll up to the same resting height as the others.
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int pad = Math.max(0, (b - t) - restingY() - rowHeight());
            if (list.getPaddingBottom() != pad) {
                list.setPadding(list.getPaddingLeft(), 0, list.getPaddingRight(), pad);
            }
        });

        section = prefs.getInt("section", DOCK);
        topIdx = prefs.getInt("top", 0);
        dockIdx = prefs.getInt("dock", 0);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf, Context.RECEIVER_EXPORTED);

        watchNetwork();
        loadApps();
        loadMovies();
        nowPlaying = new NowPlaying(this, this::showNowPlaying);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServiceGuard.ensureEnabled(this);
        showWeather(Weather.cached(this));
        Weather.refreshIfStale(this, io, w -> runOnUiThread(() -> showWeather(w)));
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf, Context.RECEIVER_EXPORTED);
        updateClock();
        loadWallpaper();
        Movies.refreshIfStale(this, io, this::loadMovies);
        nowPlaying.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeReceiver);
        if (section == PLAYER) closeNowPlaying(false);
        nowPlaying.stop();
        if (section == MENU) closeMenu();
        if (section == GALLERY) closeGallery(false, false);
        prefs.edit()
                .putInt("section", section)
                .putInt("top", topIdx)
                .putInt("dock", dockIdx)
                .putString("grid", gridIdx < grid.size() ? grid.get(gridIdx).pkg() : null)
                .putString("movie", movieIdx < movies.size() ? movies.get(movieIdx).id : null)
                .apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: go back to the dock. Coming back from
        // an app keeps the remembered tile. Android pauses the activity before
        // delivering a new intent, so "still visible" is the signal that we were
        // already on screen.
        if (visible) {
            if (section == MENU) closeMenu();
            if (section == GALLERY) closeGallery(false, false);
            if (section != DOCK) {
                section = DOCK;
                applyFocus(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        if (netCallback != null) connectivity.unregisterNetworkCallback(netCallback);
        io.shutdownNow();
        decoder.shutdownNow();
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
            loadMovies();
        }
    };

    // ---------------------------------------------------------------- data

    private void loadApps() {
        io.execute(() -> {
            List<AppEntry> d = AppEntry.loadDock(this);
            List<AppEntry> g = AppEntry.loadGrid(this, d);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                String keepPkg = gridIdx < grid.size() ? grid.get(gridIdx).pkg()
                        : prefs.getString("grid", null);
                dock = d;
                grid = g;
                gridIdx = 0;
                for (int i = 0; i < grid.size(); i++) {
                    if (grid.get(i).pkg().equals(keepPkg)) gridIdx = i;
                }
                dockIdx = clamp(dockIdx, 0, dock.size() - 1);
                if (section == GRID && grid.isEmpty()) section = DOCK;
                if (section == MENU) section = TOP;
                adapter.notifyDataSetChanged();
                list.post(() -> applyFocus(false));
            });
        });
    }

    /** Reads the cached "Popular on Stremio" list and decodes its posters. */
    private void loadMovies() {
        io.execute(() -> {
            boolean installed;
            try {
                getPackageManager().getPackageInfo(Movies.STREMIO, 0);
                installed = true;
            } catch (Exception e) {
                installed = false;
            }
            List<Movies.Movie> m = installed ? Movies.cached(this) : Collections.emptyList();
            Map<String, Bitmap> bmps = new HashMap<>();
            for (Movies.Movie x : m) {
                Bitmap b = Movies.decode(x.poster, dp(POSTER_WIDTH_DP));
                if (b != null) bmps.put(x.id, b);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                String keepId = movieIdx < movies.size() ? movies.get(movieIdx).id
                        : prefs.getString("movie", null);
                movies = m;
                posters = bmps;
                movieIdx = 0;
                for (int i = 0; i < movies.size(); i++) {
                    if (movies.get(i).id.equals(keepId)) movieIdx = i;
                }
                if (section == MOVIES && movies.isEmpty()) section = DOCK;
                adapter.notifyDataSetChanged();
                list.post(() -> applyFocus(false));
            });
        });
    }

    /** Adapter position of the first app tile: after the header and the movies row. */
    private int gridStart() {
        return movies.isEmpty() ? 1 : 2;
    }

    private int moviesHeight() {
        return movies.isEmpty() ? 0 : dp(MOVIES_ROW_DP);
    }

    /** Scroll offset of a grid row's top. */
    private int gridTop(int row) {
        return list.getHeight() + moviesHeight() + row * rowHeight();
    }

    // ---------------------------------------------------------------- wallpaper

    /** A wallpaper.jpg/png/webp pushed to the app's files folder, or null. */
    private File customPhoto() {
        File dir = getExternalFilesDir(null);
        File f = null;
        for (String name : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File c = dir == null ? null : new File(dir, name);
            if (c != null && c.isFile()) f = c;
        }
        return f;
    }

    /** The bundled pack (assets/wallpapers/index.txt, written by tools/make-wallpapers.py). */
    private Map<String, String> art() {
        if (art != null) return art;
        art = new java.util.LinkedHashMap<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(getAssets().open("wallpapers/index.txt")))) {
            for (String line; (line = r.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq > 0) art.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (java.io.IOException ignored) {
        }
        return art;
    }

    private String wallpaperLabel(String key) {
        if (PHOTO.equals(key)) return "Your photo";
        if (BLACK.equals(key)) return "Black";
        String name = art().get(key);
        return name == null ? key : name;
    }

    /**
     * The chosen wallpaper. Until one is picked in the gallery: a pushed
     * photo if there is one, otherwise the first of the bundled pack.
     */
    private String chosenWallpaper() {
        String k = prefs.getString("wallpaper", null);
        boolean havePhoto = customPhoto() != null;
        if (BLACK.equals(k) || (PHOTO.equals(k) && havePhoto) || (k != null && art().containsKey(k))) return k;
        if (havePhoto) return PHOTO;
        return art().isEmpty() ? BLACK : art().keySet().iterator().next();
    }

    /** Called when Home opens: shows the chosen wallpaper if it isn't already. */
    private void loadWallpaper() {
        if (section == GALLERY) return;
        showWallpaper(chosenWallpaper(), false);
    }

    /** Decodes and shows a wallpaper off the UI thread; only the latest request wins. */
    private void showWallpaper(String key, boolean crossfade) {
        File photo = PHOTO.equals(key) ? customPhoto() : null;
        String id = photo == null ? key : key + ":" + photo.lastModified() + ":" + photo.length();
        if (id.equals(shownWallpaper)) return;
        shownWallpaper = id;
        final int token = ++wallpaperToken;
        if (BLACK.equals(key) || (PHOTO.equals(key) && photo == null)) {
            showBitmap(null, crossfade);
            return;
        }
        decoder.execute(() -> {
            if (token != wallpaperToken) return;   // skipped past while browsing
            Bitmap bmp = photo != null ? decodeWallpaper(photo) : decodeArt(key);
            runOnUiThread(() -> {
                if (!isDestroyed() && token == wallpaperToken) showBitmap(bmp, crossfade);
            });
        });
    }

    private Bitmap decodeArt(String key) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream in = getAssets().open("wallpapers/" + key + ".jpg")) {
            return bakeWallpaper(BitmapFactory.decodeStream(in, null, o), ART_DIM);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private Bitmap decodeWallpaper(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int target = getResources().getDisplayMetrics().widthPixels;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return bakeWallpaper(BitmapFactory.decodeFile(f.getPath(), o), WALL_DIM);
    }

    /**
     * Returns the picture cropped to the screen with the dim and the two
     * scrims painted in. Drawing those as separate full-screen layers
     * every frame was most of the GPU's work while scrolling (Mali-G52 at
     * 1080p), so the background is now a single opaque bitmap.
     */
    private Bitmap bakeWallpaper(Bitmap src, int dim) {
        if (src == null) return null;
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        c.drawColor(dim);
        Drawable top = getDrawable(R.drawable.scrim_top).mutate();
        top.setDither(true);
        top.setBounds(0, 0, w, h);
        top.draw(c);
        Drawable bottom = getDrawable(R.drawable.scrim_bottom).mutate();
        bottom.setDither(true);
        bottom.setBounds(0, h - dp(260), w, h);
        bottom.draw(c);
        return out;
    }

    /** Shows a (baked) wallpaper, or black for null; crossfade blends from the current one. */
    private void showBitmap(Bitmap bmp, boolean crossfade) {
        wallpaperNext.animate().cancel();
        wallpaperNext.setVisibility(View.GONE);
        wallpaperNext.setImageDrawable(null);
        if (bmp == null) {
            root.setBackgroundColor(Color.BLACK);
            wallpaper.animate().cancel();
            if (crossfade && wallpaper.getVisibility() == View.VISIBLE) {
                wallpaper.animate().alpha(0f).setDuration(350).withEndAction(() -> {
                    wallpaper.setVisibility(View.GONE);
                    wallpaper.setImageDrawable(null);
                }).start();
            } else {
                wallpaper.setImageDrawable(null);
                wallpaper.setVisibility(View.GONE);
            }
            return;
        }
        if (!crossfade || wallpaper.getVisibility() != View.VISIBLE) {
            root.setBackgroundColor(Color.BLACK);
            wallpaper.animate().cancel();
            wallpaper.setImageBitmap(bmp);
            wallpaper.setVisibility(View.VISIBLE);
            wallpaper.setAlpha(0f);
            wallpaper.animate().alpha(1f).setDuration(crossfade ? 350 : 300)
                    .withEndAction(() -> root.setBackground(null)).start();
            return;
        }
        // Fade the new picture in on top, then swap it into the base layer.
        wallpaper.animate().cancel();   // e.g. a fade to Black still running
        wallpaperNext.setImageBitmap(bmp);
        wallpaperNext.setAlpha(0f);
        wallpaperNext.setVisibility(View.VISIBLE);
        wallpaperNext.animate().alpha(1f).setDuration(350).withEndAction(() -> {
            wallpaper.setImageBitmap(bmp);
            wallpaper.setAlpha(1f);
            root.setBackground(null);
            wallpaperNext.setVisibility(View.GONE);
            wallpaperNext.setImageDrawable(null);
        }).start();
    }

    // ---------------------------------------------------------------- gallery

    private void openGallery() {
        galleryKeys.clear();
        File photo = customPhoto();
        if (photo != null) galleryKeys.add(PHOTO);
        galleryKeys.addAll(art().keySet());
        galleryKeys.add(BLACK);
        galleryBefore = chosenWallpaper();
        galleryIdx = Math.max(0, galleryKeys.indexOf(galleryBefore));

        galleryStrip.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (String k : galleryKeys) {
            View t = inf.inflate(R.layout.item_wallpaper, galleryStrip, false);
            TextView name = t.findViewById(R.id.thumb_name);
            name.setText(wallpaperLabel(k));
            font(name, 400);
            name.setVisibility(BLACK.equals(k) ? View.VISIBLE : View.GONE);
            t.findViewById(R.id.current).setVisibility(k.equals(galleryBefore) ? View.VISIBLE : View.GONE);
            galleryStrip.addView(t);
        }
        // Thumbnails load after the gallery is up.
        final int gen = ++galleryGen;
        final List<String> keys = new ArrayList<>(galleryKeys);
        decoder.execute(() -> {
            for (int i = 0; i < keys.size() && gen == galleryGen; i++) {
                Bitmap b = thumbnail(keys.get(i), photo);
                if (b == null) continue;
                final int at = i;
                runOnUiThread(() -> {
                    if (gen != galleryGen || at >= galleryStrip.getChildCount()) return;
                    ((ImageView) galleryStrip.getChildAt(at).findViewById(R.id.thumb)).setImageBitmap(b);
                });
            }
        });

        galleryName.setText(wallpaperLabel(galleryKeys.get(galleryIdx)));
        list.animate().alpha(0f).setDuration(200).start();
        gallery.animate().cancel();
        gallery.setAlpha(0f);
        gallery.setVisibility(View.VISIBLE);
        gallery.animate().alpha(1f).setDuration(200).start();
        section = GALLERY;
        galleryStrip.post(() -> applyFocus(false));
    }

    private Bitmap thumbnail(String key, File photo) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        if (PHOTO.equals(key)) {
            o.inSampleSize = 4;
            return photo == null ? null : BitmapFactory.decodeFile(photo.getPath(), o);
        }
        if (BLACK.equals(key)) return null;
        try (java.io.InputStream in = getAssets().open("wallpapers/thumbs/" + key + ".jpg")) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private View galleryCard(int i) {
        return i < galleryStrip.getChildCount() ? galleryStrip.getChildAt(i).findViewById(R.id.card) : null;
    }

    /** Focus moved in the gallery: preview that wallpaper full screen. */
    private void browseGallery() {
        String k = galleryKeys.get(galleryIdx);
        galleryName.setText(wallpaperLabel(k));
        showWallpaper(k, true);
    }

    /** keep: save the focused wallpaper; otherwise put the previous one back. */
    private void closeGallery(boolean keep, boolean animate) {
        galleryGen++;
        if (keep) {
            prefs.edit().putString("wallpaper", galleryKeys.get(galleryIdx)).apply();
        } else if (galleryBefore != null) {
            showWallpaper(galleryBefore, animate);
        }
        gallery.animate().cancel();
        list.animate().cancel();
        if (animate) {
            gallery.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                gallery.setVisibility(View.GONE);
                galleryStrip.removeAllViews();
            }).start();
            list.animate().alpha(1f).setDuration(200).start();
        } else {
            gallery.setVisibility(View.GONE);
            galleryStrip.removeAllViews();
            list.setAlpha(1f);
        }
        section = TOP;
        topIdx = PILL_WALLPAPER;
        applyFocus(animate);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean ours = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || isSelect(k) || k == KeyEvent.KEYCODE_BACK;
        if (!ours) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        // This TV's Bluetooth remote often delivers the key release late, so
        // Android auto-repeats the key and one press moved several rows (even
        // from the grid up to the top bar). One press = one step: ignore D-pad
        // auto-repeats, and identical key-downs within 90 ms.
        long now = android.os.SystemClock.uptimeMillis();
        boolean dpad = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if ((dpad && e.getRepeatCount() > 0) || (k == lastKey && now - lastKeyTime < 90)) {
            android.util.Log.d("CleanHomeNav", "ignored " + KeyEvent.keyCodeToString(k)
                    + " repeat=" + e.getRepeatCount() + " +" + (now - lastKeyTime) + "ms");
            return true;
        }
        lastKey = k;
        lastKeyTime = now;
        if (isSelect(k)) {
            if (e.getRepeatCount() == 0) select();
        } else if (k == KeyEvent.KEYCODE_BACK) {
            back();
        } else {
            move(k);
        }
        return true;
    }

    private static boolean isSelect(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    private void move(int k) {
        int before = section * 1000 + currentIndex();
        switch (section) {
            case TOP:
                if (topIdx == PILL_NOW) {
                    // The card: Up back to the pill we came from, Down to the dock.
                    if (k == KeyEvent.KEYCODE_DPAD_UP) topIdx = pillAboveNow;
                    else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !dock.isEmpty()) section = DOCK;
                } else if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    topIdx = nextVisiblePill(topIdx, -1);
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    topIdx = nextVisiblePill(topIdx, +1);
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (pills[PILL_NOW].getVisibility() == View.VISIBLE) {
                        pillAboveNow = topIdx;
                        topIdx = PILL_NOW;
                    } else if (!dock.isEmpty()) {
                        section = DOCK;
                    }
                }
                break;
            case PLAYER:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) playerIdx = Math.max(0, playerIdx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) playerIdx = Math.min(playerButtons.length - 1, playerIdx + 1);
                else {
                    closeNowPlaying(true);
                    return;
                }
                break;
            case DOCK:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) dockIdx = Math.max(0, dockIdx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) dockIdx = Math.min(dock.size() - 1, dockIdx + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_UP) section = TOP;
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !movies.isEmpty()) section = MOVIES;
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !grid.isEmpty()) {
                    section = GRID;
                    gridIdx = Math.min(dockIdx, grid.size() - 1);
                }
                break;
            case MOVIES:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) movieIdx = Math.max(0, movieIdx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) movieIdx = Math.min(movies.size() - 1, movieIdx + 1);
                else if (k == KeyEvent.KEYCODE_DPAD_UP && !dock.isEmpty()) section = DOCK;
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !grid.isEmpty()) {
                    section = GRID;
                    gridIdx = Math.min(columnUnder(movieView(movieIdx, R.id.card)), grid.size() - 1);
                }
                break;
            case GRID: {
                int col = gridIdx % COLS;
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (col > 0) gridIdx--;
                } else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (col < COLS - 1 && gridIdx + 1 < grid.size()) gridIdx++;
                } else if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    if (gridIdx < COLS && !movies.isEmpty()) {
                        section = MOVIES;
                    } else if (gridIdx < COLS) {
                        section = DOCK;
                        dockIdx = Math.min(col, dock.size() - 1);
                    } else {
                        gridIdx -= COLS;
                    }
                } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int row = gridIdx / COLS;
                    if ((row + 1) * COLS < grid.size()) {
                        gridIdx = Math.min(gridIdx + COLS, grid.size() - 1);
                    }
                }
                break;
            }
            case GALLERY:
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) galleryIdx = Math.max(0, galleryIdx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) galleryIdx = Math.min(galleryKeys.size() - 1, galleryIdx + 1);
                break;
            case MENU:
                if (k == KeyEvent.KEYCODE_DPAD_UP) menuIdx = Math.max(0, menuIdx - 1);
                else if (k == KeyEvent.KEYCODE_DPAD_DOWN) menuIdx = Math.min(menuRows.size() - 1, menuIdx + 1);
                else closeMenu();
                break;
        }
        int after = section * 1000 + currentIndex();
        android.util.Log.d("CleanHomeNav", "key=" + KeyEvent.keyCodeToString(k) + " from=" + before
                + " to=" + after + " scroll=" + currentScroll() + " rowH=" + rowHeight()
                + " listH=" + list.getHeight() + " scrolling=" + list.getScrollState());
        if (after != before) {
            if (section == GALLERY) browseGallery();
            applyFocus(true);
        }
    }

    private int currentIndex() {
        switch (section) {
            case TOP: return topIdx;
            case DOCK: return dockIdx;
            case GRID: return gridIdx;
            case MOVIES: return movieIdx;
            case GALLERY: return galleryIdx;
            case PLAYER: return playerIdx;
            default: return menuIdx;
        }
    }

    private void select() {
        switch (section) {
            case TOP:
                if (topIdx == PILL_INPUTS) openInputsMenu();
                else if (topIdx == PILL_WIFI) openSettings(Settings.ACTION_WIFI_SETTINGS);
                else if (topIdx == PILL_NOW) openNowPlaying();
                else if (topIdx == PILL_WALLPAPER) openGallery();
                else openSettings(Settings.ACTION_SETTINGS);
                break;
            case PLAYER:
                if (playerIdx == 0) nowPlaying.previous();
                else if (playerIdx == 1) nowPlaying.togglePlay();
                else nowPlaying.next();
                break;
            case DOCK:
                if (dockIdx < dock.size()) launch(dock.get(dockIdx));
                break;
            case GRID:
                if (gridIdx < grid.size()) launch(grid.get(gridIdx));
                break;
            case MOVIES:
                if (movieIdx < movies.size()) launchMovie(movies.get(movieIdx));
                break;
            case GALLERY:
                closeGallery(true, true);
                break;
            case MENU:
                Runnable r = menuIdx < menuActions.size() ? menuActions.get(menuIdx) : null;
                closeMenu();
                if (r != null) r.run();
                break;
        }
    }

    private void back() {
        if (section == PLAYER) {
            closeNowPlaying(true);
        } else if (section == MENU) {
            closeMenu();
        } else if (section == GALLERY) {
            closeGallery(false, true);
        } else if (section != DOCK && !dock.isEmpty()) {
            section = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- focus

    /** Moves the highlight, scales the focused tile, scrolls and updates the hero. */
    private void applyFocus(boolean animate) {
        if (section == MENU) {
            final int i = menuIdx;
            menuHighlight.setTarget(() -> i < menuRows.size() ? menuRows.get(i) : null, dp(14), animate);
            return;
        }
        menuHighlight.hide();

        View card = null, label = null;
        float scale = TILE_SCALE;
        GlassHighlight.Target target;
        float radius = dp(14) * TILE_SCALE;
        switch (section) {
            case TOP: {
                if (topIdx < pills.length && pills[topIdx].getVisibility() != View.VISIBLE) topIdx = pillAboveNow;
                final int i = topIdx;
                card = i < pills.length ? pills[i] : null;
                scale = PILL_SCALE;
                radius = (i == PILL_NOW ? dp(16) : dp(20)) * PILL_SCALE;
                target = () -> i < pills.length ? pills[i] : null;
                break;
            }
            case PLAYER: {
                final int i = playerIdx;
                card = i < playerButtons.length ? playerButtons[i] : null;
                scale = PILL_SCALE;
                radius = dp(22) * PILL_SCALE;
                target = () -> i < playerButtons.length ? playerButtons[i] : null;
                break;
            }
            case DOCK: {
                final int i = dockIdx;
                if (i < dockCards.length) {
                    card = dockCards[i];
                    label = dockLabels[i];
                }
                target = () -> i < dockCards.length ? dockCards[i] : null;
                break;
            }
            case MOVIES: {
                final int i = movieIdx;
                card = movieView(i, R.id.card);
                label = movieView(i, R.id.label);
                target = () -> movieView(i, R.id.card);
                break;
            }
            case GALLERY: {
                final int i = galleryIdx;
                card = galleryCard(i);
                target = () -> galleryCard(i);
                break;
            }
            default: {
                final int pos = gridIdx + gridStart();
                RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
                if (h != null) {
                    card = h.itemView.findViewById(R.id.card);
                    label = h.itemView.findViewById(R.id.label);
                }
                target = () -> {
                    RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
                    return vh == null ? null : vh.itemView.findViewById(R.id.card);
                };
            }
        }

        if (card != focusedCard) {
            setFocusedVisual(focusedCard, focusedLabel, false, animate, 1f);
        }
        setFocusedVisual(card, label, true, animate, scale);
        focusedCard = card;
        focusedLabel = label;

        int y = 0;
        if (section == GRID) y = gridTop(gridIdx / COLS) - restingY();
        else if (section == MOVIES) y = list.getHeight() + dp(MOVIES_POSTER_TOP_DP) - restingY();
        scrollTo(y, animate);
        if (section == MOVIES) scrollStrip(movieView(movieIdx, R.id.card), animate);
        if (section == GALLERY) scrollStrip(galleryCard(galleryIdx), animate);
        updateHero(animate);
        highlight.setTarget(target, radius, animate);
    }

    private void setFocusedVisual(View card, View label, boolean on, boolean animate, float scale) {
        if (card != null) {
            float s = on ? scale : 1f;
            if (animate) card.animate().scaleX(s).scaleY(s).setDuration(200).start();
            else { card.animate().cancel(); card.setScaleX(s); card.setScaleY(s); }
        }
        if (label != null) {
            float a = on ? 1f : 0f;
            if (animate) label.animate().alpha(a).setDuration(200).start();
            else { label.animate().cancel(); label.setAlpha(a); }
        }
    }

    private void scrollTo(int y, boolean animate) {
        int dy = Math.max(0, y) - currentScroll();
        if (dy == 0) return;
        if (animate) list.smoothScrollBy(0, dy, scrollInterp, 420);
        else list.scrollBy(0, dy);
    }

    /**
     * The list's real scroll offset, read from whichever item is on screen (the
     * header is one screen tall, every grid row is rowHeight()). Reading it
     * fresh each time avoids drift when the list re-lays out mid-scroll (app
     * list or Now Playing changes), which made Down jump back up.
     */
    private int currentScroll() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            int pos = list.getChildAdapterPosition(c);
            if (pos == RecyclerView.NO_POSITION) continue;
            if (pos == 0) return -c.getTop();
            if (pos < gridStart()) return list.getHeight() - c.getTop();
            return gridTop((pos - gridStart()) / COLS) - c.getTop();
        }
        return 0;
    }

    /** A view inside poster i of the movies row, or null if the row isn't laid out. */
    private View movieView(int i, int id) {
        if (movies.isEmpty()) return null;
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(1);
        if (h == null) return null;
        ViewGroup strip = h.itemView.findViewById(R.id.movies_strip);
        return strip == null || i >= strip.getChildCount() ? null : strip.getChildAt(i).findViewById(id);
    }

    /** Scrolls a horizontal strip (movies, gallery) so the focused card, and one beyond it, are on screen. */
    private void scrollStrip(View card, boolean animate) {
        if (card == null) return;
        View poster = (View) card.getParent();
        HorizontalScrollView sv = (HorizontalScrollView) poster.getParent().getParent();
        int edge = dp(48), w = poster.getWidth();
        int x = sv.getScrollX();
        if (poster.getRight() + w - x > sv.getWidth() - edge) x = poster.getRight() + w - (sv.getWidth() - edge);
        if (poster.getLeft() - w - x < edge) x = poster.getLeft() - w - edge;
        View strip = (View) poster.getParent();
        x = clamp(x, 0, strip.getWidth() - sv.getWidth());
        if (x == sv.getScrollX()) return;
        if (animate) sv.smoothScrollTo(x, 0);
        else sv.scrollTo(x, 0);
    }

    /** The app grid column below a view, from its centre on screen. */
    private int columnUnder(View v) {
        if (v == null) return 0;
        int[] p = new int[2], r = new int[2];
        v.getLocationInWindow(p);
        list.getLocationInWindow(r);
        float centre = p[0] - r[0] + v.getWidth() / 2f - list.getPaddingLeft();
        float colW = (list.getWidth() - list.getPaddingLeft() - list.getPaddingRight()) / (float) COLS;
        return clamp((int) (centre / colW), 0, COLS - 1);
    }

    private int restingY() {
        return dp(196);
    }

    private int rowHeight() {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (list.getChildAdapterPosition(c) >= gridStart() && c.getHeight() > 0) return c.getHeight();
        }
        return dp(156);
    }

    private void updateHero(boolean animate) {
        if (hero == null) return;
        AppEntry e = dockIdx < dock.size() ? dock.get(dockIdx) : null;
        hero.setText(e == null ? "" : e.label);
        float a = section == DOCK ? 1f : 0.35f;
        int target = e == null ? Color.TRANSPARENT : e.accent;
        if (animate) {
            hero.animate().alpha(a).setDuration(250).start();
            glow.animate().alpha(a).setDuration(350).start();
        } else {
            hero.setAlpha(a);
            glow.setAlpha(a);
        }
        if (target == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        final int from = glowColor;
        if (!animate) {
            setGlow(target);
            return;
        }
        ArgbEvaluator ev = new ArgbEvaluator();
        glowAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(450);
        glowAnim.addUpdateListener(va -> setGlow((int) ev.evaluate(va.getAnimatedFraction(), from, target)));
        glowAnim.start();
    }

    private void setGlow(int c) {
        glowColor = c;
        if (glowDrawable == null) return;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        glowDrawable.setColors(new int[]{
                Color.argb(110, r, g, b), Color.argb(40, r, g, b), Color.argb(0, r, g, b)});
    }

    // ---------------------------------------------------------------- header

    private void bindHeader(View v) {
        greeting = v.findViewById(R.id.greeting);
        clock = v.findViewById(R.id.clock);
        date = v.findViewById(R.id.date);
        hero = v.findViewById(R.id.hero);
        wifiName = v.findViewById(R.id.wifi_name);
        glow = v.findViewById(R.id.glow);
        font(greeting, 300);
        font(clock, 150);
        font(date, 300);
        weatherRow = v.findViewById(R.id.weather);
        weatherIcon = v.findViewById(R.id.weather_icon);
        weatherTemp = v.findViewById(R.id.weather_temp);
        font(weatherTemp, 300);
        showWeather(Weather.cached(this));
        font(hero, 250);
        font(v.findViewById(R.id.pill_inputs_text), 400);
        font(wifiName, 400);
        font(v.findViewById(R.id.pill_wallpaper_text), 400);
        font(v.findViewById(R.id.pill_settings_text), 400);

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(dp(380));
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        glow.setBackground(glowDrawable);
        setGlow(glowColor);

        pills = new View[]{v.findViewById(R.id.pill_now), v.findViewById(R.id.pill_inputs),
                v.findViewById(R.id.pill_wifi), v.findViewById(R.id.pill_wallpaper), v.findViewById(R.id.pill_settings)};
        nowPillText = v.findViewById(R.id.now_pill_text);
        nowPillSub = v.findViewById(R.id.now_pill_sub);
        nowPillIcon = v.findViewById(R.id.now_pill_icon);
        nowPillArt = v.findViewById(R.id.now_pill_art);
        font(nowPillText, 450);
        font(nowPillSub, 300);
        nowCard = v.findViewById(R.id.now_playing);
        nowCard.setCornerRadius(dp(24));
        npArt = v.findViewById(R.id.np_art);
        npArt.setClipToOutline(true);
        npPlay = v.findViewById(R.id.np_play);
        npTitle = v.findViewById(R.id.np_title);
        npSubtitle = v.findViewById(R.id.np_subtitle);
        font(npTitle, 450);
        font(npSubtitle, 300);
        playerButtons = new View[]{v.findViewById(R.id.np_prev), npPlay, v.findViewById(R.id.np_next)};
        showNowPlaying(npState);
        topIdx = clamp(topIdx, 0, pills.length - 1);

        dockPanel = v.findViewById(R.id.dock);
        dockPanel.setCornerRadius(dp(30));
        ViewGroup band = dockPanel;
        band.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        dockCards = new View[dock.size()];
        dockLabels = new View[dock.size()];
        for (int i = 0; i < dock.size(); i++) {
            View tile = inf.inflate(R.layout.item_tile, band, false);
            // Always size tiles for four slots, so fewer dock apps don't make
            // the tiles (and the band) taller.
            tile.setLayoutParams(new LinearLayout.LayoutParams(dockTileWidth(),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            bindTile(tile, dock.get(i), section == DOCK && i == dockIdx);
            band.addView(tile);
            dockCards[i] = tile.findViewById(R.id.card);
            dockLabels[i] = tile.findViewById(R.id.label);
        }
        for (int i = 0; i < pills.length; i++) {
            float s = section == TOP && i == topIdx ? PILL_SCALE : 1f;
            pills[i].setScaleX(s);
            pills[i].setScaleY(s);
        }
        if (section == DOCK && dockIdx < dockCards.length) {
            focusedCard = dockCards[dockIdx];
            focusedLabel = dockLabels[dockIdx];
        } else if (section == TOP) {
            focusedCard = pills[topIdx];
            focusedLabel = null;
        }
        wifiName.setText(wifiLabel);
        updateClock();
        updateHero(false);
    }

    private void bindTile(View tile, AppEntry e, boolean focused) {
        ImageView banner = tile.findViewById(R.id.banner);
        ImageView icon = tile.findViewById(R.id.icon);
        View card = tile.findViewById(R.id.card);
        TextView label = tile.findViewById(R.id.label);
        if (e.banner != null) {
            banner.setImageDrawable(e.banner);
            banner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            card.getBackground().mutate().setTintList(null);
        } else {
            banner.setVisibility(View.GONE);
            icon.setImageDrawable(e.icon);
            icon.setVisibility(View.VISIBLE);
            card.getBackground().mutate().setTint(blend(e.accent, 0xFF1E1E21, 0.22f));
        }
        label.setText(e.label);
        font(label, 400);
        float s = focused ? TILE_SCALE : 1f;
        card.animate().cancel();
        card.setScaleX(s);
        card.setScaleY(s);
        label.animate().cancel();
        label.setAlpha(focused ? 1f : 0f);
    }

    private void bindMovies(View v) {
        font(v.findViewById(R.id.movies_title), 400);
        HorizontalScrollView sv = v.findViewById(R.id.movies_scroll);
        sv.setOnScrollChangeListener((s, x, y, ox, oy) -> highlight.poke());
        ViewGroup strip = v.findViewById(R.id.movies_strip);
        // Rebuild the posters only when the list itself changed (the app list
        // reloading also rebinds every row); otherwise just refresh focus.
        if (strip.getTag() == movies) {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View card = strip.getChildAt(i).findViewById(R.id.card);
                View label = strip.getChildAt(i).findViewById(R.id.label);
                boolean focused = section == MOVIES && i == movieIdx;
                setFocusedVisual(card, label, focused, false, TILE_SCALE);
                if (focused) {
                    focusedCard = card;
                    focusedLabel = label;
                }
            }
            return;
        }
        strip.setTag(movies);
        strip.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < movies.size(); i++) {
            Movies.Movie m = movies.get(i);
            View p = inf.inflate(R.layout.item_poster, strip, false);
            Bitmap b = posters.get(m.id);
            ((ImageView) p.findViewById(R.id.poster)).setImageBitmap(b);
            TextView name = p.findViewById(R.id.poster_name);
            name.setText(m.name);
            name.setVisibility(b == null ? View.VISIBLE : View.GONE);
            font(name, 400);
            TextView label = p.findViewById(R.id.label);
            label.setText(m.name);
            font(label, 400);
            View card = p.findViewById(R.id.card);
            boolean focused = section == MOVIES && i == movieIdx;
            card.setScaleX(focused ? TILE_SCALE : 1f);
            card.setScaleY(focused ? TILE_SCALE : 1f);
            label.setAlpha(focused ? 1f : 0f);
            if (focused) {
                focusedCard = card;
                focusedLabel = label;
            }
            strip.addView(p);
        }
        sv.post(() -> scrollStrip(movieView(movieIdx, R.id.card), false));
    }

    /** Width of one dock tile: a quarter of the band's inner width. */
    private int dockTileWidth() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int inner = screen - list.getPaddingLeft() - list.getPaddingRight() - dp(28);
        return inner / 4;
    }

    private void showWeather(Weather w) {
        if (weatherRow == null) return;
        if (w == null) {
            weatherRow.setVisibility(View.GONE);
            return;
        }
        weatherIcon.setImageResource(w.icon());
        weatherTemp.setText(w.tempC + "°");
        weatherRow.setVisibility(View.VISIBLE);
    }

    /** Next pill in the given direction along the top row (the Now Playing card is below it). */
    private int nextVisiblePill(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < pills.length; i += dir) {
            if (i != PILL_NOW && pills[i].getVisibility() == View.VISIBLE) return i;
        }
        return from;
    }

    private void showNowPlaying(NowPlaying.State st) {
        npState = st;
        if (nowCard == null) return;
        View pill = pills.length > PILL_NOW ? pills[PILL_NOW] : null;
        if (st == null) {
            if (pill != null) pill.setVisibility(View.GONE);
            if (section == PLAYER) closeNowPlaying(false);
            if (section == TOP && topIdx == PILL_NOW) {
                topIdx = pillAboveNow;
                applyFocus(true);
            }
            return;
        }
        nowPillText.setText(st.title);
        nowPillSub.setText(st.subtitle == null ? "" : st.subtitle);
        nowPillSub.setVisibility(st.subtitle == null || st.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        if (st.art != null) nowPillArt.setImageBitmap(st.art);
        else nowPillArt.setImageDrawable(st.appIcon);
        nowPillIcon.setImageResource(st.playing ? R.drawable.ic_music : R.drawable.ic_pause);
        if (pill != null) pill.setVisibility(View.VISIBLE);
        npTitle.setText(st.title);
        npSubtitle.setText(st.subtitle == null ? "" : st.subtitle);
        if (st.art != null) npArt.setImageBitmap(st.art);
        else npArt.setImageDrawable(st.appIcon);
        npPlay.setImageResource(st.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (section == TOP || section == PLAYER) highlight.poke();
    }

    /** Opens the Now Playing dropdown under its pill, focus on play/pause. */
    private void openNowPlaying() {
        if (npState == null || nowCard == null) return;
        View pill = pills[PILL_NOW];
        View parent = (View) nowCard.getParent();
        int[] p = new int[2], r = new int[2];
        pill.getLocationInWindow(p);
        parent.getLocationInWindow(r);
        float cardW = nowCard.getLayoutParams().width;
        float x = p[0] - r[0] + pill.getWidth() * pill.getScaleX() / 2f - cardW / 2f;
        x = Math.max(dp(8), Math.min(x, parent.getWidth() - cardW - dp(8)));
        nowCard.setX(x);
        nowCard.setY(p[1] - r[1] + pill.getHeight() * pill.getScaleY() + dp(12));
        nowCard.setAlpha(0f);
        nowCard.setVisibility(View.VISIBLE);
        nowCard.animate().alpha(1f).setDuration(160).start();
        section = PLAYER;
        playerIdx = 1;
        nowCard.post(() -> applyFocus(true));
    }

    private void closeNowPlaying(boolean animate) {
        if (nowCard != null) {
            if (animate) {
                nowCard.animate().alpha(0f).setDuration(120)
                        .withEndAction(() -> nowCard.setVisibility(View.GONE)).start();
            } else {
                nowCard.setVisibility(View.GONE);
            }
        }
        section = TOP;
        topIdx = pills.length > PILL_NOW && pills[PILL_NOW].getVisibility() == View.VISIBLE ? PILL_NOW : pillAboveNow;
        applyFocus(animate);
    }

    private void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.US).format(now));
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
    }

    // ---------------------------------------------------------------- network

    private void watchNetwork() {
        connectivity = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities caps) {
                String label;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    label = "Ethernet";
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    WifiInfo wi = caps.getTransportInfo() instanceof WifiInfo
                            ? (WifiInfo) caps.getTransportInfo() : null;
                    String ssid = wi == null ? null : wi.getSSID();
                    if (ssid == null || ssid.isEmpty() || ssid.equals(WifiManagerCompat.UNKNOWN_SSID)) {
                        label = "Wi-Fi";
                    } else {
                        label = ssid.replaceAll("^\"|\"$", "");
                    }
                } else {
                    label = "Connected";
                }
                postWifi(label);
            }

            @Override
            public void onLost(@NonNull Network n) {
                postWifi("Offline");
            }
        };
        connectivity.registerDefaultNetworkCallback(netCallback);
    }

    private void postWifi(String label) {
        runOnUiThread(() -> {
            wifiLabel = label;
            if (wifiName != null) wifiName.setText(label);
            if (section == TOP) highlight.poke();
        });
    }

    /** Avoids a WifiManager dependency for one constant. */
    private static final class WifiManagerCompat {
        static final String UNKNOWN_SSID = "<unknown ssid>";
    }

    // ---------------------------------------------------------------- inputs

    private LayoutInflater beginMenu() {
        inputsMenu.removeAllViews();
        menuRows.clear();
        menuActions.clear();
        return LayoutInflater.from(this);
    }

    private void openInputsMenu() {
        LayoutInflater inf = beginMenu();

        TvInputManager tim = getSystemService(TvInputManager.class);
        List<TvInputInfo> hdmi = new ArrayList<>();
        TvInputInfo av = null, tuner = null;
        if (tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getParentId() != null) continue;   // CEC devices hang off an HDMI port
                switch (info.getType()) {
                    case TvInputInfo.TYPE_HDMI: hdmi.add(info); break;
                    case TvInputInfo.TYPE_COMPOSITE: if (av == null) av = info; break;
                    case TvInputInfo.TYPE_TUNER: if (tuner == null) tuner = info; break;
                    default: break;
                }
            }
        }
        // MediaTek ids end in HWn with n rising with the HDMI port number.
        hdmi.sort((a, b) -> Integer.compare(hwNumber(a.getId()), hwNumber(b.getId())));
        int hdmiCount = hdmi.isEmpty() ? 4 : hdmi.size();
        for (int i = 0; i < hdmiCount; i++) {
            TvInputInfo info = i < hdmi.size() ? hdmi.get(i) : null;
            CharSequence custom = info == null ? null : info.loadCustomLabel(this);
            String name = custom != null && custom.length() > 0 ? custom.toString() : "HDMI " + (i + 1);
            final int source = 23 + i;   // Xiaomi tvplayer: HDMI1..4 = 23..26
            addMenuRow(inf, name, () -> switchInput(info, source, false));
        }
        final TvInputInfo avInfo = av, tunerInfo = tuner;
        addMenuRow(inf, "AV", () -> switchInput(avInfo, 2, false));   // tvplayer: CVBS = 2
        addMenuRow(inf, "Live TV", () -> switchInput(tunerInfo, -1, true));
        showMenu(PILL_INPUTS);
    }

    /** Shows the dropdown under the given pill and moves focus into it. */
    private void showMenu(int anchor) {
        menuAnchor = anchor;
        View pill = anchor < pills.length ? pills[anchor] : null;
        if (pill != null) {
            int[] p = new int[2], r = new int[2];
            pill.getLocationInWindow(p);
            ((View) inputsMenu.getParent()).getLocationInWindow(r);
            float pillW = pill.getWidth() * pill.getScaleX();
            float menuW = inputsMenu.getLayoutParams().width;
            // Centre under the pill, but keep it on screen.
            float x = p[0] - r[0] + pillW / 2f - menuW / 2f;
            float maxX = ((View) inputsMenu.getParent()).getWidth() - menuW - dp(24);
            inputsMenu.setX(Math.min(x, maxX));
            inputsMenu.setY(p[1] - r[1] + pill.getHeight() * pill.getScaleY() + dp(10));
        }
        inputsMenu.setAlpha(0f);
        inputsMenu.setTranslationY(inputsMenu.getTranslationY());
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.animate().alpha(1f).setDuration(160).start();

        section = MENU;
        menuIdx = 0;
        inputsMenu.post(() -> applyFocus(false));
    }

    private void addMenuRow(LayoutInflater inf, String label, Runnable action) {
        TextView row = (TextView) inf.inflate(R.layout.item_input, inputsMenu, false);
        row.setText(label);
        font(row, 400);
        inputsMenu.addView(row);
        menuRows.add(row);
        menuActions.add(action);
    }

    private void closeMenu() {
        inputsMenu.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
        menuHighlight.hide();
        section = TOP;
        topIdx = menuAnchor;
        applyFocus(true);
    }

    /**
     * Switches source. Tries the standard TV input framework passthrough intent
     * first; this TV's own player does not handle it, so it falls back to the
     * Xiaomi tvplayer intents with the source ids found in its code.
     */
    private void switchInput(TvInputInfo info, int xiaomiSource, boolean tuner) {
        if (info != null && !tuner) {
            Intent tif = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (tif.resolveActivity(getPackageManager()) != null && start(tif)) return;
        }
        Intent mi = tuner
                ? new Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY")
                : new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").putExtra("input", xiaomiSource);
        mi.setPackage(TVPLAYER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!start(mi)) Toast.makeText(this, "Can't switch input", Toast.LENGTH_SHORT).show();
    }

    private static int hwNumber(String id) {
        int i = id.lastIndexOf("HW");
        try {
            return i < 0 ? Integer.MAX_VALUE : Integer.parseInt(id.substring(i + 2));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- launching

    private void launch(AppEntry e) {
        if (!start(e.launchIntent())) {
            Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the movie's page in Stremio, or just Stremio if it won't take the link. */
    private void launchMovie(Movies.Movie m) {
        if (start(m.intent())) return;
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(Movies.STREMIO);
        if (app == null || !start(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Toast.makeText(this, "Can't open Stremio", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String action) {
        if (start(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        if (start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return;
        start(new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("com.xiaomi.mitv.settings",
                        "com.xiaomi.mitv.settings.entry.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private boolean start(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void font(TextView tv, int weight) {
        if (tv == null) return;
        Typeface t = outfit.get(weight);
        if (t == null) {
            t = new Typeface.Builder(getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
            outfit.put(weight, t);
        }
        if (tv.getTypeface() != t) tv.setTypeface(t);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) * t + Color.red(b) * (1 - t)),
                Math.round(Color.green(a) * t + Color.green(b) * (1 - t)),
                Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    // ---------------------------------------------------------------- adapter

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : position < gridStart() ? 2 : 1;
        }

        @Override
        public int getItemCount() {
            return gridStart() + grid.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            View v;
            if (type == 0) {
                v = inf.inflate(R.layout.item_header, parent, false);
                int h = parent.getHeight() > 0 ? parent.getHeight()
                        : getResources().getDisplayMetrics().heightPixels;
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            } else if (type == 2) {
                v = inf.inflate(R.layout.item_movies, parent, false);
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(MOVIES_ROW_DP)));
            } else {
                v = inf.inflate(R.layout.item_tile, parent, false);
            }
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (position == 0) {
                bindHeader(h.itemView);
            } else if (position < gridStart()) {
                bindMovies(h.itemView);
            } else {
                int i = position - gridStart();
                boolean focused = section == GRID && gridIdx == i;
                bindTile(h.itemView, grid.get(i), focused);
                if (focused) {
                    focusedCard = h.itemView.findViewById(R.id.card);
                    focusedLabel = h.itemView.findViewById(R.id.label);
                }
            }
        }
    }
}
