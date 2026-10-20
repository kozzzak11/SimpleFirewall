package com.kozzzak.simplefirewall;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Process;
import java.util.*;

/** Reads Android's accounted Wi-Fi + mobile bytes. No packet interception. */
final class TrafficMonitor {
    static final class Bytes {
        long rx, tx;
        long total() { return rx + tx; }
    }
    static final class Snapshot {
        final Map<Integer, Bytes> byUid = new HashMap<>();
        long rx, tx;
        int networks;
        String error = "";
    }
    static boolean hasAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    static long startOfPeriod(int period) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, period == 1 ? -6 : period == 2 ? -29 : 0);
        return c.getTimeInMillis();
    }
    static Snapshot read(Context c, long start, long end) {
        Snapshot result = new Snapshot();
        if (!hasAccess(c)) { result.error = "Нужен доступ к статистике использования"; return result; }
        NetworkStatsManager manager = c.getSystemService(NetworkStatsManager.class);
        for (int type : new int[]{ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE}) {
            // Commit each network only on complete success: an interrupted query
            // must not silently present partial bytes as a complete result.
            Map<Integer, Bytes> network = new HashMap<>();
            try (NetworkStats stats = manager.querySummary(type, null, start, end)) {
                if (stats == null) throw new IllegalStateException("No statistics");
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break;
                    Bytes b = network.computeIfAbsent(bucket.getUid(), uid -> new Bytes());
                    b.rx += Math.max(0, bucket.getRxBytes()); b.tx += Math.max(0, bucket.getTxBytes());
                }
                for (Map.Entry<Integer, Bytes> item : network.entrySet()) {
                    Bytes b = result.byUid.computeIfAbsent(item.getKey(), uid -> new Bytes());
                    b.rx += item.getValue().rx; b.tx += item.getValue().tx;
                    result.rx += item.getValue().rx; result.tx += item.getValue().tx;
                }
                result.networks++;
            } catch (Exception e) { result.error = "Часть статистики недоступна"; }
        }
        if (result.networks == 0) result.error = "Не удалось получить статистику Android";
        return result;
    }
    static String format(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " Б";
        double n = bytes; String[] units = {"Б", "КиБ", "МиБ", "ГиБ", "ТиБ"}; int unit = 0;
        while (n >= 1024 && unit < units.length - 1) { n /= 1024; unit++; }
        return String.format(Locale.getDefault(), n < 10 ? "%.1f %s" : "%.0f %s", n, units[unit]);
    }
}
