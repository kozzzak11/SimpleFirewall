package com.kozzzak.simplefirewall;

import android.content.*;
import android.net.*;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FirewallTest {
    private static final String PROBE = "com.kozzzak.simplefirewall.probe";
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private MainActivity screen;
    private volatile boolean lastProbeVpn;
    private final Context context = instrumentation.getTargetContext();

    private void command(String value) throws Exception {
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(value);
             FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            byte[] buf = new byte[1024]; while (input.read(buf) != -1) { /* wait for command */ }
        }
    }
    private void setRules(java.util.Set<String> packages) throws Exception {
        long previous = FirewallService.appliedGeneration;
        Rules.save(context, packages); Rules.enabled(context, true);
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, FirewallService.class).setAction(FirewallService.UPDATE)));
        instrumentation.waitForIdleSync();
        long end = System.currentTimeMillis() + 6000;
        while ((!FirewallService.running || FirewallService.appliedGeneration <= previous)
            && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(FirewallService.error, FirewallService.running);
        assertTrue("New rules were not acknowledged", FirewallService.appliedGeneration > previous);
    }
    private boolean connects() { return connects(false); }
    private boolean connects(boolean bind) {
        String request = java.util.UUID.randomUUID().toString();
        CountDownLatch reply = new CountDownLatch(1);
        AtomicBoolean reachable = new AtomicBoolean();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (request.equals(i.getStringExtra("request"))) {
                    reachable.set(i.getBooleanExtra("reachable", false)); lastProbeVpn = i.getBooleanExtra("vpn", false); reply.countDown();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter("com.kozzzak.simplefirewall.PROBE_RESULT"),
            "com.kozzzak.simplefirewall.INTERNAL", null, Context.RECEIVER_EXPORTED);
        try {
            context.sendBroadcast(new Intent().setComponent(new ComponentName(PROBE, PROBE + ".ProbeReceiver"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("request", request).putExtra("bind", bind));
            assertTrue("Separate probe app did not reply", reply.await(8, TimeUnit.SECONDS));
            return reachable.get();
        } catch (InterruptedException e) { throw new AssertionError(e); }
        finally { context.unregisterReceiver(receiver); }
    }
    private void expectReachable() throws Exception {
        for (int i = 0; i < 5; i++) { if (connects() && !lastProbeVpn) return; Thread.sleep(100); }
        fail("Allowed app cannot reach local test server");
    }
    private void expectBlocked(String message) {
        // ConnectivityService installs the new agent's UID routes asynchronously
        // after establish() returns. Check eventual application within a bound,
        // then verify a second fresh connection remains blocked.
        long deadline = System.currentTimeMillis() + 6000;
        boolean reachable;
        do { reachable = connects(); }
        while (reachable && System.currentTimeMillis() < deadline);
        assertFalse(message, reachable);
        assertFalse("Blocking policy did not remain active", connects());
    }

    @Before public void prepare() throws Exception {
        command("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
        screen = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        expectReachable();
    }
    @After public void cleanup() {
        context.getSystemService(ConnectivityManager.class).bindProcessToNetwork(null);
        Rules.enabled(context, false);
        context.startService(new Intent(context, FirewallService.class).setAction(FirewallService.STOP));
        Rules.save(context, Collections.emptySet());
    }

    @Test public void defaultDenyAllowThenRevoke() throws Exception {
        setRules(Collections.emptySet());
        expectBlocked("Unselected app still has IPv4 access after policy application");

        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        boolean v4 = false, v6 = false;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties props = cm.getLinkProperties(network);
            if (props == null) continue;
            for (RouteInfo route : props.getRoutes()) {
                if (route.getDestination().toString().equals("0.0.0.0/0")) v4 = true;
                if (route.getDestination().getPrefixLength() == 0 && route.getDestination().getAddress() instanceof Inet6Address) v6 = true;
            }
        }
        assertTrue("Missing IPv4 sink default route", v4);
        assertTrue("Missing IPv6 sink default route", v6);

        // Probe an ordinary app UID: the VPN owner's UID has protect privileges.
        assertFalse("Physical-network binding bypassed firewall", connects(true));
        setRules(Collections.singleton(PROBE));
        expectReachable();
        // Repeated UI/update commands must leave a permitted app usable.
        for (int i = 0; i < 3; i++) {
            instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, FirewallService.class).setAction(FirewallService.UPDATE)));
            Thread.sleep(350); expectReachable();
        }
        long beforeRepair = FirewallService.appliedGeneration;
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, FirewallService.class).setAction(FirewallService.REPAIR)));
        long repairBy = System.currentTimeMillis() + 6000;
        while (FirewallService.appliedGeneration <= beforeRepair && System.currentTimeMillis() < repairBy) Thread.sleep(50);
        assertTrue("Repair was not applied", FirewallService.appliedGeneration > beforeRepair);
        expectReachable();
        setRules(Collections.emptySet());
        expectBlocked("Revoked app still has Internet after policy application");
        Rules.enabled(context, false);
        instrumentation.runOnMainSync(() -> context.startService(new Intent(context, FirewallService.class).setAction(FirewallService.STOP)));
        instrumentation.waitForIdleSync();
        // startService dispatches asynchronously; the UI-thread idle barrier
        // can precede delivery of the service command from system_server.
        long stoppedBy = System.currentTimeMillis() + 6000;
        while (FirewallService.running && System.currentTimeMillis() < stoppedBy) Thread.sleep(50);
        assertFalse("Firewall still reports active after STOP", FirewallService.running);
        expectReachable();
    }
    @Test public void fullListAndSearchRespectSameFilter() throws Exception {
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.Set<Long> expected = new java.util.HashSet<>();
        java.util.List<android.content.pm.PackageInfo> installed = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS);
        java.util.Map<Integer, Boolean> ordinary = new java.util.HashMap<>();
        for (android.content.pm.PackageInfo info : installed) {
            if (info.applicationInfo == null || info.packageName.equals(context.getPackageName())) continue;
            int uid = info.applicationInfo.uid;
            boolean user = (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                || pm.getLaunchIntentForPackage(info.packageName) != null;
            ordinary.put(uid, ordinary.getOrDefault(uid, false) || user);
            if (info.requestedPermissions != null && java.util.Arrays.asList(info.requestedPermissions).contains(android.Manifest.permission.INTERNET))
                expected.add((long) uid);
        }
        java.util.concurrent.atomic.AtomicReference<java.util.Set<Long>> displayed = new java.util.concurrent.atomic.AtomicReference<>();
        long loadedBy = System.currentTimeMillis() + 10000;
        do {
            instrumentation.runOnMainSync(() -> {
                android.widget.Spinner filter = screen.findViewById(R.id.app_filter);
                assertEquals("Default list must include system apps", 0, filter.getSelectedItemPosition());
                android.widget.ListView list = screen.findViewById(R.id.app_list);
                java.util.Set<Long> ids = new java.util.HashSet<>();
                for (int i = 0; i < list.getCount(); i++) ids.add(list.getAdapter().getItemId(i));
                displayed.set(ids);
            });
            if (displayed.get().equals(expected)) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() < loadedBy);
        assertEquals("Installed network-capable UID missing from default list", expected, displayed.get());
        String hidden = null;
        for (android.content.pm.PackageInfo info : installed) {
            if (info.applicationInfo != null && expected.contains((long) info.applicationInfo.uid)
                && !ordinary.getOrDefault(info.applicationInfo.uid, false)) { hidden = info.packageName; break; }
        }
        assertNotNull("Emulator needs a system network app for the regression", hidden);
        final String query = hidden;
        instrumentation.runOnMainSync(() -> {
            android.widget.EditText search = screen.findViewById(R.id.app_search);
            search.setText(query);
            android.widget.ListView list = screen.findViewById(R.id.app_list);
            assertTrue("Search must find system app in All", list.getCount() > 0);
            ((android.widget.Spinner) screen.findViewById(R.id.app_filter)).setSelection(1);
        });
        instrumentation.waitForIdleSync();
        Thread.sleep(200);
        instrumentation.runOnMainSync(() -> {
            android.widget.ListView list = screen.findViewById(R.id.app_list);
            assertEquals("Search must not override Ordinary filter", 0, list.getCount());
            ((android.widget.Spinner) screen.findViewById(R.id.app_filter)).setSelection(0);
            ((android.widget.EditText) screen.findViewById(R.id.app_search)).setText("");
        });
    }

    @Test public void trafficPermissionAndRealUidAccounting() throws Exception {
        command("appops set " + context.getPackageName() + " GET_USAGE_STATS deny");
        assertFalse(TrafficMonitor.hasAccess(context));
        TrafficMonitor.Snapshot denied = TrafficMonitor.read(context, System.currentTimeMillis() - 86400000, System.currentTimeMillis());
        assertEquals(0, denied.networks);
        assertFalse(denied.error.isEmpty());
        try {
            command("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
            assertTrue(TrafficMonitor.hasAccess(context));
            expectReachable();
            command("dumpsys netstats --poll");
            int uid = context.getPackageManager().getApplicationInfo(PROBE, 0).uid;
            TrafficMonitor.Snapshot data = null;
            long countedBy = System.currentTimeMillis() + 10000;
            do {
                data = TrafficMonitor.read(context, TrafficMonitor.startOfPeriod(0), System.currentTimeMillis());
                if (data.byUid.containsKey(uid) && data.byUid.get(uid).rx > 0) break;
                Thread.sleep(300); command("dumpsys netstats --poll");
            } while (System.currentTimeMillis() < countedBy);
            assertTrue("NetworkStats query unavailable: " + data.error, data.networks > 0);
            assertTrue("Received probe bytes not attributed to its UID", data.byUid.containsKey(uid) && data.byUid.get(uid).rx > 0);
        } finally { command("appops set " + context.getPackageName() + " GET_USAGE_STATS deny"); }
    }

}
