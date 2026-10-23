package com.cleanhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns recognised speech into actions. Understands:
 *   "open / launch / start / go to <app>"   (or just the app's name)
 *   "play", "resume", "pause", "stop", "next", "skip", "previous"
 *   "hdmi one..four", "a v", "live tv", "home"
 *   "search <words> [on youtube|smart tube]", "youtube <words>",
 *   "play <words> on youtube|smart tube"     -> SmartTube search results
 * Returns a short line to show on screen.
 */
final class VoiceCommands {
    private static final String TAG = "CleanHomeVoice";
    private static final String TVPLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String SMARTTUBE = "org.smarttube.stable";

    // Spoken names that don't match an app's label.
    private static final String[][] ALIASES = {
            {"youtube", SMARTTUBE}, {"you tube", SMARTTUBE}, {"smart tube", SMARTTUBE},
            {"waka", "com.mm.droid.livetv.wakatv"}, {"walker", "com.mm.droid.livetv.wakatv"},
            {"wacker", "com.mm.droid.livetv.wakatv"}, {"media player", "com.xiaomi.mitv.mediaexplorer"},
            {"tick tock", "com.tiktok.tv"}, {"tik tok", "com.tiktok.tv"},
            {"settings", "com.android.tv.settings"}, {"app store", "cm.aptoidetv.pt"},
            {"aptoide", "cm.aptoidetv.pt"}, {"browser", "com.phlox.tvwebbrowser"},
    };
    private static final Pattern SEARCH = Pattern.compile(
            "^(?:search(?: for)?|find|look up)\\s+(.+?)(?:\\s+on\\s+(?:youtube|you tube|smart tube))?$");
    private static final Pattern PLAY_ON = Pattern.compile("^(?:play|watch)\\s+(.+?)\\s+on\\s+(?:youtube|you tube|smart tube)$");
    private static final Pattern YOUTUBE = Pattern.compile("^(?:youtube|you tube|smart tube)\\s+(.+)$");
    private static final Pattern OPEN = Pattern.compile("^(?:open|launch|start|go to|show|run)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$");

    private final Context ctx;

    VoiceCommands(Context ctx) {
        this.ctx = ctx;
    }

    // Fixed phrases for the command pass. Vosk only knows dictionary words, so
    // unusual names are given sound-alike spellings (see SPOKEN).
    private static final String[] FIXED = {
            "play", "play music", "resume", "continue", "pause", "pause music", "stop", "stop music",
            "next", "next song", "skip", "skip song", "previous", "previous song", "go back",
            "hdmi one", "hdmi two", "hdmi three", "hdmi four", "input one", "input two", "input three", "input four",
            "a v", "live tv", "live television", "antenna", "home", "go home",
            "search", "search for", "find", "youtube", "you tube",
    };
    // Spoken form -> package, for app names that aren't English words.
    private static final String[][] SPOKEN = {
            {"walker tv", "com.mm.droid.livetv.wakatv"}, {"walk a tv", "com.mm.droid.livetv.wakatv"},
            {"wacker tv", "com.mm.droid.livetv.wakatv"}, {"walker", "com.mm.droid.livetv.wakatv"},
            {"stream io", "com.stremio.one"}, {"stream yo", "com.stremio.one"}, {"stream video", "com.stremio.one"},
            {"spot if i", "com.spotify.tv.android"}, {"spotify", "com.spotify.tv.android"},
            {"smart tube", SMARTTUBE}, {"you tube", SMARTTUBE}, {"youtube", SMARTTUBE},
            {"tick tock", "com.tiktok.tv"}, {"air screen", "com.ionitech.airscreen"},
            {"app store", "cm.aptoidetv.pt"}, {"apt o id", "cm.aptoidetv.pt"},
            {"down loader", "com.esaba.downloader"}, {"media player", "com.xiaomi.mitv.mediaexplorer"},
            {"settings", "com.android.tv.settings"}, {"gallery", "com.mitv.gallery"},
            {"calendar", "com.xiaomi.mitv.calendar"}, {"tv bro", "com.phlox.tvwebbrowser"},
            {"browser", "com.phlox.tvwebbrowser"},
    };

    /**
     * The command pass's phrase list (Vosk grammar JSON), built from the installed
     * apps. Only phrases made entirely of words the model knows are kept: Vosk
     * drops unknown words, and the gaps that leaves crash it natively (SIGSEGV),
     * taking the launcher and its Home-button service down with it.
     */
    String grammar(java.util.function.Predicate<String> known) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String[] s : SPOKEN) names.add(s[0]);
        List<AppEntry> apps = new ArrayList<>(AppEntry.loadDock(ctx));
        apps.addAll(AppEntry.loadGrid(ctx, apps));
        for (AppEntry e : apps) {
            String n = e.label.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
            if (!n.isEmpty()) names.add(n);
        }
        org.json.JSONArray a = new org.json.JSONArray();
        for (String f : FIXED) if (allKnown(f, known)) a.put(f);
        for (String n : names) {
            if (!allKnown(n, known)) continue;
            a.put(n);
            a.put("open " + n);
            a.put("launch " + n);
            a.put("start " + n);
        }
        a.put("[unk]");
        return a.toString();
    }

    private static boolean allKnown(String phrase, java.util.function.Predicate<String> known) {
        for (String w : phrase.split(" ")) {
            if (!known.test(w)) {
                Log.i("CleanHomeVoice", "grammar: skipping \"" + phrase + "\" (\"" + w + "\" not in the model)");
                return false;
            }
        }
        return true;
    }

    /**
     * cmd: what the command pass heard (or "" / "[unk]"); free: the dictation pass.
     * Commands win; "search ..." takes its words from the dictation pass.
     */
    String run(String cmd, String free) {
        String c = cmd == null ? "" : cmd.replace("[unk]", "").trim();
        String f = free == null ? "" : free.trim();
        if (c.matches("(?:search|search for|find|youtube|you tube)") || f.matches("^(?:search|find|look up|youtube|you tube)\\b.*")) {
            return run(f);
        }
        if (!c.isEmpty() && !c.equals("search")) {
            for (String[] s : SPOKEN) {
                if (c.equals(s[0]) || c.endsWith(" " + s[0])) {
                    AppEntry e = byPackage(s[1]);
                    if (e != null) return start(e.launchIntent(), "\u201C" + c + "\u201D", "Opening " + e.label);
                }
            }
            return run(c);
        }
        return run(f);
    }

    private AppEntry byPackage(String pkg) {
        List<AppEntry> apps = new ArrayList<>(AppEntry.loadDock(ctx));
        apps.addAll(AppEntry.loadGrid(ctx, apps));
        for (AppEntry e : apps) if (e.pkg().equals(pkg)) return e;
        return null;
    }

    String run(String heard) {
        String t = normalise(heard);
        if (t.isEmpty()) return "Didn't catch that";
        Log.i(TAG, "command: " + t);
        String quoted = "“" + heard + "”";

        // Media
        if (t.matches("(?:play|resume|continue|unpause)(?: (?:it|the music|music|video))?")) return media(quoted, "play");
        if (t.matches("(?:pause|stop)(?: (?:it|the music|music|video|playing))?")) return media(quoted, "pause");
        if (t.matches("(?:next|skip)(?: (?:song|track|video|one))?")) return media(quoted, "next");
        if (t.matches("(?:previous|last|go back)(?: (?:song|track|video|one))?")) return media(quoted, "previous");

        // Inputs
        Matcher hdmi = Pattern.compile("(?:hdmi|input)\\s*(\\d)").matcher(t);
        if (hdmi.find()) {
            int n = Integer.parseInt(hdmi.group(1));
            if (n >= 1 && n <= 4) return input(quoted, "HDMI " + n, 22 + n);
        }
        if (t.matches("(?:switch to |go to |input )?(?:av|a v|composite)(?: input)?")) return input(quoted, "AV", 2);
        if (t.matches("(?:switch to |go to |watch )?(?:live tv|antenna|tv channels|television)")) {
            return start(new Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY").setPackage(TVPLAYER), quoted, "Live TV");
        }
        if (t.matches("(?:go )?home|home screen")) {
            return start(new Intent(ctx, HomeActivity.class), quoted, "Home");
        }

        // SmartTube search
        Matcher m;
        if ((m = PLAY_ON.matcher(t)).find() || (m = SEARCH.matcher(t)).find() || (m = YOUTUBE.matcher(t)).find()) {
            return search(quoted, m.group(1));
        }

        // Apps
        String target = (m = OPEN.matcher(t)).find() ? m.group(1) : t;
        AppEntry app = findApp(target);
        if (app != null) return start(app.launchIntent(), quoted, "Opening " + app.label);
        return "Didn't understand " + quoted;
    }

    // ---------------------------------------------------------------- actions

    private String media(String quoted, String action) {
        MediaController c = activeSession();
        if (c == null) return quoted + "  →  nothing is playing";
        MediaController.TransportControls tc = c.getTransportControls();
        switch (action) {
            case "play": tc.play(); return "Playing";
            case "pause": tc.pause(); return "Paused";
            case "next": tc.skipToNext(); return "Next";
            default: tc.skipToPrevious(); return "Previous";
        }
    }

    private MediaController activeSession() {
        try {
            List<MediaController> list = ctx.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(ctx, MediaListener.class));
            MediaController paused = null;
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s == null) continue;
                if (s.getState() == PlaybackState.STATE_PLAYING) return c;
                if (paused == null && s.getState() == PlaybackState.STATE_PAUSED) paused = c;
            }
            return paused;
        } catch (SecurityException e) {
            return null;
        }
    }

    private String input(String quoted, String name, int xiaomiSource) {
        Intent i = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").setPackage(TVPLAYER)
                .putExtra("input", xiaomiSource);
        return start(i, quoted, name);
    }

    private String search(String quoted, String query) {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
                .setPackage(SMARTTUBE);
        return start(i, quoted, "Searching “" + query + "”");
    }

    private String start(Intent i, String quoted, String done) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return done;
        } catch (RuntimeException e) {
            Log.w(TAG, "start failed: " + e);
            return "Couldn't do " + quoted;
        }
    }

    // ---------------------------------------------------------------- apps

    private AppEntry findApp(String spoken) {
        String want = squash(spoken);
        if (want.length() < 2) return null;
        List<AppEntry> apps = new ArrayList<>(AppEntry.loadDock(ctx));
        apps.addAll(AppEntry.loadGrid(ctx, apps));
        for (String[] a : ALIASES) {
            if (want.equals(squash(a[0])) || want.contains(squash(a[0]))) {
                for (AppEntry e : apps) if (e.pkg().equals(a[1])) return e;
            }
        }
        AppEntry best = null;
        double bestScore = 0;
        for (AppEntry e : apps) {
            String name = squash(e.label);
            double s;
            if (name.equals(want)) s = 1;
            else if (name.startsWith(want) || want.startsWith(name)) s = 0.9;
            else if (name.contains(want) || want.contains(name)) s = 0.8;
            else s = similarity(name, want);
            if (s > bestScore) {
                bestScore = s;
                best = e;
            }
        }
        return bestScore >= 0.6 ? best : null;
    }

    /** 1 - edit distance / length: tolerates small mishearings ("spot if i"). */
    private static double similarity(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - prev[b.length()] / (double) Math.max(a.length(), b.length());
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    /** Lower-case, digits for number words, joined spelled letters ("h d m i" -> "hdmi"). */
    private static String normalise(String s) {
        String t = " " + s.toLowerCase(Locale.US).trim().replaceAll("\\s+", " ") + " ";
        t = t.replace(" one ", " 1 ").replace(" two ", " 2 ").replace(" to ", " 2 ")
                .replace(" three ", " 3 ").replace(" four ", " 4 ").replace(" for ", " 4 ");
        t = t.replaceAll("\\b(h)\\s(d)\\s(m)\\s(i)\\b", "hdmi").replace("h d m i", "hdmi");
        t = t.trim();
        // Put "to"/"for" back outside HDMI/input phrases, where they were real words.
        if (!t.contains("hdmi") && !t.matches(".*input \\d.*")) {
            t = (" " + t + " ").replace(" 2 ", " to ").replace(" 4 ", " for ").trim();
        }
        return t;
    }
}
