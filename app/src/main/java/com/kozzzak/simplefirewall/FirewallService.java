package com.kozzzak.simplefirewall;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.util.Set;

public class FirewallService extends VpnService {
    static final String STOP = "com.kozzzak.simplefirewall.STOP";
    static final String STATUS = "com.kozzzak.simplefirewall.STATUS";
    static volatile boolean running;
    static volatile long appliedGeneration;
    static volatile String error = "";
    private ParcelFileDescriptor tunnel;
    private boolean registered;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Set<String> allowed = Rules.allowed(c);
                if (i.getData() != null) allowed.remove(i.getData().getSchemeSpecificPart());
                Rules.save(c, allowed);
            }
            if (Rules.enabled(c)) establishRules();
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
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Rules.enabled(this)) {
            error = "";
            shutdown();
            return START_NOT_STICKY;
        }
        startForeground(1, notification());
        if (prepare(this) != null) {
            fail("Нет разрешения на VPN. Включите firewall ещё раз.");
            return START_NOT_STICKY;
        }
        establishRules();
        return running ? START_STICKY : START_NOT_STICKY;
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

    private void establishRules() {
        try {
            // Selected applications bypass this local sink. Everything else enters
            // both default routes and is dropped. Never call allowBypass().
            Builder builder = new Builder().setSession("SimpleFirewall").setMtu(1500)
                .addAddress("10.111.0.1", 32).addRoute("0.0.0.0", 0)
                .addAddress("fd42:7366::1", 128).addRoute("::", 0)
                .addDnsServer("192.0.2.1");
            builder.setMetered(false);
            builder.setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            for (String name : Rules.allowed(this)) {
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
            running = true;
            appliedGeneration++;
            error = "";
            changed();
        } catch (Exception e) {
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
        running = false;
    }
    private void shutdown() {
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
        closeTunnel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        changed();
        super.onDestroy();
    }
}
