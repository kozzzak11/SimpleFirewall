package com.kozzzak.simplefirewall;

import android.Manifest;
import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import static com.kozzzak.simplefirewall.Ui.*;

public class MainActivity extends Activity {
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ArrayList<AppGroup> all = new ArrayList<>(), visible = new ArrayList<>();
    private Set<String> allowed;
    private TextView status, stateTitle, count, allowedBadge, allowedValue, todayValue, todayCaption;
    private View heroRing, heroHalo;
    private ImageView heroIcon;
    private ObjectAnimator pulse;
    private FrameLayout pages;
    private View firewallPage, appsPage;
    private final ArrayList<View> pushHints = new ArrayList<>();
    private LinearLayout firewallTab, appsTab, trafficTab;
    private TrafficPage trafficPage;
    private Switch adblockSwitch;
    private TextView adblockMeta, adblockHint;
    private boolean adblockGuard, pendingAdblock;
    private boolean resumed;
    private int currentTab = -1;
    private EditText search;
    private ChipGroup appFilter;
    private AppAdapter adapter;
    private IconCache icons;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateStatus(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        icons = IconCache.get(this);
        allowed = Rules.allowed(this);
        LinearLayout root = column(this);
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

        LinearLayout navigation = row(this);
        navigation.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
        navigation.setBackground(outlined(this, SURFACE, STROKE, 26));
        firewallTab = navButton("Firewall", R.drawable.ic_shield, 0);
        appsTab = navButton("Приложения", R.drawable.ic_apps, 1);
        trafficTab = navButton("Трафик", R.drawable.ic_traffic, 2);
        navigation.addView(firewallTab, new LinearLayout.LayoutParams(0, dp(this, 56), 1));
        navigation.addView(appsTab, new LinearLayout.LayoutParams(0, dp(this, 56), 1));
        navigation.addView(trafficTab, new LinearLayout.LayoutParams(0, dp(this, 56), 1));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, -2);
        navParams.setMargins(dp(this, 14), dp(this, 4), dp(this, 14), dp(this, 10));
        root.addView(navigation, navParams);
        if (state != null) {
            search.setText(state.getString("query", ""));
            appFilter.setSelection(state.getInt("filter", 0));
            pendingAdblock = state.getBoolean("pending_adblock");
        }
        loader.execute(() -> Blocklist.ensure(this));
        selectTab(state != null ? state.getInt("tab", 0) : Rules.prefs(this).getInt("tab", 0));
        appFilter.setListener(position -> filter());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            public void afterTextChanged(Editable e) {}
        });
        registerUpdates();
        updateStatus();
        handleIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent) {
        if (intent != null && FirewallService.ENABLE.equals(intent.getAction())) {
            setIntent(new Intent(this, MainActivity.class)); selectTab(0);
            if (Rules.enabled(this) || !Rules.adblock(this)) requestStart(); else applyAdblock(true);
        }
    }

    // ---------- Firewall ----------
    private View buildFirewallPage() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = column(this); page.setPadding(dp(this, 20), dp(this, 18), dp(this, 20), dp(this, 16));
        scroll.addView(page);
        TextView brand = bold(this, "SIMPLE / FIREWALL", 12, ACCENT); brand.setLetterSpacing(.2f); page.addView(brand);
        TextView title = bold(this, "Твой интернет.\nТвои правила.", 28, WHITE); title.setLineSpacing(0, 1.1f);
        title.setPadding(0, dp(this, 12), 0, dp(this, 18)); page.addView(title);

        LinearLayout hero = column(this); hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(this, 16), dp(this, 22), dp(this, 16), dp(this, 22)); hero.setBackground(outlined(this, CARD, STROKE, R_CARD));
        FrameLayout dial = new FrameLayout(this);
        heroHalo = new View(this);
        dial.addView(heroHalo, new FrameLayout.LayoutParams(dp(this, 210), dp(this, 210), Gravity.CENTER));
        heroRing = new View(this); heroRing.setId(R.id.power_button); heroRing.setClickable(true); heroRing.setFocusable(true);
        dial.addView(heroRing, new FrameLayout.LayoutParams(dp(this, 150), dp(this, 150), Gravity.CENTER));
        heroIcon = new ImageView(this); heroIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dial.addView(heroIcon, new FrameLayout.LayoutParams(dp(this, 64), dp(this, 64), Gravity.CENTER));
        hero.addView(dial, new LinearLayout.LayoutParams(dp(this, 210), dp(this, 210)));
        heroRing.setOnClickListener(v -> {
            v.animate().scaleX(.94f).scaleY(.94f).setDuration(90).withEndAction(() -> v.animate().scaleX(1).scaleY(1).setDuration(140).start()).start();
            if (desired()) stopFirewall(); else requestStart();
        });
        stateTitle = bold(this, "Защита выключена", 22, WHITE); stateTitle.setGravity(Gravity.CENTER);
        hero.addView(stateTitle, margins(-1, -2, this, 4));
        status = text(this, "Выключен", 14, MUTED); status.setGravity(Gravity.CENTER); status.setLineSpacing(0, 1.15f);
        hero.addView(status, margins(-1, -2, this, 8));
        TextView hint = text(this, "Нажми на щит, чтобы включить или выключить", 12, FAINT); hint.setGravity(Gravity.CENTER);
        hero.addView(hint, margins(-1, -2, this, 10));
        page.addView(hero);
        pulse = ObjectAnimator.ofPropertyValuesHolder(heroHalo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, .9f, 1.04f), PropertyValuesHolder.ofFloat(View.SCALE_Y, .9f, 1.04f),
            PropertyValuesHolder.ofFloat(View.ALPHA, .55f, 1f));
        pulse.setDuration(1600); pulse.setRepeatMode(ValueAnimator.REVERSE); pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());

        LinearLayout stats = row(this);
        LinearLayout allowedCard = statCard("Разрешено"); allowedValue = (TextView) allowedCard.getChildAt(1); ((TextView) allowedCard.getChildAt(2)).setText("приложений");
        LinearLayout todayCard = statCard("Сегодня"); todayValue = (TextView) todayCard.getChildAt(1); todayCaption = (TextView) todayCard.getChildAt(2);
        allowedCard.setOnClickListener(v -> selectTab(1)); todayCard.setOnClickListener(v -> selectTab(2));
        stats.addView(allowedCard, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams todayParams = new LinearLayout.LayoutParams(0, -2, 1); todayParams.leftMargin = dp(this, 10);
        stats.addView(todayCard, todayParams);
        page.addView(stats, margins(-1, -2, this, 12));

        View pushHint = pushHint(); page.addView(pushHint, margins(-1, -2, this, 12));
        page.addView(adblockCard(), margins(-1, -2, this, 12));

        TextView intro = text(this, "Интернет только у выбранных приложений.\nОдни правила для Wi‑Fi и мобильной сети.", 14, MUTED);
        intro.setLineSpacing(0, 1.2f); page.addView(intro, margins(-1, -2, this, 18));
        TextView choose = button(this, "Выбрать приложения  →", true);
        page.addView(choose, margins(-1, dp(this, 54), this, 16)); choose.setOnClickListener(v -> selectTab(1));
        TextView repair = button(this, "Восстановить соединение", false);
        page.addView(repair, margins(-1, dp(this, 50), this, 10));
        repair.setOnClickListener(v -> {
            if (Rules.enabled(this) && FirewallService.running) startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.REPAIR));
            else Toast.makeText(this, "Сначала включите Firewall", Toast.LENGTH_SHORT).show();
        });
        TextView help = text(this, "Как это работает", 14, MUTED); help.setGravity(Gravity.CENTER); help.setPadding(0, dp(this, 18), 0, dp(this, 12));
        page.addView(help, new LinearLayout.LayoutParams(-1, -2)); help.setOnClickListener(v -> showHelp());
        return scroll;
    }
    private LinearLayout statCard(String caption) {
        LinearLayout card = column(this); card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(ripple(this, outlined(this, CARD, STROKE, R_CARD), R_CARD)); card.setClickable(true);
        card.addView(label(this, caption));
        TextView value = bold(this, "—", 24, WHITE); card.addView(value, margins(-2, -2, this, 8));
        TextView sub = text(this, "", 12, MUTED); card.addView(sub, margins(-2, -2, this, 4));
        return card;
    }
    /** Explains why pushes can be late and offers a one-tap, user-confirmed fix. Never auto-allows. */
    private View pushHint() {
        LinearLayout card = column(this); card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(outlined(this, 0xff1f2416, 0x40d4ff68, R_CARD));
        LinearLayout head = row(this);
        ImageView bell = new ImageView(this); bell.setImageDrawable(icon(this, R.drawable.ic_bell, 20, ACCENT));
        head.addView(bell, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        TextView title = bold(this, "Уведомления могут не приходить", 15, WHITE); title.setPadding(dp(this, 10), 0, 0, 0);
        head.addView(title); card.addView(head);
        TextView body = text(this, "Push почти всех приложений идут через Google Play services. Разреши им интернет, и уведомления разрешённых приложений будут приходить вовремя.", 13, MUTED);
        body.setLineSpacing(0, 1.15f); card.addView(body, margins(-1, -2, this, 8));
        LinearLayout actions = row(this);
        TextView allow = button(this, "Разрешить", true); allow.setTextSize(13);
        actions.addView(allow, new LinearLayout.LayoutParams(-2, dp(this, 38)));
        allow.setPadding(dp(this, 18), 0, dp(this, 18), 0);
        TextView later = text(this, "Скрыть", 13, MUTED); later.setGravity(Gravity.CENTER); later.setPadding(dp(this, 18), 0, dp(this, 18), 0);
        actions.addView(later, new LinearLayout.LayoutParams(-2, dp(this, 38)));
        card.addView(actions, margins(-1, -2, this, 12));
        allow.setOnClickListener(v -> allowPushService());
        later.setOnClickListener(v -> { Rules.prefs(this).edit().putBoolean("push_hint_hidden", true).apply(); updatePushHints(); });
        pushHints.add(card); return card;
    }
    /** Separate from the shield: off leaves every existing firewall path unchanged. */
    private View adblockCard() {
        LinearLayout card = column(this);
        card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(outlined(this, CARD, STROKE, R_CARD));
        LinearLayout head = row(this);
        ImageView mark = new ImageView(this); mark.setImageDrawable(icon(this, R.drawable.ic_block, 22, ACCENT));
        head.addView(mark, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
        LinearLayout titles = column(this); titles.setPadding(dp(this, 10), 0, dp(this, 8), 0);
        titles.addView(bold(this, "Блокировка рекламы", 16, WHITE));
        TextView sub = text(this, "Реклама и трекеры во всех приложениях и браузерах. Обычные сайты открываются как обычно.", 13, MUTED);
        sub.setLineSpacing(0, 1.15f);
        titles.addView(sub, margins(-1, -2, this, 4));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        adblockSwitch = new Switch(this); adblockSwitch.setId(R.id.adblock_switch);
        adblockSwitch.setContentDescription("Блокировка рекламы");
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        adblockSwitch.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{ACCENT, 0xffc5cec0}));
        adblockSwitch.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0x66d4ff68, 0x33ffffff}));
        head.addView(adblockSwitch);
        card.addView(head);
        adblockMeta = text(this, "Выключена", 12, MUTED);
        card.addView(adblockMeta, margins(-1, -2, this, 10));
        TextView update = button(this, "Обновить список", false); update.setTextSize(13);
        update.setOnClickListener(v -> refreshBlocklist(update));
        card.addView(update, margins(-1, dp(this, 40), this, 12));
        adblockHint = text(this, "Частный DNS указывает на свой сервер. Выключите его в настройках Android — иначе фильтр не увидит запросы.", 12, DANGER);
        adblockHint.setLineSpacing(0, 1.15f); adblockHint.setVisibility(View.GONE);
        adblockHint.setOnClickListener(v -> openPrivateDns());
        card.addView(adblockHint, margins(-1, -2, this, 10));
        adblockSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (adblockGuard) return;
            if (checked) requestAdblock(); else applyAdblock(false);
        });
        return card;
    }
    private final Runnable adblockTick = new Runnable() {
        @Override public void run() {
            if (isDestroyed()) return;
            paintAdblock();
            if (resumed && Rules.adblock(MainActivity.this)) status.postDelayed(this, 1000);
        }
    };
    private void paintAdblock() {
        if (adblockSwitch == null) return;
        boolean on = Rules.adblock(this);
        adblockGuard = true; adblockSwitch.setChecked(on); adblockGuard = false;
        if (!on) { adblockMeta.setText("Выключена"); adblockHint.setVisibility(View.GONE); return; }
        adblockMeta.setText("Заблокировано: " + PacketTunnel.blockedQueries.get() + "  ·  " + Blocklist.count() + " доменов, " + Blocklist.origin());
        adblockHint.setVisibility(privateDnsStrict() ? View.VISIBLE : View.GONE);
    }
    private void scheduleAdblockTick() {
        status.removeCallbacks(adblockTick);
        if (resumed && Rules.adblock(this)) status.postDelayed(adblockTick, 1000);
    }
    private boolean privateDnsStrict() {
        return "hostname".equals(Settings.Global.getString(getContentResolver(), "private_dns_mode"));
    }
    private void openPrivateDns() {
        try { startActivity(new Intent("android.settings.PRIVATE_DNS_SETTINGS")); }
        catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (ActivityNotFoundException ignored) { /* the hint text is enough */ }
        }
    }
    private void requestAdblock() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            pendingAdblock = true;
            try { startActivityForResult(consent, 11); }
            catch (ActivityNotFoundException e) {
                pendingAdblock = false;
                FirewallService.error = "Android не показал окно разрешения VPN.";
                updateStatus();
            }
            return;
        }
        applyAdblock(true);
    }
    private void applyAdblock(boolean on) {
        Rules.adblock(this, on);
        if (on) Rules.interrupted(this, false);
        try {
            if (on || Rules.enabled(this)) startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.FILTER));
            else startService(new Intent(this, FirewallService.class).setAction(FirewallService.STOP));
        } catch (RuntimeException e) {
            Rules.adblock(this, false);
            FirewallService.error = "Не удалось запустить блокировку рекламы.";
        }
        updateStatus();
    }
    private void refreshBlocklist(TextView button) {
        button.setEnabled(false); button.setText("Обновление…");
        loader.execute(() -> {
            String msg = Blocklist.update(this);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                button.setEnabled(true); button.setText("Обновить список");
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                paintAdblock();
            });
        });
    }
    private String[] pushPackages() {
        try {
            PackageManager pm = getPackageManager();
            String[] shared = pm.getPackagesForUid(pm.getApplicationInfo(Rules.PUSH_SERVICE, 0).uid);
            return shared != null ? shared : new String[]{Rules.PUSH_SERVICE};
        } catch (PackageManager.NameNotFoundException e) { return null; }
    }
    private void allowPushService() {
        String[] packages = pushPackages(); if (packages == null) return;
        allowed.addAll(Arrays.asList(packages)); rulesChanged();
        Toast.makeText(this, "Google Play services: интернет разрешён", Toast.LENGTH_SHORT).show();
    }
    private void updatePushHints() {
        boolean show = !Rules.prefs(this).getBoolean("push_hint_hidden", false) && pushPackages() != null && !allowed.contains(Rules.PUSH_SERVICE);
        for (View v : pushHints) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------- Apps ----------
    private View buildAppsPage() {
        LinearLayout page = column(this); page.setPadding(dp(this, 16), dp(this, 18), dp(this, 16), 0);
        TextView title = bold(this, "Приложения", 26, WHITE); page.addView(title);
        TextView subtitle = text(this, "Отметь приложения с доступом в интернет", 13, MUTED);
        page.addView(subtitle, margins(-1, -2, this, 6));
        View hint = pushHint(); page.addView(hint, margins(-1, -2, this, 14));

        LinearLayout searchBox = row(this); searchBox.setPadding(dp(this, 14), 0, dp(this, 6), 0);
        searchBox.setBackground(outlined(this, CARD, STROKE, R_CONTROL));
        ImageView glass = new ImageView(this); glass.setImageDrawable(icon(this, R.drawable.ic_search, 20, MUTED));
        searchBox.addView(glass, new LinearLayout.LayoutParams(dp(this, 20), dp(this, 20)));
        search = new EditText(this); search.setId(R.id.app_search); search.setSingleLine(true); search.setTextSize(15); search.setTextColor(WHITE);
        search.setHintTextColor(MUTED); search.setHint("Поиск приложений"); search.setBackground(null);
        search.setPadding(dp(this, 10), 0, dp(this, 4), 0); search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        TextView clear = text(this, "✕", 15, MUTED); clear.setGravity(Gravity.CENTER); clear.setContentDescription("Очистить поиск");
        clear.setVisibility(View.GONE); clear.setOnClickListener(v -> search.setText(""));
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(this, 36), -1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE); }
            public void afterTextChanged(Editable e) {}
        });
        page.addView(searchBox, margins(-1, dp(this, 48), this, 14));

        appFilter = new ChipGroup(this, "Все", "Обычные", "Системные", "Разрешённые");
        appFilter.setId(R.id.app_filter); appFilter.setContentDescription("Фильтр приложений");
        page.addView(appFilter, margins(-1, -2, this, 12));

        LinearLayout toolbar = row(this);
        count = text(this, "Загрузка…", 12, MUTED);
        toolbar.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        allowedBadge = bold(this, "", 12, ACCENT); allowedBadge.setPadding(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        allowedBadge.setBackground(round(this, ACCENT_SOFT, R_PILL)); toolbar.addView(allowedBadge);
        page.addView(toolbar, margins(-1, -2, this, 12));

        ListView list = new ListView(this); list.setId(R.id.app_list); list.setDivider(null); list.setDividerHeight(dp(this, 8));
        list.setSelector(new android.graphics.drawable.ColorDrawable(0)); list.setClipToPadding(false);
        list.setPadding(0, dp(this, 10), 0, dp(this, 12)); list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(BG); adapter = new AppAdapter(); list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    // ---------- Navigation ----------
    private LinearLayout navButton(String label, int icon, int tab) {
        LinearLayout button = column(this); button.setGravity(Gravity.CENTER);
        ImageView image = new ImageView(this); image.setImageDrawable(getDrawable(icon).mutate());
        button.addView(image, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
        TextView text = bold(this, label, 12, MUTED); text.setGravity(Gravity.CENTER);
        button.addView(text, margins(-2, -2, this, 4));
        button.setContentDescription("Вкладка " + label); button.setClickable(true); button.setFocusable(true);
        button.setOnClickListener(v -> selectTab(tab)); return button;
    }
    private void selectTab(int tab) {
        int next = tab >= 0 && tab <= 2 ? tab : 0;
        boolean changed = next != currentTab; currentTab = next;
        firewallPage.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
        appsPage.setVisibility(currentTab == 1 ? View.VISIBLE : View.GONE);
        trafficPage.setVisibility(currentTab == 2 ? View.VISIBLE : View.GONE);
        if (changed) fadeIn(currentTab == 0 ? firewallPage : currentTab == 1 ? appsPage : trafficPage);
        styleTab(firewallTab, currentTab == 0); styleTab(appsTab, currentTab == 1); styleTab(trafficTab, currentTab == 2);
        if (resumed && currentTab == 2) trafficPage.resume(); else trafficPage.pause();
        Rules.prefs(this).edit().putInt("tab", currentTab).apply();
        if (currentTab != 1) {
            search.clearFocus();
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }
    private void styleTab(LinearLayout tab, boolean selected) {
        tab.setSelected(selected);
        tab.setBackground(ripple(this, round(this, selected ? ACCENT_SOFT : 0, 20), 20));
        ((ImageView) tab.getChildAt(0)).getDrawable().setTint(selected ? ACCENT : MUTED);
        ((TextView) tab.getChildAt(1)).setTextColor(selected ? ACCENT : MUTED);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", currentTab); state.putString("query", search.getText().toString());
        state.putInt("filter", appFilter.getSelectedItemPosition()); state.putBoolean("pending_adblock", pendingAdblock);
        super.onSaveInstanceState(state);
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
        if (Rules.enabled(this) && !Rules.interrupted(this) && !FirewallService.running) {
            if (VpnService.prepare(this) == null) startFirewall();
            else { Rules.interrupted(this, true); FirewallService.error = "Нет разрешения на VPN. Включите firewall ещё раз."; }
        } else if (Rules.adblock(this) && !Rules.interrupted(this) && !FirewallService.running && VpnService.prepare(this) == null) {
            applyAdblock(true);
        }
        updateStatus(); loadApps(); loadToday();
    }

    private void requestStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            try { startActivityForResult(consent, 10); }
            catch (ActivityNotFoundException e) { FirewallService.error = "Android не показал окно разрешения VPN."; updateStatus(); }
            return;
        }
        startFirewall();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 11) { pendingAdblock = false; if (result == RESULT_OK) applyAdblock(true); else updateStatus(); return; }
        if (request == 10 && result == RESULT_OK) startFirewall(); else updateStatus();
    }
    private void startFirewall() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        Rules.enabled(this, true); Rules.interrupted(this, false);
        FirewallService.error = "";
        try { startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.START)); }
        catch (RuntimeException e) { Rules.enabled(this, false); FirewallService.error = "Не удалось запустить firewall."; }
        updateStatus();
    }
    private void stopFirewall() {
        Rules.enabled(this, false); Rules.interrupted(this, false); FirewallService.error = "";
        startService(new Intent(this, FirewallService.class).setAction(FirewallService.STOP)); updateStatus();
    }
    private boolean desired() { return Rules.enabled(this) && !Rules.interrupted(this); }
    private void updateStatus() {
        boolean desired = desired(), on = desired && FirewallService.running && !FirewallService.updating;
        String error = FirewallService.error;
        status.setText(!error.isEmpty() ? error : desired ? (FirewallService.updating ? "Применение правил…" : FirewallService.running ? "Включён · остальные приложения без интернета" : "Запуск…") : "Выключен · доступ есть у всех приложений");
        status.setTextColor(!error.isEmpty() ? DANGER : desired ? ACCENT : MUTED);
        stateTitle.setText(desired ? (FirewallService.running ? "Защита включена" : "Включение…") : "Защита выключена");
        int tone = desired ? ACCENT : !error.isEmpty() ? DANGER : FAINT;
        GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL);
        ring.setColor(desired ? 0xff20271a : CARD_HI); ring.setStroke(dp(this, 3), tone);
        heroRing.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), ring, null));
        GradientDrawable halo = new GradientDrawable(); halo.setShape(GradientDrawable.OVAL);
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT); halo.setGradientRadius(dp(this, 105));
        halo.setColors(new int[]{(tone & 0x00ffffff) | (desired ? 0x66000000 : 0x22000000), (tone & 0x00ffffff)});
        heroHalo.setBackground(halo);
        heroIcon.setImageDrawable(icon(this, desired ? R.drawable.ic_shield : R.drawable.ic_power, 64, desired ? ACCENT : WHITE));
        if (desired) heroIcon.getDrawable().setTintList(null);
        heroRing.setContentDescription("Firewall " + (desired ? "включён, нажмите, чтобы выключить" : "выключен, нажмите, чтобы включить"));
        if (on && !pulse.isStarted()) pulse.start();
        else if (!on && pulse.isStarted()) { pulse.cancel(); heroHalo.setScaleX(1); heroHalo.setScaleY(1); heroHalo.setAlpha(desired ? .8f : .5f); }
        updatePushHints();
        paintAdblock();
        scheduleAdblockTick();
    }
    private void loadToday() {
        loader.execute(() -> {
            boolean access = TrafficMonitor.hasAccess(this);
            long total = -1;
            if (access) {
                TrafficMonitor.Snapshot today = TrafficMonitor.read(this, TrafficMonitor.startOfPeriod(0), System.currentTimeMillis());
                if (today.networks > 0) total = today.rx + today.tx;
            }
            long value = total;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                todayValue.setText(value >= 0 ? TrafficMonitor.format(value) : "—");
                todayCaption.setText(access ? "трафик устройства" : "нет доступа к статистике");
            });
        });
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
        int mode = appFilter.getSelectedItemPosition();
        for (AppGroup g : all) {
            boolean selected = selected(g); if (selected) n++;
            boolean included = mode == 0 || (mode == 1 ? (g.user || g.launchable) : mode == 2 ? !g.user : selected);
            if (included && (g.label + " " + g.details).toLowerCase(Locale.ROOT).contains(q)) visible.add(g);
        }
        count.setText("В списке: " + visible.size() + " / " + all.size());
        allowedBadge.setText("Разрешено: " + n);
        allowedValue.setText(String.valueOf(all.isEmpty() ? countAllowedPackages() : n));
        adapter.notifyDataSetChanged();
    }
    private int countAllowedPackages() {
        HashSet<Integer> uids = new HashSet<>();
        for (String p : allowed) try { uids.add(getPackageManager().getApplicationInfo(p, 0).uid); } catch (PackageManager.NameNotFoundException ignored) {}
        return uids.size();
    }

    private void toggle(AppGroup g, boolean checked) {
        if (checked) allowed.addAll(g.packages); else allowed.removeAll(g.packages);
        rulesChanged();
    }
    private void rulesChanged() {
        Rules.save(this, allowed);
        if (Rules.enabled(this) && FirewallService.running) {
            try { startForegroundService(new Intent(this, FirewallService.class).setAction(FirewallService.UPDATE)); }
            catch (RuntimeException e) { FirewallService.error = "Правила не обновлены. Перезапустите firewall."; }
        }
        filter(); updateStatus();
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("SimpleFirewall")
            .setMessage("Отметьте приложения и включите firewall. Новые приложения по умолчанию заблокированы. Настройки сохраняются.\n\nРаботает локально через VPN Android, без серверов, рекламы и сбора данных. Самому приложению интернет не нужен. Другой VPN одновременно использовать нельзя: если он займёт место, придёт уведомление «Firewall OFF» с кнопкой включения.\n\nPush-уведомления большинства приложений доставляют Google Play services. Если они заблокированы, уведомления разрешённых приложений могут не приходить.\n\nУ приложений с общим UID доступ общий — они объединены в одну строку. Фильтруется трафик приложений текущего профиля, IPv4 и IPv6. Раздача интернета и другие профили не фильтруются.\n\nПосле перезагрузки firewall включается сам, если был включён. Для надёжности можно выбрать SimpleFirewall как «Постоянную VPN» в настройках Android. Режим «Блокировать соединения без VPN» несовместим: выбранные приложения выходят в интернет напрямую.\n\nБлокировка рекламы — отдельный переключатель, по умолчанию выключен. Рекламные домены получают пустой адрес, остальные DNS-запросы проходят как обычно. Список StevenBlack уже в приложении. Рекламу YouTube и Instagram DNS не отличает: она идёт с того же домена, что и само видео. Частный DNS с указанием сервера нужно выключить, иначе Android обходит фильтр.")
            .setPositiveButton("Понятно", null).show();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int pos) { return visible.get(pos); }
        @Override public long getItemId(int pos) { return visible.get(pos).info.uid; }
        @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
            Row row;
            if (recycled == null) {
                row = new Row(); LinearLayout box = row(MainActivity.this);
                box.setPadding(dp(MainActivity.this, 12), dp(MainActivity.this, 12), dp(MainActivity.this, 14), dp(MainActivity.this, 12));
                row.icon = new ImageView(MainActivity.this); box.addView(row.icon, new LinearLayout.LayoutParams(dp(MainActivity.this, 40), dp(MainActivity.this, 40)));
                LinearLayout labels = column(MainActivity.this); labels.setPadding(dp(MainActivity.this, 14), 0, dp(MainActivity.this, 10), 0);
                row.title = bold(MainActivity.this, "", 15, WHITE); row.title.setMaxLines(2); row.title.setEllipsize(TextUtils.TruncateAt.END); labels.addView(row.title);
                row.detail = text(MainActivity.this, "", 11, MUTED); row.detail.setSingleLine(); row.detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                labels.addView(row.detail, margins(-1, -2, MainActivity.this, 4));
                box.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.check = new ImageView(MainActivity.this); int pad = dp(MainActivity.this, 3); row.check.setPadding(pad, pad, pad, pad);
                row.check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                box.addView(row.check, new LinearLayout.LayoutParams(dp(MainActivity.this, 26), dp(MainActivity.this, 26)));
                box.setTag(row); recycled = box;
            } else row = (Row) recycled.getTag();
            AppGroup g = visible.get(position); boolean on = selected(g);
            row.title.setText(g.label); row.detail.setText(g.details);
            icons.load(row.icon, g.info.packageName);
            row.check.setBackground(on ? round(MainActivity.this, ACCENT, 8) : outlined(MainActivity.this, 0, FAINT, 8));
            row.check.setImageDrawable(on ? icon(MainActivity.this, R.drawable.ic_check, 20, ON_ACCENT) : null);
            recycled.setBackground(ripple(MainActivity.this, on ? outlined(MainActivity.this, 0xff1f2519, 0x40d4ff68, 16) : round(MainActivity.this, CARD, 16), 16));
            recycled.setContentDescription(g.label + (on ? ", интернет разрешён" : ", интернет запрещён"));
            recycled.setOnClickListener(v -> toggle(g, !selected(g))); return recycled;
        }
    }
    private static class Row { ImageView icon, check; TextView title, detail; }
    private static class AppGroup {
        final ArrayList<String> packages = new ArrayList<>(); final TreeSet<String> labels = new TreeSet<>();
        String label, details; boolean user, network, launchable; ApplicationInfo info;
    }
    @Override protected void onPause() { resumed = false; status.removeCallbacks(adblockTick); trafficPage.pause(); super.onPause(); }
    @Override protected void onDestroy() { pulse.cancel(); trafficPage.close(); unregisterReceiver(updates); loader.shutdown(); super.onDestroy(); }
}
