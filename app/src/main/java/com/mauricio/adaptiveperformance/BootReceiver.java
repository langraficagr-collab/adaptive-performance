package com.mauricio.adaptiveperformance;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("adaptive", Context.MODE_PRIVATE)
                .getBoolean("master", false);
        if (!enabled) return;
        Intent svc = new Intent(context, OptimizationService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Throwable ignored) {}
    }
}
