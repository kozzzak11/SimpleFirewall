package com.kozzzak.simplefirewall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Restores the firewall after reboot or app update when the user left it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!Rules.enabled(c) || Rules.interrupted(c) || FirewallService.running) return;
        // Without stored VPN consent only the user can re-enable it from the app.
        if (VpnService.prepare(c) != null) { FirewallService.notifyOff(c, "Нет разрешения на VPN. Нажмите, чтобы включить снова."); return; }
        try { c.startForegroundService(new Intent(c, FirewallService.class).setAction(FirewallService.START)); }
        catch (RuntimeException e) { FirewallService.notifyOff(c, "Android не дал запустить firewall. Нажмите, чтобы включить."); }
    }
}
