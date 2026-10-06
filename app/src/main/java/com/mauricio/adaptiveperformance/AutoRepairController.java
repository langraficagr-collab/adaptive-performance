package com.mauricio.adaptiveperformance;

import android.content.SharedPreferences;
import java.util.Locale;

public final class AutoRepairController {
    private static final String[] METRICS = {"ram_free_pct", "cpu_load", "thermal_soc_c", "psi_memory", "lmk_delta"};
    private AutoRepairController() {}
    private static float metric(SharedPreferences p, String key) {
        Object value = p.getAll().get(key);
        return value instanceof Number ? ((Number)value).floatValue() : -1f;
    }
    public static synchronized void begin(SharedPreferences p, String cause) {
        if (!p.getBoolean("auto_repair", true) || !p.getBoolean("action_effectiveness", true)
                || p.getLong("repair_started_at", 0) != 0) return;
        SharedPreferences.Editor e = p.edit().putString("repair_cause", cause)
                .putLong("repair_started_at", System.currentTimeMillis()).putLong("repair_success_at", 0);
        for (String key : METRICS) e.putFloat("repair_before_" + key, metric(p, key));
        e.commit();
    }
    public static synchronized void finish(SharedPreferences p, String cause, boolean success, String action) {
        IncidentHistory.add(p, cause, action, success ? "Sucesso" : "Não confirmado", -1);
        if (!cause.equals(p.getString("repair_cause", ""))) return;
        if (success) {
            p.edit().putLong("repair_success_at", System.currentTimeMillis())
                    .putString("repair_action", action).apply();
            AdaptiveIntelligenceController.noteCorrection(p, cause);
        }
        else p.edit().remove("repair_started_at").remove("repair_success_at").remove("repair_cause").apply();
    }
    public static boolean safe(SharedPreferences p) {
        float ram = metric(p, "ram_free_pct"), cpu = metric(p, "cpu_load");
        float temp = metric(p, "thermal_soc_c"), psi = metric(p, "psi_memory");
        long age = System.currentTimeMillis() - p.getLong("last_sample", 0);
        return age >= 0 && age < 120000 && ram >= 20 && cpu >= 0 && cpu <= 55
                && temp > 0 && temp <= 55 && psi >= 0 && psi < 5
                && metric(p, "psi_io") >= 0 && metric(p, "psi_io") < 7;
    }
    public static synchronized void evaluate(SharedPreferences p, CpuPressureController cpu) {
        long started = p.getLong("repair_started_at", 0), success = p.getLong("repair_success_at", 0);
        long now = System.currentTimeMillis();
        if (started == 0) return;
        if (!p.getBoolean("auto_repair", true) || !p.getBoolean("action_effectiveness", true)
                || now - started > 300000 || now < started) {
            p.edit().remove("repair_started_at").remove("repair_success_at").remove("repair_cause").apply(); return;
        }
        if (success == 0 || now - success < 45000 || p.getLong("last_sample", 0) < success + 45000) return;
        float[] d = new float[5];
        boolean[] valid = new boolean[5];
        float weighted = 0; int samples = 0;
        float[] scale = {5, 15, 2, 5, 3};
        for (int i = 0; i < METRICS.length; i++) {
            float before = p.getFloat("repair_before_" + METRICS[i], -1);
            float after = metric(p, METRICS[i]);
            valid[i] = before >= 0 && after >= 0;
            if (!valid[i]) continue;
            d[i] = i == 0 ? after - before : before - after;
            weighted += Math.max(-1, Math.min(1, d[i] / scale[i])); samples++;
        }
        int score = samples == 0 ? 0 : Math.max(0, Math.min(100, Math.round(100 * weighted / samples)));
        String summary = "RAM " + delta(d[0], valid[0], false, "%") + ", CPU " + delta(d[1], valid[1], true, "%")
                + ", temp " + delta(d[2], valid[2], true, "°C") + ", PSI " + delta(d[3], valid[3], true, "");
        String cause = p.getString("repair_cause", "");
        p.edit().putInt("action_effectiveness_score", score).putString("last_effectiveness_summary", summary)
                .putLong("repair_evaluated_at", now).remove("repair_started_at").remove("repair_success_at")
                .remove("repair_cause").apply();
        IncidentHistory.add(p, cause, p.getString("repair_action", "Correção"), summary, score);
        // Unknown thermal/pressure readings cannot authorize rollback.
        float ram = metric(p, "ram_free_pct"), temp = metric(p, "thermal_soc_c");
        float load = metric(p, "cpu_load"), psi = metric(p, "psi_memory"), io = metric(p, "psi_io");
        if (samples >= 4 && score < 20 && cpu != null && cause.matches("ram_pressure|cpu_pressure|io_pressure")
                && ram >= 15 && load >= 0 && load < 85 && temp > 0 && temp <= 55
                && psi >= 0 && psi < 15 && io >= 0 && io < 20) {
            int released = cpu.releaseBatch(2);
            p.edit().putLong("auto_repair_block_until_" + cause, now + 30L * 60L * 1000L).apply();
            IncidentHistory.add(p, cause, "Auto-Reparo rollback", "Liberados: " + released + "; cooldown 30 min", score);
        }
    }
    private static String delta(float value, boolean valid, boolean invert, String unit) {
        return valid ? String.format(Locale.US, "%+.1f%s", invert ? -value : value, unit) : "indisponível";
    }
}
