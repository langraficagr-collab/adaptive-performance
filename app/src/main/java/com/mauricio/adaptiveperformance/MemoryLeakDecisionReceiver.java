package com.mauricio.adaptiveperformance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Internal consent action. App selection is only changed after explicit approval. */
public final class MemoryLeakDecisionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null) return;
        String action = i.getAction();
        if (!MemoryLeakFreezeAdvisor.ACTION_APPROVE.equals(action)
                && !MemoryLeakFreezeAdvisor.ACTION_IGNORE.equals(action)) return;
        SharedPreferences p = c.getSharedPreferences("adaptive", Context.MODE_PRIVATE);
        String pkg = i.getStringExtra(MemoryLeakFreezeAdvisor.EXTRA_PKG);
        long token = i.getLongExtra(MemoryLeakFreezeAdvisor.EXTRA_TOKEN, 0L);
        boolean approved = MemoryLeakFreezeAdvisor.ACTION_APPROVE.equals(action);
        if (!MemoryLeakFreezeAdvisor.consume(c, p, pkg, token, approved)) return;
        if (!approved) return;
        try {
            Intent service = new Intent(c, OptimizationService.class)
                    .setAction(OptimizationService.ACTION_ADD_LEAK_FREEZE)
                    .putExtra(MemoryLeakFreezeAdvisor.EXTRA_PKG, pkg);
            c.startForegroundService(service);
        } catch (Throwable t) {
            p.edit().putString("leak_advisor_status", "Autorizado, mas serviço indisponível: "
                    + t.getClass().getSimpleName()).apply();
        }
    }
}
