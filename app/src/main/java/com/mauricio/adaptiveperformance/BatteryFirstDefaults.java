package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** One-time update migration: optional I/O must require explicit user opt-in. */
final class BatteryFirstDefaults {
    private static final String MIGRATED = "battery_first_defaults_117";
    private BatteryFirstDefaults() {}

    static void apply(SharedPreferences prefs) {
        if (prefs.getBoolean(MIGRATED, false)) return;
        prefs.edit()
                .putBoolean("auto_preload_enabled", false)
                .putBoolean("auto_preload_monitor_pending", false)
                .putInt("auto_preload_count", 0)
                .putStringSet("auto_preload_apps", new java.util.LinkedHashSet<String>())
                .putString("auto_preload_status", "Pré-carregamento opcional desativado para economizar bateria")
                .putBoolean(MIGRATED, true).commit();
    }
}
