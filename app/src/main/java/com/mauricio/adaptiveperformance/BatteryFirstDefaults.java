package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** One-time update migration: optional I/O must require explicit user opt-in. */
final class BatteryFirstDefaults {
    private static final String MIGRATED = "battery_first_defaults_117";
    private BatteryFirstDefaults() {}

    static void apply(SharedPreferences prefs) {
        if (!prefs.getBoolean("zram_profile_normalized_118", false)) {
            String profile = prefs.getString("zram_profile", "normal");
            SharedPreferences.Editor migrated = prefs.edit()
                    .putBoolean("zram_profile_normalized_118", true);
            // The removed ZRAM profile selector cannot restore legacy custom values.
            if (!"normal".equals(profile) && !"auto".equals(profile))
                migrated.putString("zram_profile", "normal");
            migrated.commit();
        }
        // Older installations may have completed 1.11.7 migration while still
        // retaining a visible list of cached apps from a previous session.
        if (!prefs.getBoolean("preload_list_reset_119", false)) {
            SharedPreferences.Editor reset = prefs.edit()
                    .putBoolean("preload_list_reset_119", true);
            if (!prefs.getBoolean("auto_preload_enabled", false))
                reset.putStringSet("auto_preload_apps", java.util.Collections.emptySet())
                        .putInt("auto_preload_count", 0);
            reset.commit();
        }
        if (prefs.getBoolean(MIGRATED, false)) return;
        prefs.edit()
                .putBoolean("auto_preload_enabled", false)
                .putBoolean("auto_preload_monitor_pending", false)
                .putInt("auto_preload_count", 0)
                .putStringSet("auto_preload_apps", java.util.Collections.emptySet())
                .putString("auto_preload_status", "Pré-carregamento opcional desativado para economizar bateria")
                .putBoolean(MIGRATED, true).commit();
    }
}
