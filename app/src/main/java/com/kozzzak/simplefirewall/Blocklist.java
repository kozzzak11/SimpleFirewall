package com.kozzzak.simplefirewall;

import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Bundled StevenBlack list, plus a small extra file that an update cannot drop. */
final class Blocklist {
    static final String SOURCE = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String FILE = "adblock-hosts.txt";
    static volatile DomainFilter current = DomainFilter.EMPTY;
    static volatile boolean custom;
    private static boolean loaded;

    private Blocklist() {}

    static synchronized void ensure(Context c) {
        if (loaded && current != DomainFilter.EMPTY) return;
        try { current = read(c); loaded = true; }
        catch (IOException e) { current = DomainFilter.EMPTY; }
    }

    static synchronized String update(Context c) {
        File tmp = new File(c.getFilesDir(), FILE + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(SOURCE).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "SimpleFirewall/1.4.0");
            if (conn.getResponseCode() != 200) return "Не удалось обновить список";
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 8_000_000) return "Список слишком большой";
                    out.write(buf, 0, n);
                }
            }
            DomainFilter check = read(c, new FileInputStream(tmp));
            if (check.size() < 10000) return "Ответ не похож на список блокировки";
            File dest = new File(c.getFilesDir(), FILE);
            if (!tmp.renameTo(dest)) {
                try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                tmp.delete();
            }
            current = read(c);
            loaded = true;
            return "Список обновлён · " + count() + " доменов";
        } catch (IOException e) {
            tmp.delete();
            return "Нет сети, оставлен сохранённый список";
        } finally { if (conn != null) conn.disconnect(); }
    }

    static String count() { return String.format(Locale.ROOT, "%,d", current.size()).replace(',', ' '); }
    static String origin() { return custom ? "обновлённый список" : "встроенный список"; }

    private static DomainFilter read(Context c) throws IOException {
        File saved = new File(c.getFilesDir(), FILE);
        custom = saved.isFile() && saved.length() > 1000;
        if (custom) {
            try { return read(c, new FileInputStream(saved)); }
            catch (IOException e) { saved.delete(); custom = false; }
        }
        return read(c, c.getAssets().open("adblock-hosts.txt"));
    }

    private static DomainFilter read(Context c, InputStream base) throws IOException {
        try (Reader extra = new InputStreamReader(c.getAssets().open("adblock-extra.txt"), StandardCharsets.UTF_8);
             Reader hosts = new InputStreamReader(base, StandardCharsets.UTF_8)) {
            return DomainFilter.load(extra, hosts);
        }
    }
}
