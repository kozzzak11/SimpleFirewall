package com.kozzzak.simplefirewall;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import static com.kozzzak.simplefirewall.Ui.*;

/** View-scoped monitoring: no background poller, no second VPN. */
final class TrafficPage extends LinearLayout {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView downSpeed, upSpeed, speedState, total, totalRx, totalTx, note, periodInfo, listTitle;
    private final View liveDot;
    private final TextView access;
    private final ChipGroup period;
    private final ImageView reload;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final BaseAdapter adapter;
    private final IconCache icons;
    private long top = 1;
    private boolean active, closed, busy, pending;
    private int revision;
    private long previousRx = -1, previousTx = -1, previousTime, lastRefresh;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || closed) return;
            long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = SystemClock.elapsedRealtime();
            if (rx >= 0 && tx >= 0 && previousRx >= 0 && previousTx >= 0 && now > previousTime) {
                long elapsed = now - previousTime;
                downSpeed.setText(TrafficMonitor.speed(Math.max(0, rx - previousRx) * 1000 / elapsed));
                upSpeed.setText(TrafficMonitor.speed(Math.max(0, tx - previousTx) * 1000 / elapsed));
                speedState.setText("Сейчас · оценочно");
                liveDot.animate().alpha(liveDot.getAlpha() > .6f ? .35f : 1f).setDuration(450).start();
            } else if (rx < 0 || tx < 0) speedState.setText("Скорость недоступна");
            previousRx = rx; previousTx = tx; previousTime = now;
            if (!TrafficMonitor.hasAccess(activity)) showNoAccess();
            else if (now - lastRefresh >= 15000) refresh();
            handler.postDelayed(this, 1000);
        }
    };

    TrafficPage(Activity activity) {
        super(activity); this.activity = activity; setOrientation(VERTICAL); setPadding(dp(16), dp(18), dp(16), 0);
        icons = IconCache.get(activity);
        LinearLayout head = column(activity);
        TextView title = bold(activity, "Трафик", 26, WHITE); head.addView(title);
        TextView subtitle = text(activity, "Скорость устройства и расход по приложениям", 13, MUTED);
        head.addView(subtitle, margins(-1, -2, activity, 6));

        // Live speed
        LinearLayout speedCard = column(activity); speedCard.setPadding(dp(16), dp(14), dp(16), dp(16));
        speedCard.setBackground(outlined(activity, CARD, STROKE, R_CARD));
        LinearLayout speedHead = row(activity);
        liveDot = new View(activity); GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(ACCENT); liveDot.setBackground(dot);
        speedHead.addView(liveDot, new LayoutParams(dp(8), dp(8)));
        speedState = label(activity, "Измерение скорости…"); speedState.setPadding(dp(8), 0, 0, 0); speedHead.addView(speedState);
        speedCard.addView(speedHead);
        LinearLayout speeds = row(activity);
        downSpeed = bold(activity, "—", 22, DOWN); upSpeed = bold(activity, "—", 22, UP);
        speeds.addView(speedColumn("↓ Загрузка", downSpeed), new LayoutParams(0, -2, 1));
        View split = new View(activity); split.setBackgroundColor(STROKE);
        speeds.addView(split, new LayoutParams(dp(1), dp(40)));
        LinearLayout upCol = speedColumn("↑ Отдача", upSpeed); upCol.setPadding(dp(16), 0, 0, 0);
        speeds.addView(upCol, new LayoutParams(0, -2, 1));
        speedCard.addView(speeds, margins(-1, -2, activity, 12));
        head.addView(speedCard, margins(-1, -2, activity, 16));

        // Period + refresh
        LinearLayout controls = row(activity);
        period = new ChipGroup(activity, "Сегодня", "7 дней", "30 дней"); period.setContentDescription("Период статистики");
        controls.addView(period, new LayoutParams(0, -2, 1));
        reload = new ImageView(activity); reload.setImageDrawable(icon(activity, R.drawable.ic_refresh, 20, WHITE));
        reload.setScaleType(ImageView.ScaleType.CENTER); reload.setContentDescription("Обновить");
        reload.setBackground(ripple(activity, outlined(activity, CARD, STROKE, R_PILL), R_PILL)); reload.setClickable(true);
        reload.setOnClickListener(v -> { v.animate().rotationBy(360).setDuration(500).start(); lastRefresh = 0; refresh(); });
        LayoutParams reloadParams = new LayoutParams(dp(36), dp(36)); reloadParams.leftMargin = dp(8);
        controls.addView(reload, reloadParams);
        head.addView(controls, margins(-1, -2, activity, 16));

        // Summary
        LinearLayout summary = column(activity); summary.setPadding(dp(18), dp(16), dp(18), dp(16));
        summary.setBackground(outlined(activity, 0xff1f2519, 0x33d4ff68, R_CARD));
        summary.addView(label(activity, "Всего за период"));
        total = bold(activity, "—", 36, WHITE); summary.addView(total, margins(-2, -2, activity, 8));
        LinearLayout split2 = row(activity);
        totalRx = bold(activity, "↓ —", 15, DOWN); totalTx = bold(activity, "↑ —", 15, UP); totalTx.setPadding(dp(18), 0, 0, 0);
        split2.addView(totalRx); split2.addView(totalTx);
        summary.addView(split2, margins(-1, -2, activity, 8));
        periodInfo = text(activity, "", 12, MUTED); summary.addView(periodInfo, margins(-1, -2, activity, 10));
        head.addView(summary, margins(-1, -2, activity, 12));
        note = text(activity, "Android обновляет счётчики с задержкой. Wi‑Fi и мобильная сеть вместе.", 12, FAINT);
        note.setLineSpacing(0, 1.15f); head.addView(note, margins(-1, -2, activity, 10));
        access = button(activity, "Разрешить доступ к статистике", true);
        head.addView(access, margins(-1, dp(50), activity, 12)); access.setOnClickListener(v -> openAccess());
        listTitle = label(activity, "По приложениям"); listTitle.setPadding(dp(2), dp(18), 0, dp(10)); head.addView(listTitle);

        adapter = new BaseAdapter() {
            public int getCount() { return entries.size(); }
            public Object getItem(int p) { return entries.get(p); }
            public long getItemId(int p) { return entries.get(p).uid; }
            public View getView(int p, View recycled, ViewGroup parent) {
                Holder h;
                if (recycled == null) {
                    h = new Holder();
                    LinearLayout box = column(activity); box.setPadding(dp(12), dp(12), dp(14), dp(12)); box.setBackground(round(activity, CARD, 16));
                    LinearLayout line = row(activity);
                    h.icon = new ImageView(activity); line.addView(h.icon, new LayoutParams(dp(36), dp(36)));
                    LinearLayout labels = column(activity); labels.setPadding(dp(12), 0, dp(8), 0);
                    h.name = bold(activity, "", 14, WHITE); h.name.setSingleLine(); h.name.setEllipsize(TextUtils.TruncateAt.END); labels.addView(h.name);
                    h.split = text(activity, "", 12, MUTED); labels.addView(h.split, margins(-1, -2, activity, 4));
                    line.addView(labels, new LayoutParams(0, -2, 1));
                    h.total = bold(activity, "", 15, WHITE); line.addView(h.total);
                    box.addView(line);
                    LinearLayout track = new LinearLayout(activity); track.setBackground(round(activity, 0xff2a302a, R_PILL));
                    h.bar = new View(activity); h.bar.setBackground(round(activity, ACCENT, R_PILL));
                    h.rest = new View(activity);
                    track.addView(h.bar, new LayoutParams(0, -1, 0)); track.addView(h.rest, new LayoutParams(0, -1, 1));
                    LayoutParams trackParams = new LayoutParams(-1, dp(4)); trackParams.topMargin = dp(10); trackParams.leftMargin = dp(48);
                    box.addView(track, trackParams); h.track = track;
                    box.setTag(h); recycled = box;
                } else h = (Holder) recycled.getTag();
                Entry item = entries.get(p);
                h.name.setText(item.name);
                h.split.setText("↓ " + TrafficMonitor.format(item.bytes.rx) + "   ↑ " + TrafficMonitor.format(item.bytes.tx));
                h.total.setText(TrafficMonitor.format(item.bytes.total()));
                icons.load(h.icon, item.packageName);
                float share = Math.max(.02f, Math.min(1f, item.bytes.total() / (float) top));
                h.bar.setAlpha(p == 0 ? 1f : .75f);
                h.bar.setLayoutParams(new LayoutParams(0, -1, share)); h.rest.setLayoutParams(new LayoutParams(0, -1, 1f - share));
                return recycled;
            }
        };
        ListView list = new ListView(activity); list.setDivider(null); list.setDividerHeight(dp(8));
        list.setSelector(new android.graphics.drawable.ColorDrawable(0)); list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(12));
        list.addHeaderView(head, null, false); list.setAdapter(adapter);
        addView(list, new LayoutParams(-1, 0, 1));
        period.setListener(pos -> {
            revision++; entries.clear(); adapter.notifyDataSetChanged(); total.setText("…");
            if (active) refresh();
        });
    }
    private LinearLayout speedColumn(String caption, TextView value) {
        LinearLayout c = column(activity);
        c.addView(text(activity, caption, 12, MUTED));
        c.addView(value, margins(-2, -2, activity, 6)); return c;
    }
    void resume() {
        if (closed || active) return;
        active = true; previousRx = previousTx = -1; lastRefresh = 0;
        refresh(); handler.post(tick);
    }
    void pause() { active = false; handler.removeCallbacks(tick); }
    void close() { pause(); closed = true; worker.shutdownNow(); }
    private void showNoAccess() {
        access.setVisibility(VISIBLE); total.setText("—"); totalRx.setText("↓ —"); totalTx.setText("↑ —");
        periodInfo.setText("Статистика по приложениям: нет доступа"); listTitle.setVisibility(GONE);
        note.setText("Для расхода по приложениям нужен системный доступ к статистике. Firewall работает и без него.");
        entries.clear(); adapter.notifyDataSetChanged();
    }
    private void refresh() {
        if (!active || closed) return;
        if (!TrafficMonitor.hasAccess(activity)) { revision++; showNoAccess(); return; }
        access.setVisibility(GONE); listTitle.setVisibility(VISIBLE);
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
                String iconPackage = null;
                if (names != null) for (String name : names) {
                    try {
                        ApplicationInfo info = pm.getApplicationInfo(name, 0);
                        labels.add(pm.getApplicationLabel(info).toString());
                        if (iconPackage == null || (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0) iconPackage = name;
                    } catch (PackageManager.NameNotFoundException ignored) {}
                }
                String name = !labels.isEmpty() ? String.join(" / ", labels) : b.getKey() == -4 ? "Удалённые приложения"
                    : b.getKey() == -5 ? "Раздача интернета" : "Система · UID " + b.getKey();
                rows.add(new Entry(b.getKey(), name, iconPackage, b.getValue()));
            }
            rows.sort((a,b) -> Long.compare(b.bytes.total(), a.bytes.total()));
            handler.post(() -> {
                busy = false;
                if (closed) return;
                if (requested == revision && TrafficMonitor.hasAccess(activity)) {
                    top = rows.isEmpty() ? 1 : Math.max(1, rows.get(0).bytes.total());
                    entries.clear(); entries.addAll(rows); adapter.notifyDataSetChanged();
                    boolean ok = result.networks > 0;
                    total.setText(ok ? TrafficMonitor.format(result.rx + result.tx) : "—");
                    totalRx.setText("↓ " + (ok ? TrafficMonitor.format(result.rx) : "—"));
                    totalTx.setText("↑ " + (ok ? TrafficMonitor.format(result.tx) : "—"));
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
    private static class Holder { ImageView icon; TextView name, split, total; View bar, rest; LinearLayout track; }
    private static class Entry {
        final int uid; final String name, packageName; final TrafficMonitor.Bytes bytes;
        Entry(int uid, String name, String packageName, TrafficMonitor.Bytes bytes) { this.uid = uid; this.name = name; this.packageName = packageName; this.bytes = bytes; }
    }
    private int dp(int n) { return Ui.dp(activity, n); }
}
