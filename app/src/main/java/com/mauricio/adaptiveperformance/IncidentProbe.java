package com.mauricio.adaptiveperformance;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.Locale;

/** Bounded no-shell incident snapshot, independent of privileged diagnostics. */
final class IncidentProbe {
    private final SharedPreferences prefs;
    private long until, cooldown, last;
    private int samples;
    IncidentProbe(SharedPreferences prefs) {
        this.prefs = prefs;
    }
    void observe(float batteryTemp, float socTemp, double cpu, double ram,
                 float jank, float power, boolean screenOn, boolean predictedHeat) {
        long now = SystemClock.elapsedRealtime();
        boolean hot = (batteryTemp > 0 && batteryTemp >= 38.5f)
                || (socTemp > 0 && socTemp >= 60f) || predictedHeat;
        boolean stalled = cpu >= 90d || (ram >= 0 && ram <= 8d) || jank >= 18f;
        if (until <= now && now >= cooldown && screenOn && (hot || stalled)) {
            until = now + 180000L;
            cooldown = now + 23L * 60L * 1000L;
            samples = 0;
            last = 0L;
            prefs.edit().putString("incident_probe_log", "")
                    .putString("incident_probe_status",
                            hot ? "Análise temporária: risco térmico" :
                                    "Análise temporária: pressão/travamento")
                    .putLong("incident_probe_started_at", System.currentTimeMillis()).apply();
        }
        if (now >= until || until == 0L) {
            if (until > 0L && now >= until
                    && prefs.getString("incident_probe_status","").startsWith("Análise temporária"))
                prefs.edit().putString("incident_probe_status",
                        "Diagnóstico concluído; registro disponível no relatório").apply();
            return;
        }
        if (last != 0L && now - last < 60000L) return;
        last = now;
        samples++;
        String line = String.format(Locale.US,
                "amostra %d: bateria %.1f C, SoC %.1f C, CPU %.0f%%, RAM %.0f%%, jank %.1f%%, potência %.2fW",
                samples, batteryTemp, socTemp, cpu, ram, jank, power);
        String log = prefs.getString("incident_probe_log", "");
        prefs.edit().putString("incident_probe_log",
                        (log + "\n" + line).substring(0, Math.min(2000, (log + "\n" + line).length())))
                .apply();
    }
}
