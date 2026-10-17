package com.kozzzak.simplefirewall;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;

final class Rules {
    private Rules() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("firewall", Context.MODE_PRIVATE); }
    static Set<String> allowed(Context c) { return new HashSet<>(prefs(c).getStringSet("allowed", Collections.emptySet())); }
    static void save(Context c, Set<String> packages) {
        prefs(c).edit().putStringSet("allowed", new HashSet<>(packages)).commit();
    }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static void enabled(Context c, boolean value) { prefs(c).edit().putBoolean("enabled", value).commit(); }
}
