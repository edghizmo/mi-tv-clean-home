package com.cleanhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * Tracks the media session worth showing on the home screen (playing first,
 * otherwise the most recent paused one) and controls it. Event driven: active
 * sessions and the chosen controller's callbacks; nothing polls.
 */
final class NowPlaying {

    static final class State {
        String title, subtitle;
        Bitmap art;
        Drawable appIcon;
        boolean playing;
    }

    interface Listener {
        /** state is null when there is nothing to show. */
        void onNowPlaying(State state);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName me;
    private MediaSessionManager msm;
    private MediaController current;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            publish();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    NowPlaying(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.me = new ComponentName(ctx, MediaListener.class);
    }

    void start() {
        msm = ctx.getSystemService(MediaSessionManager.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main);
            pick(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            listener.onNowPlaying(null);   // notification access not granted
        }
    }

    void stop() {
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
        }
        setController(null);
    }

    void previous() {
        if (current != null) current.getTransportControls().skipToPrevious();
    }

    void next() {
        if (current != null) current.getTransportControls().skipToNext();
    }

    void togglePlay() {
        if (current == null) return;
        PlaybackState s = current.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) current.getTransportControls().pause();
        else current.getTransportControls().play();
    }

    private void pick(List<MediaController> sessions) {
        if (sessions == null) {
            try {
                sessions = msm.getActiveSessions(me);
            } catch (RuntimeException e) {
                sessions = null;
            }
        }
        MediaController best = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                if (!showable(c)) continue;
                if (isPlaying(c)) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        setController(best);
    }

    private void setController(MediaController c) {
        if (current != null && (c == null || !current.getSessionToken().equals(c.getSessionToken()))) {
            current.unregisterCallback(controllerCallback);
        }
        boolean changed = c == null || current == null || !current.getSessionToken().equals(c.getSessionToken());
        current = c;
        if (c != null && changed) c.registerCallback(controllerCallback, main);
        publish();
    }

    private void publish() {
        if (current == null || !showable(current)) {
            listener.onNowPlaying(null);
            return;
        }
        MediaMetadata m = current.getMetadata();
        State s = new State();
        s.title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        s.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (s.subtitle == null) s.subtitle = appLabel(current.getPackageName());
        s.art = art(m);
        s.playing = isPlaying(current);
        try {
            s.appIcon = ctx.getPackageManager().getApplicationIcon(current.getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        listener.onNowPlaying(s);
    }

    private static boolean showable(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        MediaMetadata m = c.getMetadata();
        if (s == null || m == null) return false;
        int st = s.getState();
        boolean active = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                || st == PlaybackState.STATE_BUFFERING;
        return active && text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE) != null;
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static String text(MediaMetadata m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static Bitmap art(MediaMetadata m) {
        if (m == null) return null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private String appLabel(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
