package com.cleanhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/**
 * Turns the Home button accessibility service back on if the system switched
 * it off (this TV did so once after an unexpected restart). Writing secure
 * settings needs WRITE_SECURE_SETTINGS, which install.sh grants over ADB.
 * Other enabled accessibility services are kept.
 */
final class ServiceGuard {
    private static final String TAG = "CleanHome";

    private ServiceGuard() {
    }

    /** Returns true if the service was off and has just been switched back on. */
    static boolean ensureEnabled(Context c) {
        String me = new ComponentName(c, HomeKeyService.class).flattenToShortString();
        String meLong = new ComponentName(c, HomeKeyService.class).flattenToString();
        try {
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = false;
            if (list != null) {
                for (String s : list.split(":")) {
                    if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meLong)) listed = true;
                }
            }
            boolean on = Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            if (listed && on) return false;
            if (!listed) {
                list = list == null || list.isEmpty() ? me : list + ":" + me;
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
            }
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "Home button service was off; switched it back on");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot re-enable Home button service (WRITE_SECURE_SETTINGS not granted)");
            return false;
        }
    }
}
