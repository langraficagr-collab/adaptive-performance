package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;

/** Detects possible silent service gaps; does NOT pretend to bypass HyperOS restrictions. */
final class ServiceContinuityMonitor {
    private static final long GAP_MS = 15L * 60L * 1000L;
    private ServiceContinuityMonitor() {}

    static void onServiceStart(SharedPreferences p) {
        long now = System.currentTimeMillis();
        long oldBeat = p.getLong("continuity_heartbeat_at", 0L);
        boolean clean = p.getBoolean("continuity_clean_exit", true);
        if (oldBeat > 0L && now > oldBeat && now - oldBeat > GAP_MS
                && !clean && p.getBoolean("master", false)) {
            p.edit().putLong("continuity_last_gap_ms", now - oldBeat)
                    .putLong("continuity_possible_interruption_at", now)
                    .putString("continuity_note",
                            "Possível interrupção do serviço; verificar bateria/autostart HyperOS")
                    .apply();
        }
        p.edit().putBoolean("continuity_clean_exit", false)
                .putLong("continuity_started_at", now).apply();
    }

    static void heartbeat(SharedPreferences p) {
        long now = System.currentTimeMillis();
        p.edit().putLong("continuity_heartbeat_at", now).apply();
    }

    static void onServiceStop(SharedPreferences p) {
        if (p == null) return;
        p.edit().putBoolean("continuity_clean_exit", !p.getBoolean("master", false))
                .putLong("continuity_stopped_at", System.currentTimeMillis()).apply();
    }

    static void markBoot(SharedPreferences p) {
        p.edit().putBoolean("continuity_clean_exit", true)
                .putString("continuity_note", "Reinício/atualização do Android detectado").apply();
    }

    static String summary(SharedPreferences p, boolean en) {
        long at = p.getLong("continuity_possible_interruption_at", 0L);
        if (at <= 0) return en
                ? "HyperOS continuity: no unexpected interruption confirmed"
                : "Continuidade HyperOS: nenhuma interrupção inesperada confirmada";
        long minutes = p.getLong("continuity_last_gap_ms", 0L) / 60000L;
        return en
                ? "HyperOS: possible " + minutes + " min service gap; check autostart and background battery restrictions. Not a confirmed OEM kill."
                : "HyperOS: possível intervalo de " + minutes
                + " min sem serviço. Verifique início automático e restrições de bateria. Não confirma encerramento pela ROM.";
    }
}
