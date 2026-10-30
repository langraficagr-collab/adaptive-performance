package com.mauricio.adaptiveperformance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Internal notification action; the actual freeze is performed by the bound Shizuku service. */
public final class ThermalDecisionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!ThermalAppAdvisor.ACTION_FREEZE.equals(action) && !ThermalAppAdvisor.ACTION_IGNORE.equals(action)) return;
        String pkg = intent.getStringExtra(ThermalAppAdvisor.EXTRA_PKG);
        long token = intent.getLongExtra(ThermalAppAdvisor.EXTRA_TOKEN, 0);
        SharedPreferences prefs = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        boolean freeze = ThermalAppAdvisor.ACTION_FREEZE.equals(action);
        if (!ThermalAppAdvisor.consume(context, prefs, pkg, token, freeze)) return;
        if (freeze) {
            Intent service = new Intent(context, OptimizationService.class)
                    .setAction(OptimizationService.ACTION_FREEZE_HEAT_APP)
                    .putExtra(ThermalAppAdvisor.EXTRA_PKG, pkg);
            try { context.startForegroundService(service); }
            catch (Exception e) {
                prefs.edit().putString("heat_advisor_status", "Não foi possível iniciar o congelamento: " + e.getClass().getSimpleName()).apply();
            }
        }
    }
}
