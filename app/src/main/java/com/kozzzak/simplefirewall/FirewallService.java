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
            handler.removeCallbacks(repairNetwork); handler.postDelayed(repairNetwork, 750);
        }
    }
    private String physicalStamp() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TreeSet<String> state = new TreeSet<>();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            LinkProperties links = cm.getLinkProperties(n);
            state.add(n.toString() + ":" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                + ":" + (links == null ? "" : links.getDnsServers().toString() + links.getLinkAddresses().toString()));
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
            error = ""; shutdown(); return START_NOT_STICKY;
        }
        // A queued START after STOP must restore the latest requested state.
        // UPDATE/REPAIR never turn on a firewall the user has switched off.
        if (intent != null && !UPDATE.equals(action) && !REPAIR.equals(action)) Rules.enabled(this, true);
        if (!Rules.enabled(this)) { shutdown(); return START_NOT_STICKY; }
        startForeground(1, notification());
        if (prepare(this) != null) {
            fail("Нет разрешения на VPN. Включите firewall ещё раз.");
            return START_NOT_STICKY;
        }
        if (UPDATE.equals(action) && running) {
            updating = true; changed();
            handler.removeCallbacks(updateRules); handler.postDelayed(updateRules, 250);
        } else establishRules(REPAIR.equals(action));
        return Rules.enabled(this) ? START_STICKY : START_NOT_STICKY;
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
            } else fail("Не удалось включить firewall: " + e.getClass().getSimpleName());
        }
    }

    private void fail(String message) {
        error = message;
        shutdown();
    }
    private void closeTunnel() {
        if (tunnel != null) try { tunnel.close(); } catch (IOException ignored) {}
        tunnel = null;
        running = false; updating = false; lastAllowed = null; appliedPackages = Collections.emptySet();
    }
    private void shutdown() {
        handler.removeCallbacks(updateRules); handler.removeCallbacks(repairNetwork);
        Rules.enabled(this, false);
        // The system is bound to VpnService while the TUN exists. stopSelf()
        // alone does not trigger onDestroy, so close the connection explicitly.
        closeTunnel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        changed();
    }
    private void changed() { sendBroadcast(new Intent(STATUS).setPackage(getPackageName())); }

    @Override public void onRevoke() {
        new android.os.Handler(getMainLooper()).post(() -> {
            error = "Firewall отключён системой или другим VPN.";
            shutdown();
        });
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
