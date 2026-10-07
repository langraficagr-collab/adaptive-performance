package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Restricts only background location delivery while the display is off. */
public final class LocationBatteryController {
    private static final String KEY = "location_background_throttle_interval_ms";
    private static final long THROTTLED_MS = 900_000L;

    private LocationBatteryController() {}

    public static void evaluate(SharedPreferences prefs, IPrivilegedService shell,
                               boolean interactive, boolean charging) {
        if (shell == null) return;
        boolean enabled = prefs.getBoolean("gps_battery_saver_enabled", false);
        if (!enabled || interactive || charging) {
            restore(prefs, shell);
            return;
        }

        try {
            String current = shell.exec("settings get global " + KEY + " 2>/dev/null");
            if (current == null || current.trim().isEmpty() || "null".equals(current.trim())) {
                prefs.edit().putString("gps_battery_saver_status",
                        "Configuração de localização não disponível neste Android").apply();
                return;
            }
            long value = Long.parseLong(current.trim());
            if (!prefs.getBoolean("gps_battery_saver_applied", false) && value < THROTTLED_MS) {
                prefs.edit().putLong("gps_battery_saver_original_ms", value)
                        .putBoolean("gps_battery_saver_applied", true).apply();
            }
            if (value < THROTTLED_MS) {
                String out = shell.exec("settings put global " + KEY + " " + THROTTLED_MS
                        + " && settings get global " + KEY);
                if (out != null && out.contains(Long.toString(THROTTLED_MS))) {
                    prefs.edit().putString("gps_battery_saver_status",
                            "Economia GPS ativa: atualizações em segundo plano a cada 15 min").apply();
                } else {
                    prefs.edit().putString("gps_battery_saver_status",
                            "O Android não aceitou limitar a localização em segundo plano").apply();
                }
            } else {
                prefs.edit().putString("gps_battery_saver_status",
                        "Economia GPS ativa: limite do sistema já está aplicado").apply();
            }
        } catch (Throwable t) {
            prefs.edit().putString("gps_battery_saver_status",
                    "Não foi possível ajustar localização: " + t.getClass().getSimpleName()).apply();
        }
    }

    public static void restore(SharedPreferences prefs, IPrivilegedService shell) {
        if (shell == null || !prefs.getBoolean("gps_battery_saver_applied", false)) return;
        try {
            long original = prefs.getLong("gps_battery_saver_original_ms", -1L);
            if (original < 0L) return;
            String out = shell.exec("settings put global " + KEY + " " + original
                    + " && settings get global " + KEY);
            String current = shell.exec("settings get global " + KEY + " 2>/dev/null");
            if (current != null && current.trim().equals(Long.toString(THROTTLED_MS))
                    && out != null && out.contains(Long.toString(original))) {
                prefs.edit().putBoolean("gps_battery_saver_applied", false)
                        .putString("gps_battery_saver_status", "Configuração original de localização restaurada")
                        .apply();
            } else if (current != null && current.trim().equals(Long.toString(original))) {
                prefs.edit().putBoolean("gps_battery_saver_applied", false)
                        .putString("gps_battery_saver_status", "Configuração original de localização restaurada")
                        .apply();
            } else {
                prefs.edit().putBoolean("gps_battery_saver_applied", false)
                        .putString("gps_battery_saver_status", "A localização mudou por outra configuração; valor preservado")
                        .apply();
            }
        } catch (Throwable t) {
            prefs.edit().putString("gps_battery_saver_status",
                    "Restauração pendente: " + t.getClass().getSimpleName()).apply();
        }
    }
}
