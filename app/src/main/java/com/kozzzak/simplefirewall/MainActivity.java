package com.kozzzak.simplefirewall;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,18,17), CARD = Color.rgb(29,32,29),
        WHITE = Color.rgb(244,247,239), MUTED = Color.rgb(161,171,157), ACCENT = Color.rgb(212,255,104);
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ArrayList<AppGroup> all = new ArrayList<>(), visible = new ArrayList<>();
    private Set<String> allowed;
    private Switch power;
    private TextView status, count, allowedSummary;
    private FrameLayout pages;
    private View firewallPage, appsPage;
    private Button firewallTab, appsTab, trafficTab;
    private TrafficPage trafficPage;
    private boolean resumed;
    private int currentTab;
    private EditText search;
    private Spinner appFilter;
    private AppAdapter adapter;
    private boolean binding;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateStatus(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        allowed = Rules.allowed(this);
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        pages = new FrameLayout(this);
        firewallPage = buildFirewallPage();
        appsPage = buildAppsPage();
        pages.addView(firewallPage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(appsPage, new FrameLayout.LayoutParams(-1, -1));
        trafficPage = new TrafficPage(this);
        pages.addView(trafficPage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));

        View divider = new View(this); divider.setBackgroundColor(CARD);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(12), dp(6), dp(12), dp(6));
        firewallTab = navButton("Firewall", R.drawable.ic_shield, 0);
        appsTab = navButton("Приложения", R.drawable.ic_apps, 1);
        trafficTab = navButton("Трафик", R.drawable.ic_traffic, 2);
        navigation.addView(firewallTab, new LinearLayout.LayoutParams(0, dp(64), 1));
        LinearLayout.LayoutParams appsNavParams = new LinearLayout.LayoutParams(0, dp(64), 1);
        appsNavParams.leftMargin = dp(4); navigation.addView(appsTab, appsNavParams);
        LinearLayout.LayoutParams trafficNavParams = new LinearLayout.LayoutParams(0, dp(64), 1);
        trafficNavParams.leftMargin = dp(4); navigation.addView(trafficTab, trafficNavParams);
        root.addView(navigation);
        if (state != null) {
            search.setText(state.getString("query", ""));
            appFilter.setSelection(state.getInt("filter", 0));
        }
        selectTab(state != null ? state.getInt("tab", 0) : Rules.prefs(this).getInt("tab", 0));
        power.setOnCheckedChangeListener((button, checked) -> { if (!binding) { if (checked) requestStart(); else stopFirewall(); } });
        appFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View view, int position, long id) { filter(); }
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            public void afterTextChanged(Editable e) {}
        });
        registerUpdates();
        updateStatus();
    }

    private View buildFirewallPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(22), dp(22), dp(22), dp(16));
        scroll.addView(page);
        TextView brand = text("SIMPLE / FIREWALL", 12, ACCENT);
        brand.setLetterSpacing(.18f); page.addView(brand);
        TextView title = text("Твой интернет.\nТвои правила.", 30, WHITE);
        title.setTypeface(null, Typeface.BOLD); title.setPadding(0, dp(20), 0, dp(24)); page.addView(title);
        LinearLayout card = column(); card.setPadding(dp(18), dp(15), dp(18), dp(18)); card.setBackground(round(CARD, 20));
        power = new Switch(this); power.setText("Firewall"); power.setTextSize(21); power.setTextColor(WHITE); power.setSwitchPadding(dp(16));
        power.setTypeface(null, Typeface.BOLD); card.addView(power, new LinearLayout.LayoutParams(-1, dp(48)));
        status = text("Выключен", 14, MUTED); status.setPadding(0, dp(8), 0, 0); card.addView(status); page.addView(card);
        TextView intro = text("Интернет только у выбранных приложений.\nОдни правила для Wi‑Fi и мобильной сети.", 15, MUTED);
        intro.setPadding(0, dp(20), 0, dp(24)); page.addView(intro);
        allowedSummary = text("Разрешено приложений: 0", 16, WHITE); page.addView(allowedSummary);
        Button choose = new Button(this); choose.setText("Выбрать приложения  →"); choose.setAllCaps(false); choose.setTextSize(16);
        choose.setTextColor(BG); choose.setBackground(round(ACCENT, 14));
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(-1, dp(52)); chooseParams.topMargin = dp(14);
        page.addView(choose, chooseParams); choose.setOnClickListener(v -> selectTab(1));
        Button repair = new Button(this); repair.setAllCaps(false); repair.setText("Восстановить соединение"); repair.setTextSize(14);
        repair.setTextColor(WHITE); repair.setBackground(round(CARD, 12));
        LinearLayout.LayoutParams repairParams = new LinearLayout.LayoutParams(-1, dp(48)); repairParams.topMargin = dp(12);
        page.addView(repair, repairParams);
        repair.setOnClickListener(v -> {
            if (Rules.enabled(this)) startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.REPAIR));
            else Toast.makeText(this, "Сначала включите Firewall", Toast.LENGTH_SHORT).show();
        });
        TextView help = text("Как это работает", 14, MUTED); help.setGravity(Gravity.CENTER); help.setPadding(0, dp(24), 0, dp(16));
        page.addView(help, new LinearLayout.LayoutParams(-1, -2)); help.setOnClickListener(v -> showHelp());
        return scroll;
    }

    private View buildAppsPage() {
        LinearLayout page = column(); page.setPadding(dp(16), dp(14), dp(16), 0);
        TextView title = text("Приложения", 24, WHITE); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        TextView subtitle = text("Отметь приложения с доступом в интернет", 13, MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(12)); page.addView(subtitle);
        search = new EditText(this); search.setId(R.id.app_search); search.setSingleLine(true); search.setTextSize(15); search.setTextColor(WHITE);
        search.setHintTextColor(MUTED); search.setHint("Поиск приложений");
        search.setPadding(dp(14), 0, dp(14), 0); search.setBackground(round(CARD, 12));
        page.addView(search, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        count = text("Загрузка…", 13, MUTED); count.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(count, new LinearLayout.LayoutParams(0, dp(48), 1));
        appFilter = new Spinner(this); appFilter.setId(R.id.app_filter); appFilter.setContentDescription("Фильтр приложений");
        appFilter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Все", "Обычные", "Системные"}));
        toolbar.addView(appFilter, new LinearLayout.LayoutParams(dp(150), dp(48))); page.addView(toolbar);
        ListView list = new ListView(this); list.setId(R.id.app_list); list.setDivider(null); list.setDividerHeight(dp(5));
        list.setCacheColorHint(BG); adapter = new AppAdapter(); list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private Button navButton(String label, int icon, int tab) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13);
        button.setPadding(dp(8), dp(7), dp(8), dp(5)); button.setMinHeight(0); button.setMinimumHeight(0);
        android.graphics.drawable.Drawable drawable = getDrawable(icon).mutate();
        drawable.setBounds(0, 0, dp(22), dp(22));
        button.setCompoundDrawables(null, drawable, null, null); button.setCompoundDrawablePadding(dp(3));
        button.setContentDescription("Вкладка " + label);
        button.setOnClickListener(v -> selectTab(tab)); return button;
    }

    private void selectTab(int tab) {
        currentTab = tab >= 0 && tab <= 2 ? tab : 0;
        firewallPage.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
        appsPage.setVisibility(currentTab == 1 ? View.VISIBLE : View.GONE);
        trafficPage.setVisibility(currentTab == 2 ? View.VISIBLE : View.GONE);
        styleTab(firewallTab, currentTab == 0); styleTab(appsTab, currentTab == 1); styleTab(trafficTab, currentTab == 2);
        if (resumed && currentTab == 2) trafficPage.resume(); else trafficPage.pause();
        Rules.prefs(this).edit().putInt("tab", currentTab).apply();
        if (currentTab != 1) {
            search.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }
    private void styleTab(Button tab, boolean selected) {
        tab.setSelected(selected); tab.setTextColor(selected ? ACCENT : MUTED);
        tab.setBackground(round(selected ? CARD : Color.TRANSPARENT, 14));
        tab.getCompoundDrawables()[1].setTint(selected ? ACCENT : MUTED);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", currentTab); state.putString("query", search.getText().toString());
        state.putInt("filter", appFilter.getSelectedItemPosition()); super.onSaveInstanceState(state);
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUpdates() {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updates, new IntentFilter(FirewallService.STATUS), Context.RECEIVER_NOT_EXPORTED);
        // Pre-33 Android lacks NOT_EXPORTED. A signature permission protects
        // this app-private status receiver instead; no AndroidX runtime needed.
        else registerReceiver(updates, new IntentFilter(FirewallService.STATUS), "com.kozzzak.simplefirewall.INTERNAL", null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (currentTab == 2) trafficPage.resume();
        allowed = Rules.allowed(this);
        if (Rules.enabled(this) && !FirewallService.running) {
            if (VpnService.prepare(this) == null) startFirewall();
            else Rules.enabled(this, false);
        }
        updateStatus(); loadApps();
    }

    private void requestStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) { startActivityForResult(consent, 10); return; }
        startFirewall();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 10 && result == RESULT_OK) startFirewall(); else updateStatus();
    }
    private void startFirewall() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        Rules.enabled(this, true);
        FirewallService.error = "";
        try { startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.START)); }
        catch (RuntimeException e) { Rules.enabled(this, false); FirewallService.error = "Не удалось запустить firewall."; }
        updateStatus();
    }
    private void stopFirewall() {
        Rules.enabled(this, false); FirewallService.error = "";
        startService(new Intent(this, FirewallService.class).setAction(FirewallService.STOP)); updateStatus();
    }
    private void updateStatus() {
        binding = true;
        boolean desired = Rules.enabled(this);
        power.setChecked(desired); binding = false;
        String error = FirewallService.error;
        status.setText(!error.isEmpty() ? error : desired ? (FirewallService.updating ? "Применение правил…" : FirewallService.running ? "Включён · остальные приложения без интернета" : "Запуск…") : "Выключен · доступ есть у всех приложений");
        status.setTextColor(!error.isEmpty() ? Color.rgb(255,172,140) : desired ? ACCENT : MUTED);
    }

    private void loadApps() {
        loader.execute(() -> {
            PackageManager pm = getPackageManager();
            TreeMap<Integer, AppGroup> groups = new TreeMap<>();
            for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (info.applicationInfo == null || info.packageName.equals(getPackageName())) continue;
                AppGroup g = groups.computeIfAbsent(info.applicationInfo.uid, uid -> new AppGroup());
                if (info.requestedPermissions != null && Arrays.asList(info.requestedPermissions).contains(Manifest.permission.INTERNET)) g.network = true;
                g.packages.add(info.packageName);
                if (pm.getLaunchIntentForPackage(info.packageName) != null) g.launchable = true;
                String label = info.applicationInfo.loadLabel(pm).toString();
                g.labels.add(label);
                if (g.info == null) g.info = info.applicationInfo;
                if ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) { g.user = true; g.info = info.applicationInfo; }
            }
            ArrayList<AppGroup> result = new ArrayList<>(groups.values());
            result.removeIf(g -> !g.network);
            for (AppGroup g : result) {
                g.label = String.join(" / ", g.labels);
                g.details = String.join(" · ", g.packages);
            }
            result.sort((a,b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                all.clear(); all.addAll(result); filter();
            });
        });
    }

    private boolean selected(AppGroup g) { for (String p : g.packages) if (allowed.contains(p)) return true; return false; }
    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear(); int n = 0;
        for (AppGroup g : all) {
            boolean selected = selected(g); if (selected) n++;
            int mode = appFilter.getSelectedItemPosition();
            boolean included = mode == 0 || (mode == 1 ? (g.user || g.launchable) : !g.user);
            if (included && (g.label + " " + g.details).toLowerCase(Locale.ROOT).contains(q)) visible.add(g);
        }
        count.setText("В списке: " + visible.size() + " / " + all.size());
        allowedSummary.setText("Разрешено приложений: " + n); adapter.notifyDataSetChanged();
    }

    private void toggle(AppGroup g, boolean checked) {
        if (checked) allowed.addAll(g.packages); else allowed.removeAll(g.packages);
        Rules.save(this, allowed);
        if (Rules.enabled(this)) {
            try { startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.UPDATE)); }
            catch (RuntimeException e) { FirewallService.error = "Правила не обновлены. Перезапустите firewall."; updateStatus(); }
        }
        filter();
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("SimpleFirewall")
            .setMessage("Отметьте приложения и включите firewall. Новые приложения по умолчанию заблокированы. Настройки сохраняются.\n\nРаботает локально через VPN Android, без серверов, рекламы и сбора данных. Другой VPN одновременно использовать нельзя.\n\nПо умолчанию показаны все приложения с разрешением на сеть. Фильтр «Обычные» включает предустановленные приложения с иконкой запуска. Поиск не меняет выбранный фильтр. У приложений с общим UID доступ общий — они объединены в одну строку. Для push-уведомлений могут понадобиться сервисы Google.\n\nФильтруется трафик приложений текущего профиля, IPv4 и IPv6. Раздача интернета и другие профили не фильтруются.\n\nЕсли Android остановит службу, блокировка прекратится. Режим «Блокировать соединения без VPN» с этой версией несовместим: выбранные приложения выходят в интернет напрямую.\n\nПосле перезагрузки телефона откройте приложение для возобновления работы.")
            .setPositiveButton("Понятно", null).show();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int pos) { return visible.get(pos); }
        @Override public long getItemId(int pos) { return visible.get(pos).info.uid; }
        @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
            Row row;
            if (recycled == null) {
                row = new Row(); LinearLayout box = new LinearLayout(MainActivity.this); box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(10), dp(9), dp(8), dp(9)); box.setBackground(round(CARD, 14));
                row.icon = new ImageView(MainActivity.this); box.addView(row.icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout labels = column(); labels.setPadding(dp(12), 0, dp(8), 0);
                row.title = text("", 15, WHITE); row.title.setMaxLines(2); row.title.setEllipsize(TextUtils.TruncateAt.END); labels.addView(row.title);
                row.detail = text("", 11, MUTED); row.detail.setSingleLine(); row.detail.setEllipsize(TextUtils.TruncateAt.MIDDLE); labels.addView(row.detail);
                box.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.check = new CheckBox(MainActivity.this); row.check.setClickable(false); row.check.setFocusable(false); row.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); box.addView(row.check);
                box.setTag(row); recycled = box;
            } else row = (Row) recycled.getTag();
            AppGroup g = visible.get(position); row.title.setText(g.label); row.detail.setText(g.details);
            row.icon.setImageDrawable(g.info.loadIcon(getPackageManager())); row.check.setChecked(selected(g));
            recycled.setContentDescription(g.label + (selected(g) ? ", интернет разрешён" : ", интернет запрещён"));
            recycled.setOnClickListener(v -> toggle(g, !selected(g))); return recycled;
        }
    }
    private static class Row { ImageView icon; TextView title, detail; CheckBox check; }
    private static class AppGroup {
        final ArrayList<String> packages = new ArrayList<>(); final TreeSet<String> labels = new TreeSet<>();
        String label, details; boolean user, network, launchable; ApplicationInfo info;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onPause() { resumed = false; trafficPage.pause(); super.onPause(); }
    @Override protected void onDestroy() { trafficPage.close(); unregisterReceiver(updates); loader.shutdown(); super.onDestroy(); }
}
