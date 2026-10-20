package com.kozzzak.simplefirewall;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** View-scoped monitoring: no background poller, no second VPN. */
final class TrafficPage extends LinearLayout {
    private static final int BG = 0xff101211, CARD = 0xff1d201d, WHITE = 0xfff4f7ef, MUTED = 0xffa1ab9d, ACCENT = 0xffd4ff68;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView speed, totals, note, periodInfo;
    private final Button access;
    private final Spinner period;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final BaseAdapter adapter;
    private boolean active, closed, busy, pending;
    private int revision;
    private long previousRx = -1, previousTx = -1, previousTime, lastRefresh;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || closed) return;
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = SystemClock.elapsedRealtime();
            if (rx >= 0 && tx >= 0 && previousRx >= 0 && previousTx >= 0 && now > previousTime) {
                long elapsed = now - previousTime;
                speed.setText("↓ " + TrafficMonitor.format(Math.max(0, rx - previousRx) * 1000 / elapsed)
                    + "/с     ↑ " + TrafficMonitor.format(Math.max(0, tx - previousTx) * 1000 / elapsed) + "/с");
            } else speed.setText(rx < 0 || tx < 0 ? "Скорость недоступна" : "Измерение скорости…");
            previousRx = rx; previousTx = tx; previousTime = now;
            if (!TrafficMonitor.hasAccess(activity)) showNoAccess();
            else if (now - lastRefresh >= 15000) refresh();
            handler.postDelayed(this, 1000);
        }
    };

    TrafficPage(Activity activity) {
        super(activity); this.activity = activity; setOrientation(VERTICAL); setPadding(dp(16), dp(14), dp(16), 0);
        TextView title = text("Трафик", 24, WHITE); title.setTypeface(null, Typeface.BOLD); addView(title);
        TextView subtitle = text("Скорость устройства · оценочно", 12, MUTED); subtitle.setPadding(0, dp(8), 0, dp(4)); addView(subtitle);
        speed = text("Измерение скорости…", 20, ACCENT); speed.setPadding(dp(12), dp(12), dp(12), dp(12));
        speed.setBackground(round(CARD)); addView(speed, new LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(activity); controls.setGravity(Gravity.CENTER_VERTICAL);
        period = new Spinner(activity); period.setContentDescription("Период статистики");
        ArrayAdapter<String> periods = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Сегодня", "7 дней", "30 дней"}); period.setAdapter(periods);
        controls.addView(period, new LayoutParams(0, dp(48), 1));
        Button reload = new Button(activity); reload.setAllCaps(false); reload.setText("Обновить");
        reload.setTextSize(13); controls.addView(reload); reload.setOnClickListener(v -> refresh()); addView(controls);
        totals = text("Статистика за период: —", 16, WHITE); addView(totals);
        periodInfo = text("", 11, MUTED); periodInfo.setPadding(0, dp(4), 0, dp(6)); addView(periodInfo);
        note = text("Android обновляет счётчики с задержкой. Wi‑Fi и мобильная сеть вместе.", 12, MUTED);
        note.setPadding(0, 0, 0, dp(8)); addView(note);
        access = new Button(activity); access.setAllCaps(false); access.setText("Разрешить доступ к статистике"); access.setTextSize(14);
        access.setTextColor(BG); access.setBackground(round(ACCENT));
        addView(access, new LayoutParams(-1, dp(48))); access.setOnClickListener(v -> openAccess());
        adapter = new BaseAdapter() {
            public int getCount() { return entries.size(); }
            public Object getItem(int p) { return entries.get(p); }
            public long getItemId(int p) { return entries.get(p).uid; }
            public View getView(int p, View recycled, ViewGroup parent) {
                LinearLayout row; TextView name, bytes;
                if (recycled == null) {
                    row = new LinearLayout(activity); row.setOrientation(VERTICAL); row.setPadding(dp(12), dp(9), dp(12), dp(9)); row.setBackground(round(CARD));
                    name = text("", 14, WHITE); name.setMaxLines(2); row.addView(name);
                    bytes = text("", 12, MUTED); row.addView(bytes); row.setTag(new TextView[]{name, bytes});
                } else row = (LinearLayout) recycled;
                TextView[] labels = (TextView[]) row.getTag(); Entry item = entries.get(p);
                labels[0].setText(item.name);
                labels[1].setText("↓ " + TrafficMonitor.format(item.bytes.rx) + "   ↑ " + TrafficMonitor.format(item.bytes.tx)
                    + "   · " + TrafficMonitor.format(item.bytes.total()));
                return row;
            }
        };
        ListView list = new ListView(activity); list.setDivider(null); list.setDividerHeight(dp(5)); list.setAdapter(adapter);
        addView(list, new LayoutParams(-1, 0, 1));
        period.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                revision++; entries.clear(); adapter.notifyDataSetChanged(); totals.setText("Загрузка статистики…");
                if (active) refresh();
            }
        });
    }
    void resume() {
        if (closed || active) return;
        active = true; previousRx = previousTx = -1; lastRefresh = 0;
        refresh(); handler.post(tick);
    }
    void pause() { active = false; handler.removeCallbacks(tick); }
    void close() { pause(); closed = true; worker.shutdownNow(); }
    private void showNoAccess() {
        access.setVisibility(VISIBLE); totals.setText("Статистика по приложениям: нет доступа");
        note.setText("Для расхода по приложениям нужен системный доступ к статистике. Firewall работает и без него.");
        entries.clear(); adapter.notifyDataSetChanged();
    }
    private void refresh() {
        if (!active || closed) return;
        if (!TrafficMonitor.hasAccess(activity)) { revision++; showNoAccess(); return; }
        access.setVisibility(GONE);
        if (busy) { pending = true; return; }
        busy = true; pending = false;
        int requested = revision, selected = period.getSelectedItemPosition();
        long start = TrafficMonitor.startOfPeriod(selected), end = System.currentTimeMillis();
        lastRefresh = SystemClock.elapsedRealtime();
        periodInfo.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(start))
            + " — " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(end)));
        worker.execute(() -> {
            TrafficMonitor.Snapshot result = TrafficMonitor.read(activity, start, end);
            ArrayList<Entry> rows = new ArrayList<>();
            PackageManager pm = activity.getPackageManager();
            for (Map.Entry<Integer, TrafficMonitor.Bytes> b : result.byUid.entrySet()) {
                if (b.getValue().total() == 0) continue;
                String[] names = pm.getPackagesForUid(b.getKey()); TreeSet<String> labels = new TreeSet<>();
                if (names != null) for (String name : names) {
                    try { labels.add(pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()); }
                    catch (PackageManager.NameNotFoundException ignored) {}
                }
                String name = !labels.isEmpty() ? String.join(" / ", labels) : b.getKey() == -4 ? "Удалённые приложения"
                    : b.getKey() == -5 ? "Раздача интернета" : "Система · UID " + b.getKey();
                rows.add(new Entry(b.getKey(), name, b.getValue()));
            }
            rows.sort((a,b) -> Long.compare(b.bytes.total(), a.bytes.total()));
            handler.post(() -> {
                busy = false;
                if (closed) return;
                if (requested == revision && TrafficMonitor.hasAccess(activity)) {
                    entries.clear(); entries.addAll(rows); adapter.notifyDataSetChanged();
                    totals.setText(result.networks == 0 ? "Статистика за период: недоступна"
                        : "↓ " + TrafficMonitor.format(result.rx) + "    ↑ " + TrafficMonitor.format(result.tx)
                            + "    · " + TrafficMonitor.format(result.rx + result.tx));
                    note.setText(!result.error.isEmpty() ? result.error + ". Значения неполные."
                        : "Wi‑Fi + мобильная сеть. Счётчики Android обновляются с задержкой; это не счёт оператора.");
                }
                if (active && (pending || requested != revision)) refresh();
            });
        });
    }
    private void openAccess() {
        try { activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(android.net.Uri.parse("package:" + activity.getPackageName()))); }
        catch (ActivityNotFoundException e) {
            try { activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) { Toast.makeText(activity, "Системный экран статистики недоступен", Toast.LENGTH_LONG).show(); }
        }
    }
    private static class Entry {
        final int uid; final String name; final TrafficMonitor.Bytes bytes;
        Entry(int uid, String name, TrafficMonitor.Bytes bytes) { this.uid = uid; this.name = name; this.bytes = bytes; }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color) { TextView v = new TextView(activity); v.setText(s); v.setTextSize(size); v.setTextColor(color); return v; }
    private GradientDrawable round(int color) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); return d; }
}
