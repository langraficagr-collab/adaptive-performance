package com.mauricio.adaptiveperformance;

import android.content.*;
import android.net.VpnService;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // A receiver declared exported can receive unexpected intents. Only
        // system boot and own package replacement events should restart services.
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        android.content.SharedPreferences p=context.getSharedPreferences("adaptive",Context.MODE_PRIVATE);
        ServiceContinuityMonitor.markBoot(p);
        boolean enabled=p.getBoolean("master",false);
        boolean privateDns=p.getBoolean("private_dns_enabled",false) || p.getBoolean("private_dns_requested",false);
        boolean dnsFirewall=p.getBoolean("dns_firewall_enabled",false) && !privateDns;

        if(enabled){
            Intent svc=new Intent(context,OptimizationService.class);
            try{ if(Build.VERSION.SDK_INT>=26) context.startForegroundService(svc); else context.startService(svc); }
            catch(Throwable error) {
                p.edit().putString("continuity_note",
                        "Android bloqueou reinício de serviço: " + error.getClass().getSimpleName()).apply();
            }
        }

        if(dnsFirewall && VpnService.prepare(context)==null){
            Intent dns=new Intent(context,AdGuardDnsVpnService.class).setAction(AdGuardDnsVpnService.ACTION_START);
            try{ if(Build.VERSION.SDK_INT>=26) context.startForegroundService(dns); else context.startService(dns); }
            catch(Throwable ignored){}
        }
    }
}
