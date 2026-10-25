package com.kozzzak.simplefirewall;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.Handler;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.*;

public class FirewallService extends VpnService {
    static final String START = "com.kozzzak.simplefirewall.START";
    static final String UPDATE = "com.kozzzak.simplefirewall.UPDATE";
    static final String REPAIR = "com.kozzzak.simplefirewall.REPAIR";
    static final String STOP = "com.kozzzak.simplefirewall.STOP";
    static final String STATUS = "com.kozzzak.simplefirewall.STATUS";
    static final String ENABLE = "com.kozzzak.simplefirewall.ENABLE";
    private static final int ONGOING_ID = 1, OFF_ID = 2;
    /** Debounce for physical network switches; address/DNS churn never rebuilds the VPN. */
    private static final long NETWORK_SETTLE_MS = 3000;
    static volatile boolean running, updating;
    static volatile Set<String> appliedPackages = Collections.emptySet();
    static volatile long appliedGeneration;
    static volatile String error = "";
    private ParcelFileDescriptor tunnel;
    private boolean registered, networkRegistered;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private Set<String> lastAllowed;
    private String topology = "";
    private final Runnable updateRules = () -> { if (Rules.enabled(this)) establishRules(false); };
    private final Runnable repairNetwork = () -> { if (Rules.enabled(this) && running) establishRules(true); };
    private final ConnectivityManager.NetworkCallback physicalNetworks = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network n) { handler.post(() -> physicalChanged()); }
        public void onLost(Network n) { handler.post(() -> physicalChanged()); }
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { handler.post(() -> physicalChanged()); }
        public void onLinkPropertiesChanged(Network n, LinkProperties p) { handler.post(() -> physicalChanged()); }
    };
    private void physicalChanged() {
        String current = physicalStamp();
        if (current.equals(topology)) return;
        topology = current;
        if (Rules.enabled(this) && running) {
            handler.removeCallbacks(repairNetwork); handler.postDelayed(repairNetwork, NETWORK_SETTLE_MS);
        }
    }
    /**
     * Identity of the physical networks only. IPv6 privacy addresses, DNS
     * updates, validation and signal changes happen constantly and must not
     * recreate the TUN: every rebuild is churn for the system and for
     * long-lived connections such as push. The TUN follows the default
     * network by itself (setUnderlyingNetworks(null)); selected apps bypass it.
     */
    private String physicalStamp() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TreeSet<Long> state = new TreeSet<>();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            state.add(n.getNetworkHandle());
        }
        return state.toString();
    }

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Set<String> allowed = Rules.allowed(c);
                if (i.getData() != null) allowed.remove(i.getData().getSchemeSpecificPart());
                Rules.save(c, allowed);
            }
            if (Rules.enabled(c)) { handler.removeCallbacks(updateRules); handler.postDelayed(updateRules, 250); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("firewall", "Firewall", NotificationManager.IMPORTANCE_LOW));
        createAlertChannel(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, filter);
        registered = true;
        topology = physicalStamp();
        try {
            getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), physicalNetworks);
            networkRegistered = true;
        } catch (RuntimeException ignored) { /* Android still tracks the default underlying network. */ }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) {
            error = ""; Rules.interrupted(this, false); shutdown(true); return START_NOT_STICKY;
        }
        // Every other command may come from startForegroundService(). Satisfy
        // the foreground contract first, before any exit path, or Android
        // kills the app for not calling startForeground() in time.
        try { startForeground(ONGOING_ID, notification()); }
        catch (RuntimeException e) { /* background start refused; the checks below still run */ }
        // A queued START after STOP must restore the latest requested state.
        // UPDATE/REPAIR never turn on a firewall the user has switched off.
        // Explicit START and the system's always-on start (android.net.VpnService).
        boolean explicit = intent != null && !UPDATE.equals(action) && !REPAIR.equals(action);
        if (explicit) {
            Rules.enabled(this, true); Rules.interrupted(this, false);
            getSystemService(NotificationManager.class).cancel(OFF_ID);
        }
        // START_STICKY restart (null intent): resume only if nothing else took the VPN.
        if (!Rules.enabled(this) || (!explicit && Rules.interrupted(this) && !running)) {
            shutdown(false); return START_NOT_STICKY;
        }
        if (prepare(this) != null) {
            interrupt("Нет разрешения на VPN. Включите firewall ещё раз.");
            return START_NOT_STICKY;
        }
        if (UPDATE.equals(action) && running) {
            updating = true; changed();
            handler.removeCallbacks(updateRules); handler.postDelayed(updateRules, 250);
        } else establishRules(REPAIR.equals(action) || !running);
        return START_STICKY;
    }

    static void createAlertChannel(Context c) {
        NotificationChannel alerts = new NotificationChannel("alerts", "Firewall выключен", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Предупреждение, когда защита отключилась не по вашей команде");
        c.getSystemService(NotificationManager.class).createNotificationChannel(alerts);
    }
    /** High-priority warning that blocking stopped, with a one-tap re-enable. */
    static void notifyOff(Context c, String text) {
        createAlertChannel(c);
        Intent enable = new Intent(c, MainActivity.class).setAction(ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(c, 3, enable, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, "alerts")
            .setSmallIcon(R.drawable.ic_shield).setContentTitle("Firewall OFF")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text + "\nСейчас интернет доступен всем приложениям."))
            .setCategory(Notification.CATEGORY_ERROR).setColor(0xffff8f7a).setAutoCancel(true).setContentIntent(pending)
            .addAction(new Notification.Action.Builder(null, "Включить", pending).build()).build();
        try { c.getSystemService(NotificationManager.class).notify(OFF_ID, n); }
        catch (SecurityException ignored) { /* notifications not permitted */ }
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FirewallService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "firewall")
            .setSmallIcon(R.drawable.ic_shield).setContentTitle("Firewall включён")
            .setContentText("Интернет только у выбранных приложений")
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Выключить", stop).build()).build();
    }

    private void establishRules(boolean force) {
        if (!Rules.enabled(this)) return;
        Set<String> allowed = Rules.allowed(this);
        if (!force && tunnel != null && allowed.equals(lastAllowed)) {
            updating = false; error = ""; changed(); return;
        }
        updating = true; changed();
        try {
            // Selected applications bypass this local sink. Everything else enters
            // both default routes and is dropped. Never call allowBypass().
            Builder builder = new Builder().setSession("SimpleFirewall").setMtu(1500)
                // Inherit meteredness from the real network instead of the
                // Android 10+ default "always metered" for VPNs.
                .setMetered(false)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd42:7366::1", 128).addRoute("::", 0);
            builder.setUnderlyingNetworks(null);
            // Use the real physical DNS configuration. DNS from blocked UIDs
            // still enters the sink; selected UIDs use their physical network.
            LinkedHashSet<java.net.InetAddress> dns = new LinkedHashSet<>();
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                LinkProperties links = cm.getLinkProperties(n);
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && links != null)
                    dns.addAll(links.getDnsServers());
            }
            if (dns.isEmpty()) builder.addDnsServer("192.0.2.1");
            else for (java.net.InetAddress server : dns) builder.addDnsServer(server);
            builder.setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            for (String name : allowed) {
                try { builder.addDisallowedApplication(name); }
                catch (PackageManager.NameNotFoundException ignored) { /* not installed */ }
            }
            ParcelFileDescriptor next = builder.establish();
            if (next == null) throw new IOException("VPN не запущен");
            ParcelFileDescriptor previous = tunnel;
            tunnel = next;
            if (previous != null) try { previous.close(); } catch (IOException ignored) {}
            // Retain the TUN descriptor without forwarding or polling packets.
            // No remote endpoints, packet logging, wakelocks or userspace TCP stack.
            lastAllowed = new HashSet<>(allowed);
            appliedPackages = Collections.unmodifiableSet(new HashSet<>(allowed));
            running = true; updating = false;
            appliedGeneration++;
            error = "";
            changed();
        } catch (Exception e) {
            updating = false;
            if (tunnel != null) {
                // Keep the previous blocking policy on a failed rules update.
                error = "Правила не обновлены. Выключите и включите firewall.";
                changed();
            } else interrupt("Не удалось включить firewall: " + e.getClass().getSimpleName());
        }
    }

    /** Blocking stopped against the user's wish: keep the intent, warn loudly. */
    private void interrupt(String message) {
        error = message;
        Rules.interrupted(this, true);
        notifyOff(this, message);
        shutdown(false);
    }
    private void closeTunnel() {
        if (tunnel != null) try { tunnel.close(); } catch (IOException ignored) {}
        tunnel = null;
        running = false; updating = false; lastAllowed = null; appliedPackages = Collections.emptySet();
    }
    private void shutdown(boolean userStop) {
        handler.removeCallbacks(updateRules); handler.removeCallbacks(repairNetwork);
        if (userStop) Rules.enabled(this, false);
        // The system is bound to VpnService while the TUN exists. stopSelf()
        // alone does not trigger onDestroy, so close the connection explicitly.
        closeTunnel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        changed();
    }
    private void changed() { sendBroadcast(new Intent(STATUS).setPackage(getPackageName())); }

    @Override public void onRevoke() {
        // Another VPN took the slot or the user disconnected it in Settings.
        // The enabled flag stays: this was not a command from our UI.
        handler.post(() -> interrupt("Firewall отключён системой или другим VPN."));
    }
    @Override public void onDestroy() {
        if (registered) unregisterReceiver(packages);
        if (networkRegistered) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(physicalNetworks);
        handler.removeCallbacksAndMessages(null);
        closeTunnel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        changed();
        super.onDestroy();
    }
}
