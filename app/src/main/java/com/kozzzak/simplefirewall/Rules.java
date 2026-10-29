package com.kozzzak.simplefirewall;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;

final class Rules {
    private Rules() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("firewall", Context.MODE_PRIVATE); }
    static Set<String> allowed(Context c) {
        SharedPreferences prefs = prefs(c);
        Set<String> result = new HashSet<>();
        for (String name : prefs.getStringSet("allowed", Collections.emptySet())) {
            try {
                long installed = c.getPackageManager().getPackageInfo(name, 0).firstInstallTime;
                if (installed == prefs.getLong("installed." + name, -1)) result.add(name);
            } catch (PackageManager.NameNotFoundException ignored) { /* removed */ }
        }
        return result;
    }
    static void save(Context c, Set<String> packages) {
        Set<String> present = new HashSet<>();
        SharedPreferences.Editor edit = prefs(c).edit();
        for (String name : packages) {
            try {
                long installed = c.getPackageManager().getPackageInfo(name, 0).firstInstallTime;
                present.add(name); edit.putLong("installed." + name, installed);
            } catch (PackageManager.NameNotFoundException ignored) { /* removed */ }
        }
        edit.putStringSet("allowed", present).commit();
    }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static void enabled(Context c, boolean value) { prefs(c).edit().putBoolean("enabled", value).commit(); }
    /** Optional DNS ad blocking. Off leaves the VPN behaviour of 1.3.0 unchanged. */
    static boolean adblock(Context c) { return prefs(c).getBoolean("adblock", false); }
    static void adblock(Context c, boolean value) { prefs(c).edit().putBoolean("adblock", value).commit(); }
    /** The user wants the firewall on, but Android or another VPN switched it off. */
    static boolean interrupted(Context c) { return prefs(c).getBoolean("interrupted", false); }
    static void interrupted(Context c, boolean value) { prefs(c).edit().putBoolean("interrupted", value).commit(); }
    /** Google Play services: delivers FCM push messages for most apps. */
    static final String PUSH_SERVICE = "com.google.android.gms";
}
