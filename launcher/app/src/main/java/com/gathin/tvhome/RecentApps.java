package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/** Apps in the order they came to the front, newest first, for the app switcher. */
final class RecentApps {
    private static final String FILE = "recent_apps", KEY = "list";
    private static final int MAX = 8;
    private static String last = "";

    private RecentApps() { }

    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String s = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "");
            for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        } catch (RuntimeException locked) {
            // storage not unlocked yet after boot
        }
        return out;
    }

    /** A window of this package came up: remember it if it is an app the user can open. */
    /** The app in front right now; empty when it is Home. */
    static String front = "";

    static void note(Context c, String pkg) {
        if (pkg.equals(c.getPackageName())) {
            if (!RecentsActivity.showing) front = "";
            return;
        }
        if (pkg.equals("com.android.systemui")) return;
        if (pkg.equals(last)) {
            front = pkg;
            return;
        }
        try {
            PackageManager pm = c.getPackageManager();
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null && pm.getLaunchIntentForPackage(pkg) == null) return;
            front = pkg;
            List<String> l = list(c);
            l.remove(pkg);
            l.add(0, pkg);
            while (l.size() > MAX) l.remove(l.size() - 1);
            save(c, l);
            last = pkg;
        } catch (RuntimeException ignored) {
        }
    }

    static void remove(Context c, String pkg) {
        List<String> l = list(c);
        if (l.remove(pkg)) save(c, l);
        if (pkg.equals(last)) last = "";
    }

    private static void save(Context c, List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String p : l) sb.append(sb.length() == 0 ? "" : ",").append(p);
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply();
    }
}
