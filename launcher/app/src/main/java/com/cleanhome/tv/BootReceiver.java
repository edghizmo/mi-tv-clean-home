package com.cleanhome.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * At boot: make sure the Home button service is on (it then opens the
 * launcher when it connects), and try to open the launcher directly too.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        ServiceGuard.ensureEnabled(c);
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        try {
            c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Background start refused; the Home button service covers it instead.
        }
    }
}
