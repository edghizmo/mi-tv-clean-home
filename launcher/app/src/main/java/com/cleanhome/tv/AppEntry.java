package com.cleanhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One launchable app: its label, banner or icon, and an accent colour sampled from it. */
public class AppEntry {
    public final ComponentName component;
    public final String label;
    public final Drawable banner;   // 16:9 TV banner, or null
    public final Drawable icon;     // used when there is no banner
    public final int accent;

    AppEntry(ComponentName component, String label, Drawable banner, Drawable icon) {
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.accent = sampleAccent(banner != null ? banner : icon);
    }

    public String pkg() {
        return component.getPackageName();
    }

    public Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(component);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** Loads the configured dock apps, in order, skipping any that are missing or disabled. */
    static List<AppEntry> loadDock(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> labels = labelOverrides(ctx);
        List<AppEntry> out = new ArrayList<>();
        for (String spec : ctx.getResources().getStringArray(R.array.dock_apps)) {
            ComponentName cn = spec.contains("/") ? ComponentName.unflattenFromString(spec)
                    : launcherComponent(pm, spec);
            AppEntry e = cn == null ? null : load(pm, cn, labels);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** All launchable apps except the dock apps, hidden apps and this launcher, sorted by name. */
    static List<AppEntry> loadGrid(Context ctx, List<AppEntry> dock) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, String> labels = labelOverrides(ctx);
        Set<String> skip = new HashSet<>();
        skip.add(ctx.getPackageName());
        for (String s : ctx.getResources().getStringArray(R.array.hidden_apps)) skip.add(s);
        for (AppEntry e : dock) skip.add(e.pkg());

        Map<String, ComponentName> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (skip.contains(p) || byPkg.containsKey(p)) continue;
                byPkg.put(p, new ComponentName(p, ri.activityInfo.name));
            }
        }
        // Apps without a launcher entry that should still be in the grid.
        for (String spec : ctx.getResources().getStringArray(R.array.extra_apps)) {
            ComponentName cn = ComponentName.unflattenFromString(spec);
            if (cn != null && !skip.contains(cn.getPackageName())) byPkg.putIfAbsent(cn.getPackageName(), cn);
        }
        List<AppEntry> out = new ArrayList<>();
        for (ComponentName cn : byPkg.values()) {
            AppEntry e = load(pm, cn, labels);
            if (e != null) out.add(e);
        }
        Collator c = Collator.getInstance();
        out.sort((a, b) -> c.compare(a.label, b.label));
        return out;
    }

    private static ComponentName launcherComponent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i == null ? null : i.getComponent();
    }

    private static AppEntry load(PackageManager pm, ComponentName cn, Map<String, String> labels) {
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            // ActivityInfo.isEnabled() is only the manifest default; apps such as
            // WakaTV switch their launcher activity on at runtime, so ask the
            // package manager for the live state instead.
            int state = pm.getComponentEnabledSetting(cn);
            boolean on = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && ai.isEnabled());
            if (!on || !ai.applicationInfo.enabled) return null;
            String label = labels.get(cn.getPackageName());
            if (label == null) label = String.valueOf(ai.applicationInfo.loadLabel(pm));
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            Drawable icon = ai.loadIcon(pm);
            return new AppEntry(cn, label, banner, icon);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static Map<String, String> labelOverrides(Context ctx) {
        Map<String, String> m = new HashMap<>();
        for (String s : ctx.getResources().getStringArray(R.array.label_overrides)) {
            int eq = s.indexOf('=');
            if (eq > 0) m.put(s.substring(0, eq), s.substring(eq + 1));
        }
        return m;
    }

    /** Picks a vivid colour from the artwork for the ambient glow (saturation-weighted mean). */
    private static int sampleAccent(Drawable d) {
        if (d == null) return 0xFF6E7BFF;
        int w = 32, h = 18;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        copy.setBounds(0, 0, w, h);
        copy.draw(new Canvas(bmp));
        float[] hsv = new float[3];
        double r = 0, g = 0, b = 0, total = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                double wgt = hsv[1] * hsv[1] * hsv[2];
                r += Color.red(c) * wgt;
                g += Color.green(c) * wgt;
                b += Color.blue(c) * wgt;
                total += wgt;
            }
        }
        bmp.recycle();
        if (total < 4) return 0xFF7A8699;   // greyscale artwork: neutral cool glow
        Color.colorToHSV(Color.rgb((int) (r / total), (int) (g / total), (int) (b / total)), hsv);
        hsv[1] = Math.max(hsv[1], 0.6f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }
}
