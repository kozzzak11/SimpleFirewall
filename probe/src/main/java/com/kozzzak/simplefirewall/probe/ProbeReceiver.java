package com.kozzzak.simplefirewall.probe;

import android.content.*;
import android.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** CI-only app running under an ordinary UID, without VPN-owner privileges. */
public class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent request) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            boolean reachable = false;
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            try {
                if (request.getBooleanExtra("bind", false)) {
                    for (Network net : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                            cm.bindProcessToNetwork(net); break;
                        }
                    }
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("10.0.2.2", 8787), 1200);
                    socket.setSoTimeout(1200);
                    socket.getOutputStream().write("GET /probe.txt HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024]; int n;
                    while ((n = socket.getInputStream().read(buf)) != -1) body.write(buf, 0, n);
                    reachable = body.toString("UTF-8").contains("sf-ok");
                }
            } catch (IOException | SecurityException expectedWhenBlocked) {
                reachable = false;
            } finally {
                cm.bindProcessToNetwork(null);
                c.sendBroadcast(new Intent("com.kozzzak.simplefirewall.PROBE_RESULT")
                    .setPackage("com.kozzzak.simplefirewall")
                    .putExtra("request", request.getStringExtra("request")).putExtra("reachable", reachable));
                pending.finish();
            }
        }, "network-probe").start();
    }
}
