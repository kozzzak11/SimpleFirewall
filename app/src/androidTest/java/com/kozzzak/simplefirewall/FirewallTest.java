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
    private final Context context = instrumentation.getTargetContext();

    private void command(String value) throws Exception {
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(value);
             FileInputStream input = new FileInputStream(fd.getFileDescriptor())) {
            byte[] buf = new byte[1024]; while (input.read(buf) != -1) { /* wait for command */ }
        }
    }
    private void setRules(java.util.Set<String> packages) throws Exception {
        Rules.save(context, packages); Rules.enabled(context, true);
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, FirewallService.class)));
        instrumentation.waitForIdleSync();
        long end = System.currentTimeMillis() + 6000;
        while (!FirewallService.running && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(FirewallService.error, FirewallService.running);
    }
    private boolean connects() { return connects(false); }
    private boolean connects(boolean bind) {
        String request = java.util.UUID.randomUUID().toString();
        CountDownLatch reply = new CountDownLatch(1);
        AtomicBoolean reachable = new AtomicBoolean();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (request.equals(i.getStringExtra("request"))) {
                    reachable.set(i.getBooleanExtra("reachable", false)); reply.countDown();
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
        for (int i = 0; i < 5; i++) { if (connects()) return; Thread.sleep(100); }
        fail("Allowed app cannot reach local test server");
    }

    @Before public void prepare() throws Exception {
        command("appops set " + context.getPackageName() + " ACTIVATE_VPN allow");
        instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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
        assertFalse("Unselected app leaked IPv4 traffic", connects());

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
        setRules(Collections.emptySet());
        assertFalse("Revoked app still has Internet", connects());
        Rules.enabled(context, false);
        instrumentation.runOnMainSync(() -> context.startService(new Intent(context, FirewallService.class).setAction(FirewallService.STOP)));
        instrumentation.waitForIdleSync();
        assertFalse("Firewall still reports active after STOP", FirewallService.running);
        expectReachable();
    }
}
