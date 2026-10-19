package com.cleanhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * This Xiaomi ROM only lets system apps be the HOME app, so this service makes
 * the launcher behave like one: it takes over the Home key, and if the system
 * still shows its own home (FallbackHome at boot, or the stock launcher) it
 * immediately opens this launcher on top. Event driven only, no polling.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String FALLBACK_PKG = "com.xiaomi.mitv.settings";
    private static final String FALLBACK_CLASS = "com.xiaomi.mitv.settings.entry.FallbackHome";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final long BOOT_WINDOW_MS = 5 * 60 * 1000;

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        CharSequence cls = e.getClassName();
        if (pkg == null) return;
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

    private void goHome() {
        Intent i = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }
}
