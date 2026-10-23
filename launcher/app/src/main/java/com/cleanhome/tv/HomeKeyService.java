package com.cleanhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * This Xiaomi ROM only lets system apps be the HOME app, so this service makes
 * the launcher behave like one: it takes over the Home key, and if the system
 * still shows its own home (FallbackHome at boot, or the stock launcher) it
 * immediately opens this launcher on top. Event driven only, no polling.
 * It also runs the remote's voice button and reads WakaTV's screens for the
 * "Continue on WakaTV" row.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String FALLBACK_PKG = "com.xiaomi.mitv.settings";
    private static final String FALLBACK_CLASS = "com.xiaomi.mitv.settings.entry.FallbackHome";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final long BOOT_WINDOW_MS = 5 * 60 * 1000;
    private static final int VOICE_SCANCODE = 63;
    private VoiceAssistant voice;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // The Xiaomi remote's voice button has no key code, only scan code 63.
        // It now runs our offline voice commands instead of Xiao Ai.
        if (e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && e.getScanCode() == VOICE_SCANCODE) {
            if (voice == null) voice = new VoiceAssistant(this);
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) voice.press();
            else if (e.getAction() == KeyEvent.ACTION_UP) voice.release();
            return true;
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (Waka.PKG.contentEquals(pkg)) {
            onWakaTv();
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        boolean fallback = FALLBACK_PKG.contentEquals(pkg) && cls != null && FALLBACK_CLASS.contentEquals(cls);
        if (fallback || STOCK_HOME.contentEquals(pkg)) goHome();
    }

    /**
     * At boot the fallback home is already on screen before this service binds,
     * so its window event is never seen. If we connect shortly after boot, open
     * the launcher straight away.
     */
    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) goHome();
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------------------------------------------------------- WakaTV

    private static final String WAKA_ID = Waka.PKG + ":id/";
    private long lastWaka;

    /**
     * Records what WakaTV shows (see {@link Waka}) and, after a "Continue on
     * WakaTV" card was chosen, steers it there: Movies&Series, the title's
     * card, then Play on its page. Checks at most every 3 s (0.7 s while
     * steering); reads a few views by id, no full-tree walks.
     */
    private void onWakaTv() {
        String want = Waka.wanted();
        long now = SystemClock.elapsedRealtime();
        if (now - lastWaka < (want != null ? 700 : 3000)) return;
        lastWaka = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String page = text(root, "m_meta_tv_details_name");
        if (page != null) Waka.onDetailPage(this, page, text(root, "tv_details_tt"));
        String playing = text(root, "vod_media_player_title_view");
        if (playing != null) {
            Waka.onPlayer(this, playing, Waka.time(text(root, "vod_media_time_current")),
                    Waka.time(text(root, "vod_media_time_total")));
            if (want != null) Waka.opened();
            return;
        }
        if (want == null) return;
        // Never leave WakaTV's exit prompt up: cancel it and stop steering.
        AccessibilityNodeInfo cancel = byId(root, "exitdlg_btn_cancel");
        if (cancel != null) {
            Log.i("CleanHomeWaka", "reopen: exit prompt, cancelling and stopping");
            cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Waka.opened();
            return;
        }
        if (page != null && Waka.same(page, want)) {
            Log.i("CleanHomeWaka", "reopen: Play on \"" + page + "\"");
            click(byId(root, "b_details_play"));
            return;
        }
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(want)) {
            if (n.isVisibleToUser() && Waka.same(String.valueOf(n.getText()), want)) {
                Log.i("CleanHomeWaka", "reopen: card \"" + want + "\"");
                click(n);
                return;
            }
        }
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(WAKA_ID + "live_menu_text")) {
            if (n.isVisibleToUser() && "Movies&Series".contentEquals(String.valueOf(n.getText()))) {
                Log.i("CleanHomeWaka", "reopen: Movies&Series");
                click(n);
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        dump(root, sb, 0);
        Log.i("CleanHomeWaka", "reopen: nothing to press on\n" + sb);
    }

    private static AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(WAKA_ID + id);
        return l.isEmpty() ? null : l.get(0);
    }

    private static String text(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo n = byId(root, id);
        CharSequence t = n == null ? null : n.getText();
        return t == null || t.toString().trim().isEmpty() ? null : t.toString().trim();
    }

    /**
     * Clicks the node, or its parent when the text sits inside a clickable
     * card. Goes no higher: a distant ancestor may do something else entirely.
     */
    private static void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo p = n == null ? null : n.getParent();
        AccessibilityNodeInfo target = n != null && n.isClickable() ? n : p != null && p.isClickable() ? p : null;
        if (target == null) {
            Log.i("CleanHomeWaka", "reopen: not clickable: " + (n == null ? null : n.getViewIdResourceName()));
            return;
        }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static void dump(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 3500) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null || d != null) {
            sb.append(n.getViewIdResourceName()).append(" | ").append(t)
                    .append(d != null ? " | desc=" + d : "").append(n.isFocused() ? " [focused]" : "")
                    .append(n.isVisibleToUser() ? "" : " [hidden]").append(n.isClickable() ? " [clickable]" : "").append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), sb, depth + 1);
    }

    private void goHome() {
        Intent i = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }
}
